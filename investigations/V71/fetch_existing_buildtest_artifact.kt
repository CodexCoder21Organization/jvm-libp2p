package investigations.v71

import foundation.url.protocol.Libp2pPeer
import foundation.url.resolver.PersistentRpcConnection
import foundation.url.resolver.UrlProtocol2
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.math.min
import kotlin.system.exitProcess

private const val SERVICE_URL = "url://buildtest/"
private const val RPC_TIMEOUT_MILLIS = 30_000L
private const val SINGLE_SHOT_RAW_THRESHOLD = 5L * 1024L * 1024L
private const val ARTIFACT_CHUNK_BYTES = 4 * 1024 * 1024

private const val BUILDTEST_PEER_ID = "12D3KooWLMyXNfwhcX1YsiNx3hnjk3GGSfsU1fydRa8bzrE6scMT"
private const val BUILDTEST_PEER_ADDRESS = "/ip4/198.199.106.165/tcp/35000/p2p/$BUILDTEST_PEER_ID"

private val BUILDTEST_BOOTSTRAP_PEERS = listOf(
    Libp2pPeer.remote(
        peerId = BUILDTEST_PEER_ID,
        multiaddresses = listOf(BUILDTEST_PEER_ADDRESS),
        advertisedServices = listOf("buildtest")
    )
)

private val USAGE = """
Usage: fetch_existing_buildtest_artifact.bash <run-id> <output-file>

Reads artifact metadata and bytes from the existing BuildTest run. The output
path must not already exist. Incomplete bytes stay in a temporary file beside
the requested output and are removed if the read does not finish.

Example, capped to five minutes:
  timeout 300s bash investigations/V71/fetch_existing_buildtest_artifact.bash f0f67dc5 investigations/V71/V71-native-reports.jar
""".trimIndent()

fun main(args: Array<String>) {
    if (args.size == 1 && (args[0] == "--help" || args[0] == "-h")) {
        println(USAGE)
        return
    }
    if (args.size != 2) {
        System.err.println(USAGE)
        exitProcess(2)
    }

    try {
        downloadExistingArtifact(args[0], Path.of(args[1]))
    } catch (e: Exception) {
        System.err.println("Artifact read did not complete: ${e.message ?: e.javaClass.simpleName}")
        exitProcess(1)
    }
}

private fun downloadExistingArtifact(runId: String, requestedOutput: Path) {
    require(runId.isNotBlank()) { "Run ID must not be blank." }

    val output = requestedOutput.toAbsolutePath().normalize()
    require(!Files.exists(output)) { "Output path already exists: $output" }
    val parent = output.parent ?: error("Output path has no parent directory: $output")
    Files.createDirectories(parent)

    val temporary = Files.createTempFile(parent, ".${output.fileName}.", ".part")
    var protocol: UrlProtocol2? = null
    var connection: PersistentRpcConnection? = null

    try {
        val activeProtocol = UrlProtocol2(bootstrapPeers = BUILDTEST_BOOTSTRAP_PEERS)
        protocol = activeProtocol
        val activeConnection = activeProtocol.openPersistentRpcConnection(
            SERVICE_URL,
            RPC_TIMEOUT_MILLIS,
            emptyMap()
        )
        connection = activeConnection
        activeConnection.sendRequest("health", emptyMap(), RPC_TIMEOUT_MILLIS)

        val info = request(activeConnection, "getArtifactInfo", mapOf("runId" to runId))
        val hasArtifact = info.requiredBoolean("hasArtifact", "getArtifactInfo")
        require(hasArtifact) { "Run $runId has no returned artifact." }
        val isDirectory = info.requiredBoolean("isDirectory", "getArtifactInfo")
        require(!isDirectory) { "Run $runId returned a directory; this helper expects a JAR file." }
        val expectedSize = info.requiredLong("sizeBytes", "getArtifactInfo")
        require(expectedSize >= 0L) { "Run $runId reported a negative artifact size: $expectedSize" }

        BufferedOutputStream(Files.newOutputStream(temporary)).use { outputStream ->
            if (expectedSize <= SINGLE_SHOT_RAW_THRESHOLD) {
                writeSingleShotArtifact(activeConnection, runId, expectedSize, outputStream)
            } else {
                writeChunkedArtifact(activeConnection, runId, expectedSize, outputStream)
            }
        }

        val actualSize = Files.size(temporary)
        check(actualSize == expectedSize) {
            "Downloaded $actualSize bytes for run $runId; metadata reported $expectedSize bytes."
        }

        try {
            Files.move(temporary, output)
        } catch (e: Exception) {
            throw IllegalStateException("Could not move the complete artifact into place at $output: ${e.message}", e)
        }
        println("Saved $actualSize artifact bytes from run $runId to $output")
    } finally {
        try {
            connection?.close()
        } finally {
            try {
                protocol?.close()
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}

private fun writeSingleShotArtifact(
    connection: PersistentRpcConnection,
    runId: String,
    expectedSize: Long,
    output: BufferedOutputStream
) {
    val response = request(connection, "getArtifact", mapOf("runId" to runId))
    require(response.requiredBoolean("hasArtifact", "getArtifact")) {
        "Run $runId no longer has an artifact."
    }
    val isDirectory = response.requiredBoolean("isDirectory", "getArtifact")
    require(!isDirectory) { "Run $runId returned a directory instead of a JAR file." }
    val encoded = response["artifact"] as? String
        ?: error("getArtifact for run $runId did not return its Base64 artifact field.")
    val bytes = Base64.getDecoder().decode(encoded)
    check(bytes.size.toLong() == expectedSize) {
        "getArtifact returned ${bytes.size} bytes for run $runId; metadata reported $expectedSize bytes."
    }
    output.write(bytes)
}

private fun writeChunkedArtifact(
    connection: PersistentRpcConnection,
    runId: String,
    expectedSize: Long,
    output: BufferedOutputStream
) {
    var offset = 0L
    while (offset < expectedSize) {
        val requestedLength = min(ARTIFACT_CHUNK_BYTES.toLong(), expectedSize - offset).toInt()
        val response = request(
            connection,
            "getArtifactChunk",
            mapOf("runId" to runId, "offset" to offset, "length" to requestedLength)
        )
        val encoded = response["data"] as? String
            ?: error("getArtifactChunk for run $runId at offset $offset did not return its Base64 data field.")
        val bytes = Base64.getDecoder().decode(encoded)
        val reportedSize = response.requiredLong("size", "getArtifactChunk")
        check(reportedSize == bytes.size.toLong()) {
            "getArtifactChunk for run $runId at offset $offset reported $reportedSize bytes but decoded ${bytes.size}."
        }
        check(bytes.isNotEmpty()) {
            "getArtifactChunk for run $runId returned no bytes at offset $offset before the $expectedSize-byte artifact was complete."
        }
        check(bytes.size <= requestedLength && offset + bytes.size <= expectedSize) {
            "getArtifactChunk for run $runId at offset $offset exceeded the requested artifact bounds."
        }
        output.write(bytes)
        offset += bytes.size
    }
    check(offset == expectedSize) {
        "Downloaded $offset bytes for run $runId; metadata reported $expectedSize bytes."
    }
}

private fun request(
    connection: PersistentRpcConnection,
    method: String,
    parameters: Map<String, Any?>
): Map<String, Any?> = connection.sendRequest(method, parameters, RPC_TIMEOUT_MILLIS)

private fun Map<String, Any?>.requiredBoolean(key: String, method: String): Boolean =
    this[key] as? Boolean ?: error("$method response did not include Boolean field '$key'.")

private fun Map<String, Any?>.requiredLong(key: String, method: String): Long =
    (this[key] as? Number)?.toLong() ?: error("$method response did not include numeric field '$key'.")

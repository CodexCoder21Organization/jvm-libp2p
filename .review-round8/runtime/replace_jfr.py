from pathlib import Path
base=Path(__file__).resolve().parent.parent/'jvm-libp2p/libp2p/src/test/kotlin/io/libp2p/security/noise'
for name in ['NoiseAlgorithmReadinessTest.kt','NoiseBindingReadinessTest.kt']:
 p=base/name;s=p.read_text()
 prefix='            ' if name.startswith('NoiseAlgorithm') else '        '
 line=prefix+'val output = Files.createTempFile('
 pos=s.index('\n',s.index(line))+1
 s=s[:pos]+prefix+'val classLoads = output.resolveSibling(output.fileName.toString() + ".classes")\n'+s[pos:]
 s=s.replace('"-Xmx128m",','"-Xmx128m",\n'+prefix+'        "-Xlog:class+load=info:file=$classLoads:none:filecount=0",\n'+prefix+'        "-Dnoise.class.load.log=$classLoads",',1)
 s=s.replace(prefix+'    Files.deleteIfExists(output)',prefix+'    try {\n'+prefix+'        Files.deleteIfExists(classLoads)\n'+prefix+'    } finally {\n'+prefix+'        Files.deleteIfExists(output)\n'+prefix+'    }',1)
 p.write_text(s)
p=base/'NoiseAlgorithmReadinessTest.kt';s=p.read_text()
s=s.replace('import jdk.jfr.Recording\n','').replace('import jdk.jfr.consumer.RecordingFile\n','').replace('import java.time.Duration\n','')
s=s.replace('import java.io.File\n','import java.io.File\nimport java.io.RandomAccessFile\n')
s=s.replace('import java.nio.file.Files\n','import java.nio.file.Files\nimport java.nio.file.Path\n')
s=s.replace('        val recordingFile = Files.createTempFile("first-dial-", ".jfr")\n        var capture: Recording? = null\n','        val classLoads = Path.of(requireNotNull(System.getProperty("noise.class.load.log")))\n')
s=s.replace('            val activeCapture = Recording()\n            operation("JFR_CONSTRUCTION_DONE")\n            capture = activeCapture\n','')
start=s.index('            activeCapture.use { recording ->')
end=s.index('            operation("EVENT_PARSING_DONE")',start)
s=s[:start]+'''            // Unified logging writes synchronously, without rotation. A known preparation
            // class proves the logger is active before taking the first-stream byte range.
            // https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#enable-logging-with-the-jvm-unified-logging-framework
            check(Files.readString(classLoads).lineSequence().any {
                it.startsWith("com.southernstorm.noise.protocol.HandshakeState source: ")
            }) { "Class-load logging did not record Noise preparation before the first stream" }
            val loadStart = Files.size(classLoads)
            operation("CLASS_LOG_START_DONE")
            val opened = local.newStream<PingController>(
                listOf("/ipfs/ping/1.0.0"),
                requireNotNull(address.getPeerId()),
                address
            )
            val loadEnd: Long
            try {
                val controller = opened.controller.get(10, TimeUnit.SECONDS)
                operation("STREAM_NEGOTIATED")
                loadEnd = Files.size(classLoads)
                operation("CLASS_LOG_END_DONE")
                check(controller.ping().get(10, TimeUnit.SECONDS) >= 0)
                operation("PING_ANSWERED")
            } finally {
                opened.stream.get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS)
                operation("CLOSURE_DONE")
            }
            val loads = RandomAccessFile(classLoads.toFile(), "r").use { log ->
                log.seek(loadStart)
                val interval = ByteArray(Math.toIntExact(loadEnd - loadStart))
                log.readFully(interval)
                interval.toString(Charsets.UTF_8).lineSequence()
                    .filter { it.contains(" source: ") }
                    .map { it.substringBefore(" source: ") }
                    .filter { name ->
                        name.startsWith("com.southernstorm.noise.crypto.") ||
                            name == "com.southernstorm.noise.protocol.HandshakeState" ||
                            name == "com.southernstorm.noise.protocol.Pattern" ||
                            name == "com.southernstorm.noise.protocol.SymmetricState" ||
                            name == "com.southernstorm.noise.protocol.ChaChaPolyCipherState" ||
                            name == "com.southernstorm.noise.protocol.DHStateHybrid"
                    }.toList()
            }
'''+s[end:]
s=s.replace('val names = loads.map { it.getClass("loadedClass").name }.distinct().sorted()','val names = loads.distinct().sorted()')
s=s.replace('''                try {
                    capture?.close()
                } finally {
                    Files.deleteIfExists(recordingFile)
                    noiseProbePhase("CLEANUP_COMPLETE")
                }''','''                noiseProbePhase("CLEANUP_COMPLETE")''')
p.write_text(s)

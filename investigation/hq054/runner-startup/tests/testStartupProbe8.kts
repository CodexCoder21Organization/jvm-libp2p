@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
package native.startupprobe
import build.kotlin.withartifact.WithArtifact
fun testStartupProbe8() {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    check(digest.provider.name == "KompileTestSha256") { "Unexpected SHA-256 provider: ${digest.provider.name}" }
    check(digest.digest("abc".toByteArray()).joinToString("") { "%02x".format(it) } == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    System.out.println("STARTUP_PROBE_BODY 8")
}

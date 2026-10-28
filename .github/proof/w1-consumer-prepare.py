from pathlib import Path
import shutil, subprocess, xml.etree.ElementTree as ET
version = "4.2.10.1-codexcoder21-auto-read"
repo = Path("local-netty").resolve()
target = repo / "io/netty/netty-transport" / version
target.mkdir(parents=True)
for p in Path("release").iterdir(): shutil.copy2(p, target / p.name)
xml = ET.parse("evidence/TEST-io.netty.channel.DefaultChannelPipelineAutoReadTest.xml").getroot()
assert xml.get("tests") == "4" and all(xml.get(k, "0") == "0" for k in ("errors", "failures", "skipped")), xml.attrib
original = subprocess.check_output(["git", "show", "origin/develop:libp2p/src/test/kotlin/io/libp2p/mux/yamux/YamuxPausedStreamKeepsConnectionReadingTest.kt"],text=True)
original = original.replace("class YamuxPausedStreamKeepsConnectionReadingTest", "class DevelopYamuxPausedStreamKeepsConnectionReadingTest")
Path("libp2p/src/test/kotlin/io/libp2p/mux/yamux/DevelopYamuxPausedStreamKeepsConnectionReadingTest.kt").write_text(original)
Path("/tmp/w1-local-netty.init.gradle").write_text("""settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories.maven { url = uri('%s') }
}
allprojects { p ->
    afterEvaluate {
        if (p.path == ':libp2p') {
            tasks.register('w1TransportProof') {
                doLast {
                    def a = configurations.compileClasspath.resolvedConfiguration.resolvedArtifacts.findAll { it.moduleVersion.id.group == 'io.netty' && it.name == 'netty-transport' }
                    assert a.size() == 1
                    def f = a[0].file
                    assert f.canonicalPath.startsWith('%s') : f
                    println 'TRANSPORT_PROOF ' + a[0].moduleVersion.id + ' ' + f + ' SHA256=' + java.security.MessageDigest.getInstance('SHA-256').digest(f.bytes).encodeHex()
                }
            }
        }
    }
}
""" % (repo, repo))

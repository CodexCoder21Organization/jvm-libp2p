```python
from pathlib import Path
import hashlib, json, re, subprocess, urllib.request, xml.etree.ElementTree as ET, zipfile

out = Path(__file__).parent
version = "1.3.0-codexcoder21-snapshot-38"
artifact = "jvm-libp2p-" + version
stage = out / "PUBLIBP2P-stage"
download = out / "PUBLIBP2P-download"
download.mkdir(exist_ok=True)
base = "https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/" + version + "/"
results = {}
for ext in ["jar", "pom"]:
    name = artifact + "." + ext
    url = base + name
    with urllib.request.urlopen(url, timeout=90) as response:
        assert response.status == 200, (url, response.status)
        (download / name).write_bytes(response.read())
    local = hashlib.sha256((stage / name).read_bytes()).hexdigest()
    remote = hashlib.sha256((download / name).read_bytes()).hexdigest()
    assert local == remote, (name, local, remote)
    results[ext] = {"url": url, "sha256_local": local, "sha256_download": remote, "bytes": (download / name).stat().st_size}
pom = ET.parse(download / (artifact + ".pom")).getroot()
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
for key, value in [("groupId", "community.kotlin.libp2p"), ("artifactId", "jvm-libp2p"), ("version", version)]:
    assert pom.findtext("m:" + key, namespaces=ns) == value, (key, value)
deps = pom.findall("m:dependencies/m:dependency", ns)
assert deps, "POM must declare transitive dependencies"
assert all(d.findtext("m:version", namespaces=ns) for d in deps), "Every dependency must declare its resolved version"
results["dependency_count"] = len(deps)
jar = download / (artifact + ".jar")
with zipfile.ZipFile(jar) as z:
    assert "io/libp2p/mux/yamux/YamuxHandler.class" in z.namelist()
    assert not any(n.startswith("io/netty/") for n in z.namelist()), "Expected the ordinary library JAR"
for cls, filename, expected in [
    ("io.libp2p.mux.yamux.YamuxHandler", "PUBLIBP2P-published-YamuxHandler-javap.txt", ["coalescePendingChildRead", "sealPendingChildRead", "preparePendingChildRead", "getPauseParentReadsForPausedChild"]),
    ("io.libp2p.etc.util.netty.mux.AbstractMuxHandler", "PUBLIBP2P-published-AbstractMuxHandler-javap.txt", ["coalescePendingChildRead", "sealPendingChildRead"]),
]:
    result = subprocess.run(["javap", "-p", "-c", "-classpath", str(jar), cls], text=True, capture_output=True, check=True, timeout=60)
    (out / filename).write_text(result.stdout)
    for token in expected:
        assert token in result.stdout, (cls, token)
yamux = (out / "PUBLIBP2P-published-YamuxHandler-javap.txt").read_text()
assert re.search(r"getPauseParentReadsForPausedChild\(\);\s+Code:\s+0: iconst_0\s+1: ireturn", yamux), "Yamux paused-child getter must return false"
previous = ET.parse(out / "PUBLIBP2P-37-check.txt").getroot()
normalize = lambda d: ET.tostring(d, encoding="unicode")
assert [normalize(d) for d in deps] == [normalize(d) for d in previous.findall("m:dependencies/m:dependency", ns)], "Release POM dependencies must match unchanged Gradle dependency declarations"
results["coordinate"] = "community.kotlin.libp2p:jvm-libp2p:" + version
(out / "PUBLIBP2P-verified.json").write_text(json.dumps(results, indent=2) + "\n")
print(json.dumps(results, indent=2))

```

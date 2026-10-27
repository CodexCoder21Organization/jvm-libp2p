import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

source = Path("libp2p/src/test/kotlin/io/libp2p/security/noise/NoiseHandshakeOffEventLoopTest.kt")
xml = Path("libp2p/build/test-results/test/TEST-io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.xml")
selector = "io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.remote read timeout fails saturated handshakes while responder crypto is queued"
results = Path("proof-results")
results.mkdir()
green = source.read_bytes()
assert b'"-Xmx128m"' in green and b'"-Xss512k"' in green
summaries = []
for phase in ["red", "green", "reversal"]:
    source.write_bytes(green)
    subprocess.run(["git", "fetch", "origin", "develop"], check=True, timeout=60)
    subprocess.run(["git", "rebase", "origin/develop"], check=True, timeout=60)
    assert source.read_bytes() == green, "Default branch changed the proof source"
    if phase != "green":
        old = b"private const val NOISE_TEST_HOST = ISOLATED_NOISE_TEST_HOST"
        assert green.count(old) == 1
        source.write_bytes(green.replace(old, b"private const val NOISE_TEST_HOST = LEGACY_NOISE_TEST_HOST"))
    destination = results / phase
    destination.mkdir()
    shutil.copy2(source, destination / source.name)
    if xml.exists():
        xml.unlink()
    with (destination / "gradle.log").open("w") as log:
        outcome = subprocess.run(["./gradlew", "--no-daemon", "--no-build-cache", ":libp2p:test", "--tests", selector], stdout=log, stderr=subprocess.STDOUT, timeout=900)
    print((destination / "gradle.log").read_text(), flush=True)
    assert xml.exists(), f"{phase}: JUnit XML missing"
    shutil.copy2(xml, destination / xml.name)
    root = ET.parse(xml).getroot()
    cases = root.findall("testcase")
    assert len(cases) == 1 and cases[0].attrib["name"].startswith("remote read timeout fails saturated handshakes"), root.attrib
    assert root.attrib["errors"] == "0" and root.attrib["skipped"] == "0", root.attrib
    failures = cases[0].findall("failure")
    output = root.findtext("system-out", "") + root.findtext("system-err", "")
    if phase == "green":
        assert outcome.returncode == 0 and not failures, root.attrib
        assert "timedOut=120 readTimeoutFailures=120" in output, output
        assert "queuedResponderTasks=120" in output, output
    else:
        assert outcome.returncode != 0 and len(failures) == 1, root.attrib
        details = failures[0].attrib.get("message", "") + (failures[0].text or "")
        print(details, flush=True)
        assert "121 queued responder message-1 crypto tasks" in details and "120 dials failed" in details, details
        assert "AssertionError" in failures[0].attrib.get("type", ""), failures[0].attrib
        assert "ReadTimeoutException" in output + details, output + details
    summary = {"phase": phase, "attempt": 1, "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "gradle_exit": outcome.returncode, **root.attrib}
    summaries.append(summary)
    (results / "summary.json").write_text(json.dumps(summaries, indent=2))
    print(json.dumps(summary), flush=True)
source.write_bytes(green)
assert source.read_bytes() == green

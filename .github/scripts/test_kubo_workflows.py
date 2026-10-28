#!/usr/bin/env python3
"""Follow each native workflow to its Kubo fixture and run the real Java consumer.

Requires PyYAML 6.0.3 and the Kubo binary installed by bld-build.yml. Only the
Gradle task selection is narrowed; the workflow's wrapper, binary, log and
fixture arguments are executed unchanged. --contract-only checks wiring without
starting a JVM, for a quick regression/reversal check.
"""

import argparse
from pathlib import Path
import shlex
import subprocess
import unittest
import xml.etree.ElementTree as ET

import yaml

ROOT = Path(__file__).resolve().parents[2]


def native_commands(workflow, seen=()):
    if workflow in seen:
        raise AssertionError(f"Workflow call cycle: {seen + (workflow,)}")
    document = yaml.load((ROOT / workflow).read_text(), Loader=yaml.BaseLoader)
    for job in document["jobs"].values():
        if "uses" in job:
            called = job["uses"]
            if not called.startswith("./.github/workflows/"):
                raise AssertionError(f"Expected a repository-owned workflow, received {called}")
            yield from native_commands(called[2:], seen + (workflow,))
        else:
            for step in job.get("steps", []):
                command = step.get("run", "")
                if "./gradlew" in command and "build" in shlex.split(command):
                    yield command


class KuboWorkflowTest(unittest.TestCase):
    def verify_workflow(self, workflow):
        commands = list(native_commands(workflow))
        self.assertEqual(1, len(commands), f"Expected one native build command in {workflow}")
        command = shlex.split(commands[0])
        self.assertEqual(
            ["python3", ".github/scripts/kubo-node.py", "run"], command[:3],
            f"{workflow} must create the owned Kubo fixture before running the Java consumer",
        )
        self.assertEqual("./gradlew", command[6], "The owned node must wrap the Gradle command")
        if CONTRACT_ONLY:
            return
        # Supply the workflow's explicit runner temporary directory, preserving its paths.
        command = [word.replace("$RUNNER_TEMP", str(RUNNER_TEMP)) for word in command]
        self.assertFalse(any("$" in word for word in command), command)
        fixture = ROOT / command[5]
        self.assertFalse(fixture.exists(), f"Unexpected fixture before workflow: {fixture}")
        # cleanTest prevents a second entry point from accepting cached XML or a skipped task.
        command = command[:7] + [
            "--no-daemon", "--stacktrace", ":libp2p:cleanTest", ":libp2p:test",
            "--tests", "io.libp2p.transport.quic.QuicKuboTestJava",
        ]
        result = subprocess.run(command, cwd=ROOT, timeout=900)
        self.assertEqual(0, result.returncode, f"Java consumer failed via {workflow}")
        report = ROOT / "libp2p/build/test-results/test/TEST-io.libp2p.transport.quic.QuicKuboTestJava.xml"
        suite = ET.parse(report).getroot()
        self.assertEqual("0", suite.attrib["failures"])
        self.assertEqual("0", suite.attrib["errors"])
        self.assertEqual("0", suite.attrib["skipped"])
        self.assertEqual({"pingKubo()", "keyPairGeneration()"},
                         {case.attrib["name"] for case in suite.findall("testcase")})
        self.assertFalse(fixture.exists(), f"Workflow left its fixture behind: {fixture}")
        destination = ROOT / "build/kubo-workflow-results" / Path(workflow).stem
        destination.mkdir(parents=True, exist_ok=True)
        (destination / report.name).write_bytes(report.read_bytes())

    def test_legacy_workflow_reaches_java_consumer(self):
        self.verify_workflow(".github/workflows/build.yml")

    def test_native_workflow_reaches_java_consumer(self):
        self.verify_workflow(".github/workflows/bld-build.yml")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runner-temp", type=Path, default=Path("/tmp"))
    parser.add_argument("--contract-only", action="store_true")
    options, remaining = parser.parse_known_args()
    CONTRACT_ONLY = options.contract_only
    RUNNER_TEMP = options.runner_temp.resolve()
    unittest.main(argv=[__file__] + remaining, verbosity=2)

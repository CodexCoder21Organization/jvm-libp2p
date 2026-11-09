#!/usr/bin/env python3
"""Run native test selectors while their former fixed TCP ports are occupied."""

import argparse
import socket
import shutil
import subprocess
from contextlib import ExitStack
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tests", action="append", default=[])
    args = parser.parse_args()
    selectors = args.tests or [
        "io.libp2p.core.RpcHandlerTest",
        "io.libp2p.core.HostTestJava",
        "io.libp2p.pubsub.gossip.GossipBackwardCompatibilityTest",
        "io.libp2p.pubsub.gossip.GossipTwoHostTest",
        "io.libp2p.pubsub.gossip.SubscriptionsLimitTest",
    ]
    root = Path(__file__).resolve().parent.parent
    with ExitStack() as sockets:
        for port in (40001, 40002):
            listener = sockets.enter_context(socket.socket(socket.AF_INET, socket.SOCK_STREAM))
            listener.bind(("127.0.0.1", port))
            listener.listen()
        command = [str(root / "gradlew"), "--no-daemon", "--max-workers=2", ":libp2p:test", "--rerun"]
        for selector in selectors:
            command.extend(["--tests", selector])
        reports = root / "libp2p/build/test-results/test"
        for old_report in reports.glob("TEST-*.xml"):
            old_report.unlink()
        result = subprocess.run(command, cwd=root, timeout=1800, check=False)
        destination = root / "build/native-port-isolation-results"
        destination.mkdir(parents=True, exist_ok=True)
        for old_report in destination.glob("TEST-*.xml"):
            old_report.unlink()
        total = failed = skipped = 0
        for report in reports.glob("TEST-*.xml"):
            suite = ET.parse(report).getroot()
            total += int(suite.attrib["tests"])
            failed += int(suite.attrib["failures"]) + int(suite.attrib["errors"])
            skipped += int(suite.attrib["skipped"])
            shutil.copy2(report, destination / report.name)
        print(f"FIRST {total - failed - skipped}/{total}; failed={failed}; skipped={skipped}", flush=True)
        if result.returncode == 0 and (total == 0 or failed or skipped):
            raise AssertionError(
                f"Expected selected native tests to run and pass; tests={total}, failed={failed}, skipped={skipped}"
            )
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())

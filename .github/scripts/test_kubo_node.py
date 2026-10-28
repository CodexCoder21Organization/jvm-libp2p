#!/usr/bin/env python3
"""Real Kubo regression tests. Run with KUBO_BINARY=/path/to/ipfs python3 this-file."""

import contextlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request

SCRIPT = Path(__file__).with_name("kubo-node.py")
IPFS = os.environ["KUBO_BINARY"]


class KuboReadinessTest(unittest.TestCase):
    @contextlib.contextmanager
    def node(self, api_address="/ip4/127.0.0.1/tcp/0", start=True):
        with tempfile.TemporaryDirectory(prefix="kubo-readiness-test-") as directory:
            repo = Path(directory)
            env = dict(os.environ, IPFS_PATH=directory)
            subprocess.run([IPFS, "init"], env=env, check=True, capture_output=True)
            config_path = repo / "config"
            config = json.loads(config_path.read_text())
            config["Bootstrap"] = []
            config["Discovery"]["MDNS"]["Enabled"] = False
            config["Addresses"]["API"] = api_address
            config["Addresses"]["Gateway"] = ""
            config["Addresses"]["Swarm"] = ["/ip4/127.0.0.1/udp/0/quic-v1"]
            config_path.write_text(json.dumps(config))
            with (repo / "daemon.log").open("w+") as log:
                process = subprocess.Popen(
                    [IPFS, "daemon", "--routing=none"], env=env, stdout=log, stderr=log
                )
                try:
                    if start:
                        deadline = time.monotonic() + 60
                        while time.monotonic() < deadline:
                            self.assertIsNone(process.poll(), (repo / "daemon.log").read_text())
                            if (repo / "api").exists():
                                parts = (repo / "api").read_text().split("/")
                                api = "http://127.0.0.1:" + parts[4]
                                try:
                                    with urllib.request.urlopen(
                                        urllib.request.Request(api + "/api/v0/id", data=b""),
                                        timeout=1,
                                    ) as response:
                                        identity = json.load(response)
                                    self.assertEqual(config["Identity"]["PeerID"], identity["ID"])
                                    break
                                except OSError:
                                    pass
                            time.sleep(0.05)
                        else:
                            self.fail("Real Kubo did not start within 60 seconds")
                    else:
                        api = None
                    yield process, api, config["Identity"]["PeerID"], repo
                finally:
                    if process.poll() is None:
                        process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)

    def readiness(self, process, api, peer):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(process.pid), api, peer],
            text=True, capture_output=True, timeout=65,
        )

    def test_bind_failure_cannot_accept_another_node(self):
        with self.node() as (_, api, _, _):
            port = api.rsplit(":", 1)[1]
            with self.node("/ip4/127.0.0.1/tcp/" + port, start=False) as (owned, _, peer, repo):
                self.assertNotEqual(0, owned.wait(timeout=10))
                self.assertIn("bind: address already in use", (repo / "daemon.log").read_text())
                result = self.readiness(owned, api, peer)
                self.assertNotEqual(0, result.returncode, "Exited owned node was declared ready")
                self.assertIn("exited", result.stderr)

    def test_live_owned_node_cannot_accept_another_nodes_endpoint(self):
        with self.node() as (_, api, foreign_peer, _):
            with self.node() as (owned, _, peer, _):
                self.assertNotEqual(foreign_peer, peer)
                self.assertIsNone(owned.poll())
                result = self.readiness(owned, api, peer)
                self.assertNotEqual(0, result.returncode, "Unrelated live node was declared ready")
                self.assertIn("peer ID", result.stderr)
                self.assertIn(peer, result.stderr)
                self.assertIn(foreign_peer, result.stderr)

    def test_owned_node_is_ready(self):
        with self.node() as (owned, api, peer, _):
            result = self.readiness(owned, api, peer)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""Real Kubo regression tests. Run with KUBO_BINARY=/path/to/ipfs python3 this-file."""

import contextlib
import json
import os
import re
import shutil
import signal
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
            [sys.executable, str(SCRIPT), "ready", str(process.pid), api, peer],
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
                self.assertEqual(
                    f"Kubo process {owned.pid} exited before verification completed.\n", result.stderr
                )

    def test_live_owned_node_cannot_accept_another_nodes_endpoint(self):
        with self.node() as (_, api, foreign_peer, _):
            with self.node() as (owned, _, peer, _):
                self.assertNotEqual(foreign_peer, peer)
                self.assertIsNone(owned.poll())
                result = self.readiness(owned, api, peer)
                self.assertNotEqual(0, result.returncode, "Unrelated live node was declared ready")
                self.assertEqual(
                    f"Kubo API {api} returned peer ID {foreign_peer}; "
                    f"expected owned node peer ID {peer}.\n", result.stderr,
                )

    def test_owned_node_is_ready(self):
        with self.node() as (owned, api, peer, _):
            result = self.readiness(owned, api, peer)
            self.assertEqual(0, result.returncode, result.stderr)


class KuboCommandTest(unittest.TestCase):
    def run_command(self, code):
        with tempfile.TemporaryDirectory(prefix="kubo-command-test-") as directory:
            log = Path(directory) / "node.log"
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "run", IPFS, str(log), sys.executable, "-c", code],
                text=True, capture_output=True, timeout=75,
            )
            match = re.search(r"Owned Kubo PID (\d+)", result.stdout)
            self.assertIsNotNone(match, result.stdout + result.stderr + log.read_text())
            pid = int(match[1])
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)
            repo = re.search(r"initializing IPFS node at (.+)", result.stdout)[1]
            self.assertFalse(Path(repo).exists(), "Owned repository was not removed")
            return result

    def test_command_receives_owned_endpoints_and_exit_status(self):
        result = self.run_command(
            "import os,json,urllib.request; "
            "identity=json.load(urllib.request.urlopen(urllib.request.Request("
            "os.environ['KUBO_API_URL']+'/api/v0/id',data=b''))); "
            "assert identity['ID']==os.environ['KUBO_PEER_ID']; "
            "assert os.environ['KUBO_SWARM_ADDRESS']+'/p2p/'+identity['ID'] in identity['Addresses']; "
            "assert ':0' not in os.environ['KUBO_API_URL']; "
            "assert '/udp/0/' not in os.environ['KUBO_SWARM_ADDRESS']; "
            "raise SystemExit(23)"
        )
        self.assertEqual(23, result.returncode, result.stdout + result.stderr)

    def test_termination_reaps_node_and_command(self):
        with tempfile.TemporaryDirectory(prefix="kubo-termination-test-") as directory:
            directory = Path(directory)
            state = directory / "state.json"
            log = directory / "node.log"
            with (directory / "output.log").open("w+") as output:
                wrapper = subprocess.Popen(
                    [sys.executable, str(SCRIPT), "run", IPFS, str(log), sys.executable, "-c",
                     "import json,os,time; from pathlib import Path; "
                     "state=Path(" + repr(str(state)) + "); temporary=state.with_suffix('.tmp'); "
                     "temporary.write_text(json.dumps("
                     "{'pid':os.getpid(),'repo':os.environ['IPFS_PATH']})); "
                     "temporary.replace(state); time.sleep(60)"],
                    stdout=output, stderr=output,
                )
                child = None
                node_pid = None
                try:
                    deadline = time.monotonic() + 60
                    while not state.exists() and time.monotonic() < deadline:
                        self.assertIsNone(wrapper.poll())
                        time.sleep(0.05)
                    self.assertTrue(state.exists(), "Verification command did not start")
                    child = json.loads(state.read_text())
                    output.seek(0)
                    node_pid = int(re.search(r"Owned Kubo PID (\d+)", output.read())[1])
                    wrapper.terminate()
                    result = wrapper.wait(timeout=15)
                    with self.assertRaises(ProcessLookupError, msg="Kubo outlived its wrapper"):
                        os.kill(node_pid, 0)
                    with self.assertRaises(ProcessLookupError, msg="Command outlived its wrapper"):
                        os.kill(child["pid"], 0)
                    self.assertFalse(Path(child["repo"]).exists())
                    self.assertEqual(143, result)
                finally:
                    if wrapper.poll() is None:
                        wrapper.kill()
                    wrapper.wait(timeout=10)
                    # Reversal controls deliberately leave children; clean those exact PIDs.
                    for pid in [node_pid, child["pid"] if child else None]:
                        if pid is not None:
                            try:
                                os.kill(pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass  # Already reaped on the successful cleanup path.
                    if child and Path(child["repo"]).exists():
                        shutil.rmtree(child["repo"])

    def test_node_exit_fails_an_inflight_command(self):
        result = self.run_command(
            "import os,subprocess,time; "
            "subprocess.run([" + repr(IPFS) + ", 'shutdown'],check=True); time.sleep(60)"
        )
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("exited before verification completed", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)

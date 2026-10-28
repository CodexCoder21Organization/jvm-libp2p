#!/usr/bin/env python3
"""Run a command with a private Kubo node, or verify an owned node's readiness."""

import argparse
import json
import os
import signal
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


def require_running(pid):
    try:
        os.kill(pid, 0)
        # A child that exited but has not been reaped still answers kill(pid, 0).
        stat = Path(f"/proc/{pid}/stat")
        if stat.exists() and stat.read_text().rsplit(")", 1)[1].split()[0] == "Z":
            raise ProcessLookupError()
    except (ProcessLookupError, FileNotFoundError):
        raise RuntimeError(f"Kubo process {pid} exited before verification completed.")


def wait_for_node(pid, api, peer_id):
    for _ in range(60):
        require_running(pid)
        try:
            with urllib.request.urlopen(
                urllib.request.Request(api + "/api/v0/id", data=b""), timeout=1
            ) as response:
                identity = json.load(response)
        except (OSError, urllib.error.URLError):
            time.sleep(1)
            continue
        require_running(pid)
        if identity.get("ID") != peer_id:
            raise RuntimeError(
                f"Kubo API {api} returned peer ID {identity.get('ID')}; "
                f"expected owned node peer ID {peer_id}."
            )
        return identity
    raise RuntimeError(f"Kubo API {api} was not ready after 60 readiness probes.")


def stop(process):
    if process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def run_with_node(ipfs, log_path, command):
    with tempfile.TemporaryDirectory(prefix="bld-kubo-") as directory:
        repo = Path(directory)
        env = dict(os.environ, IPFS_PATH=directory)
        subprocess.run([ipfs, "init"], env=env, check=True)
        config_path = repo / "config"
        config = json.loads(config_path.read_text())
        config["Bootstrap"] = []
        config["Discovery"]["MDNS"]["Enabled"] = False
        config["Addresses"]["API"] = "/ip4/127.0.0.1/tcp/0"
        config["Addresses"]["Gateway"] = ""
        config["Addresses"]["Swarm"] = ["/ip4/127.0.0.1/udp/0/quic-v1"]
        config_path.write_text(json.dumps(config))
        with open(log_path, "w") as log:
            node = subprocess.Popen(
                [ipfs, "daemon", "--routing=none"], env=env, stdout=log, stderr=log
            )
            try:
                for _ in range(60):
                    require_running(node.pid)
                    if (repo / "api").exists():
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError("Kubo did not publish its API address after 60 probes.")
                address = (repo / "api").read_text().strip().split("/")
                api = f"http://{address[2]}:{address[4]}"
                peer = config["Identity"]["PeerID"]
                identity = wait_for_node(node.pid, api, peer)
                swarm = next(
                    address.rsplit("/p2p/", 1)[0]
                    for address in identity["Addresses"]
                    if address.startswith("/ip4/127.0.0.1/udp/") and "/quic-v1" in address
                )
                env.update(KUBO_API_URL=api, KUBO_SWARM_ADDRESS=swarm, KUBO_PEER_ID=peer)
                print(f"Owned Kubo PID {node.pid}, peer {peer}, API {api}, swarm {swarm}", flush=True)
                with subprocess.Popen(command, env=env) as verification:
                    try:
                        while True:
                            require_running(node.pid)
                            result = verification.poll()
                            if result is not None:
                                return result
                            time.sleep(0.1)
                    finally:
                        stop(verification)
            finally:
                stop(node)


def terminate(signum, _frame):
    raise SystemExit(128 + signum)


def main():
    signal.signal(signal.SIGTERM, terminate)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="mode", required=True)
    ready = commands.add_parser("ready", help="Check PID and expected peer ID at an API endpoint")
    ready.add_argument("pid", type=int)
    ready.add_argument("api")
    ready.add_argument("peer_id")
    run = commands.add_parser("run", help="Own an isolated node for the lifetime of a command")
    run.add_argument("ipfs")
    run.add_argument("log")
    run.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        if args.mode == "ready":
            wait_for_node(args.pid, args.api, args.peer_id)
            return 0
        if not args.command:
            parser.error("run requires the verification command after the log path")
        return run_with_node(args.ipfs, args.log, args.command)
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

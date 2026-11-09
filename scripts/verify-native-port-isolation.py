#!/usr/bin/env python3
"""Run native test selectors while their former fixed TCP ports are occupied."""

import argparse
import socket
import subprocess
from contextlib import ExitStack
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tests", action="append", default=[])
    args = parser.parse_args()
    selectors = args.tests or ["io.libp2p.core.RpcHandlerTest.test1"]
    root = Path(__file__).resolve().parent.parent
    with ExitStack() as sockets:
        for port in (40001, 40002, 4002):
            listener = sockets.enter_context(socket.socket(socket.AF_INET, socket.SOCK_STREAM))
            listener.bind(("127.0.0.1", port))
            listener.listen()
        command = [str(root / "gradlew"), "--no-daemon", "--max-workers=2", ":libp2p:test", "--rerun"]
        for selector in selectors:
            command.extend(["--tests", selector])
        return subprocess.run(command, cwd=root, timeout=1800, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())

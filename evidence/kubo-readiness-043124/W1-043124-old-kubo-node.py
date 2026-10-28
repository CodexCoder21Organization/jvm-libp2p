#!/usr/bin/env python3
"""Run native verification with a private Kubo node, or check an owned node."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request


def wait_for_node(pid, api, peer_id):
    # The original workflow's readiness criterion, retained here before its repair.
    for _ in range(60):
        try:
            with urllib.request.urlopen(
                urllib.request.Request(api + "/api/v0/id", data=b""), timeout=1
            ) as response:
                return json.load(response)
        except (OSError, urllib.error.URLError):
            pass
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            raise RuntimeError("Kubo exited before its local API became ready.")
        time.sleep(1)
    raise RuntimeError("Kubo did not expose its local API after 60 readiness probes.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pid", type=int)
    parser.add_argument("api")
    parser.add_argument("peer_id")
    args = parser.parse_args()
    try:
        wait_for_node(args.pid, args.api, args.peer_id)
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

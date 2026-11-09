#!/usr/bin/env python3
"""Prepare a pinned distribution and point the public Gradle wrapper at its local URI."""
import argparse
from pathlib import Path
import subprocess
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--properties", type=Path,
                        default=Path("gradle/wrapper/gradle-wrapper.properties"))
    parser.add_argument("--source", action="append", help="ordered distribution source URL")
    args = parser.parse_args()
    original = args.properties.read_text()
    properties = dict(line.split("=", 1) for line in original.splitlines() if "=" in line)
    primary = properties["distributionUrl"].replace("\\:", ":")
    name = Path(urlparse(primary).path).name
    # The alternate endpoint serves the same Gradle 8.14.3 asset as the wrapper URL.
    sources = args.source or [primary, "https://api.github.com/repos/gradle/gradle-distributions/releases/assets/269969095"]
    args.directory.mkdir(parents=True, exist_ok=True)
    archive = args.directory.resolve() / name
    # Keep the wrapper's existing 10-second network allowance as a total bound per attempt.
    network_seconds = max(1, min(30, int(properties["networkTimeout"]) // 1000))
    result = subprocess.run(
        ["bash", str(Path(__file__).with_name("download-verified.sh")),
         "--connect-timeout", str(network_seconds), "--max-time", str(network_seconds),
         str(archive), properties["distributionSha256Sum"], *sources], check=False,
    )
    if result.returncode:
        return result.returncode
    replacement = "distributionUrl=" + archive.as_uri().replace(":", "\\:")
    args.properties.write_text("\n".join(replacement if line.startswith("distributionUrl=") else line
                                           for line in original.splitlines()) + "\n")
    print(f"Gradle wrapper will use the verified distribution at {archive}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

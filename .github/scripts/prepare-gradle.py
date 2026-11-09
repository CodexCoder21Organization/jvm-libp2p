#!/usr/bin/env python3
"""Prepare a pinned distribution and point the public Gradle wrapper at its local URI."""
import argparse
import hashlib
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
    sources = args.source or [primary, "https://downloads.gradle.org/distributions/" + name]
    args.directory.mkdir(parents=True, exist_ok=True)
    archive = args.directory.resolve() / name
    result = subprocess.run(["curl", "-q", "--fail", "--silent", "--show-error", "--location",
                             "--connect-timeout", "10", "--max-time", "10",
                             "--output", str(archive), "--url", sources[0]], check=False)
    if result.returncode:
        return result.returncode
    with archive.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != properties["distributionSha256Sum"]:
        raise ValueError(f"Gradle distribution SHA-256 {actual} does not match its wrapper pin")
    replacement = "distributionUrl=" + archive.as_uri().replace(":", "\\:")
    args.properties.write_text("\n".join(replacement if line.startswith("distributionUrl=") else line
                                           for line in original.splitlines()) + "\n")
    print(f"Gradle wrapper will use the verified distribution at {archive}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

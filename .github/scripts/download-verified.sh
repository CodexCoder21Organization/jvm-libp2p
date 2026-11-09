#!/usr/bin/env bash
# Download an archive and publish it only after checking the caller's SHA-256 pin.
set -euo pipefail
output=$1
expected=$2
shift 2
url=$1
partial=$(mktemp "$output.part.XXXXXX")
trap 'rm -f "$partial"' EXIT
curl -q --fail --silent --show-error --location --connect-timeout 30 --max-time 180 "$url" -o "$partial"
printf '%s  %s\n' "$expected" "$partial" | sha256sum --check
mv "$partial" "$output"

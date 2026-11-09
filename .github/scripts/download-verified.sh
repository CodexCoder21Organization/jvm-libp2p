#!/usr/bin/env bash
# Publish only a complete HTTP download matching the caller's SHA-256 pin.
# Each source gets at most three attempts; permanent responses move on immediately.
set -euo pipefail
max_time=180
if [ "${1:-}" = --max-time ]; then
  max_time=${2:-}
  shift 2
fi
if [ "$#" -lt 3 ]; then
  echo 'Usage: download-verified.sh [--max-time SECONDS] OUTPUT SHA256 URL [URL ...]' >&2
  exit 2
fi
case "$max_time" in
  ''|*[!0-9]*) echo 'Transfer deadline must be an integer from 1 to 180 seconds.' >&2; exit 2 ;;
esac
if [ "$max_time" -lt 1 ] || [ "$max_time" -gt 180 ]; then
  echo 'Transfer deadline must be an integer from 1 to 180 seconds.' >&2
  exit 2
fi
output=$1
expected=$2
shift 2
partial=$(mktemp -- "$output.part.XXXXXX")
trap 'rm -f -- "$partial"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
total=0
for url in "$@"; do
  for attempt in 1 2 3; do
    total=$((total + 1))
    curl_exit=0
    status=$(curl -q --fail --silent --show-error --location \
      --connect-timeout 30 --max-time "$max_time" \
      --header 'Accept: application/octet-stream' \
      --write-out '%{http_code}' --output "$partial" --url "$url") || curl_exit=$?
    case "$status" in
      2[0-9][0-9])
        if [ "$curl_exit" -eq 0 ]; then
          actual=$(sha256sum -- "$partial")
          actual=${actual%% *}
          if [ "$actual" = "$expected" ]; then
            mv -- "$partial" "$output"
            echo "Verified download: $url; HTTP $status; attempt $attempt of 3."
            exit 0
          fi
          echo "Download refused: $url; HTTP $status; attempt $attempt of 3; SHA-256 $actual; expected $expected." >&2
          break
        fi
        ;;
    esac
    echo "Download failed: $url; HTTP ${status:-000}; curl exit $curl_exit; attempt $attempt of 3." >&2
    case "$status" in
      ''|000|429|5[0-9][0-9]) ;;
      *) break ;;
    esac
  done
done
echo "No source supplied an archive matching the pinned SHA-256 after $total attempt(s)." >&2
exit 1

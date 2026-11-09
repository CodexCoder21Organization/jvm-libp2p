#!/usr/bin/env bash
# Publish only a complete HTTP download matching the caller's SHA-256 pin.
# Each source gets at most three attempts; permanent responses move on immediately.
set -euo pipefail
connect_time=30
max_time=180
while [ "$#" -gt 0 ]; do
  case "$1" in
    --connect-timeout|--max-time)
      option=$1
      value=${2:-}
      case "$value" in
        ''|*[!0-9]*) echo "$option requires a positive integer in seconds." >&2; exit 2 ;;
      esac
      if [ "$option" = --connect-timeout ]; then connect_time=$value; else max_time=$value; fi
      shift 2
      ;;
    *) break ;;
  esac
done
if [ "$#" -lt 3 ]; then
  echo 'Usage: download-verified.sh [--connect-timeout SECONDS] [--max-time SECONDS] OUTPUT SHA256 URL [URL ...]' >&2
  exit 2
fi
if [ "$connect_time" -lt 1 ] || [ "$connect_time" -gt 30 ] || [ "$max_time" -lt 1 ] || [ "$max_time" -gt 180 ]; then
  echo 'Connect deadline must be 1..30 seconds and transfer deadline must be 1..180 seconds.' >&2
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
      --connect-timeout "$connect_time" --max-time "$max_time" \
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

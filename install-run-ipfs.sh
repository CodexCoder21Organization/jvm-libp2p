#!/usr/bin/env bash
set -euo pipefail

ci_root="$PWD/build/ci-kubo"
mkdir -p "$ci_root"
archive="$ci_root/kubo.tar.gz"
curl --fail --location --max-time 180 \
  https://github.com/ipfs/kubo/releases/download/v0.34.1/kubo_v0.34.1_linux-amd64.tar.gz \
  --output "$archive"
printf '%s  %s\n' \
  42045802fe60c64fb01350bc071190c534d600fe269759c06e27e22b2012fd3e \
  "$archive" | sha256sum --check

tar -xzf "$archive" -C "$ci_root"
export IPFS_PATH="$ci_root/repo"
ipfs="$ci_root/kubo/ipfs"
"$ipfs" init --profile=test
"$ipfs" config Addresses.API /ip4/127.0.0.1/tcp/5001
"$ipfs" config --json Addresses.Swarm '["/ip4/127.0.0.1/udp/4001/quic-v1"]'
"$ipfs" config --json Bootstrap '[]'
"$ipfs" config --json Discovery.MDNS.Enabled false
"$ipfs" config --json Swarm.DisableNatPortMap true
"$ipfs" daemon --routing=none > "$ci_root/daemon.log" 2>&1 &
daemon_pid=$!
printf '%s\n' "$daemon_pid" > "$ci_root/daemon.pid"

# Wait for this owned daemon's readiness; a failed start keeps its complete log.
deadline=$((SECONDS + 30))
until grep -q 'Daemon is ready' "$ci_root/daemon.log"; do
  if ! kill -0 "$daemon_pid" 2>/dev/null || (( SECONDS >= deadline )); then
    cat "$ci_root/daemon.log" >&2
    kill "$daemon_pid" 2>/dev/null || true
    wait "$daemon_pid" || true
    exit 1
  fi
  sleep 0.1
done

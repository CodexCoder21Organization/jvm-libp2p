#!/usr/bin/env bash
set -euo pipefail
base=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
deadline=$((SECONDS+1500))
slot=
while [[ -z "$slot" ]]; do
  for n in 1 2 3 4 5 6; do
    if mkdir "$base/jvm-slot-$n" 2>/dev/null; then
      echo F93b > "$base/jvm-slot-$n/owner"
      slot=$n
      break
    fi
  done
  if [[ -n "$slot" ]]; then break; fi
  if (( SECONDS >= deadline )); then echo 'PROBLEM: JVM slot deadline reached'; exit 75; fi
  sleep 30
done
trap 'rm -rf "$base/jvm-slot-$slot"' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
"$@"

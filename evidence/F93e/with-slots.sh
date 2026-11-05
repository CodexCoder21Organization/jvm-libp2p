#!/usr/bin/env bash
set -euo pipefail
count=$1; shift
base=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
slots=()
cleanup() { for n in "${slots[@]}"; do if [[ -f "$base/jvm-slot-$n/owner" && $(cat "$base/jvm-slot-$n/owner") == F93e ]]; then rm -rf "$base/jvm-slot-$n"; fi; done; slots=(); }
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
deadline=$((SECONDS+1500))
while :; do
 if (( SECONDS >= deadline )); then echo 'PROBLEM: JVM slot deadline reached'; exit 75; fi
 for n in 1 2 3 4 5 6; do
  if mkdir "$base/jvm-slot-$n" 2>/dev/null; then
   echo F93e > "$base/jvm-slot-$n/owner"; slots+=("$n")
   if (( ${#slots[@]} == count )); then break 2; fi
  fi
 done
 cleanup
 sleep 30
done
echo "SLOTS_ACQUIRED F93e slots=${slots[*]} utc=$(date -u +%FT%TZ)"
"$@"

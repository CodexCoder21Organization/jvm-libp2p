#!/usr/bin/env bash
set -euo pipefail
count=$1
shift
slot_root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
held=()
release() { for slot in "${held[@]}"; do rm -rf "$slot_root/jvm-slot-$slot"; done; held=(); }
trap release EXIT
trap 'exit 143' TERM INT
started=$SECONDS
deadline=$((SECONDS+1500))
last_start_epoch=$(date -u -d '2026-11-14 12:05:00 UTC' +%s)
# Give already-waiting class arms a chance before requesting the next full set.
while ((SECONDS<deadline && $(date -u +%s)<last_start_epoch)); do
  for n in 1 2 3 4 5 6 7; do
    if ((n>5)); then
      anonymous_bytes=$(awk '$1 == "anon" {print $2}' /sys/fs/cgroup/memory.stat)
      if ((anonymous_bytes>=6442450944)); then continue; fi
    fi
    if mkdir "$slot_root/jvm-slot-$n" 2>/dev/null; then
      echo R93A5 > "$slot_root/jvm-slot-$n/owner"
      held+=("$n")
      if ((${#held[@]}==count)); then break; fi
    fi
  done
  if ((${#held[@]}==count)); then break; fi
  release
  sleep 30
done
if ((${#held[@]}!=count)); then echo 'PROBLEM: slot acquisition limit reached'; exit 75; fi
echo "OBSERVED slot wait seconds=$((SECONDS-started)) count=$count"
"$@"

#!/usr/bin/env bash
set -euo pipefail
count=$1
shift
slot_root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
held=()
release() { for slot in "${held[@]}"; do rm -rf "$slot_root/jvm-slot-$slot"; done; }
trap release EXIT
trap 'exit 143' TERM INT
started=$SECONDS
wait_file="$slot_root/F93o-slot-wait-seconds"
previous_wait=0
if [[ -f "$wait_file" ]]; then previous_wait=$(awk '{sum += $1} END {print sum+0}' "$wait_file"); fi
for ((i=0;i<count;i++)); do
  acquired=false
  deadline=$((started+1500))
  while ((SECONDS<deadline && previous_wait+SECONDS-started<3600)); do
    for n in 1 2 3 4 5 6 7; do
      if ((n > 5)); then
        anonymous_bytes=$(awk '$1 == "anon" {print $2}' /sys/fs/cgroup/memory.stat)
        if ((anonymous_bytes >= 6442450944)); then continue; fi
      fi
      if mkdir "$slot_root/jvm-slot-$n" 2>/dev/null; then
        echo F93o > "$slot_root/jvm-slot-$n/owner"
        held+=("$n")
        acquired=true
        break
      fi
    done
    if "$acquired"; then break; fi
    sleep 30
  done
  if ! "$acquired"; then
    echo "$((SECONDS-started))" >> "$wait_file"
    echo 'PROBLEM: slot acquisition limit reached'
    exit 75
  fi
done
echo "$((SECONDS-started))" >> "$wait_file"
echo "OBSERVED slot wait seconds=$((SECONDS-started)) count=$count"
"$@"

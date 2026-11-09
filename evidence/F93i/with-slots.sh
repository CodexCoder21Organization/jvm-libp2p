#!/usr/bin/env bash
set -euo pipefail
count=$1
shift
slot_root=/tmp/claude-501/-code/d3edc83f-d2cb-4273-97b5-de24e1a7faf5/scratchpad/out
held=()
release() { for slot in "${held[@]}"; do rm -rf "$slot_root/jvm-slot-$slot"; done; }
trap release EXIT
trap 'exit 143' TERM INT
started=$SECONDS
for ((i=0;i<count;i++)); do
  acquired=false
  deadline=$((SECONDS+1500))
  while ((SECONDS<deadline && SECONDS-started<3600)); do
    for n in 1 2 3 4 5; do
      if mkdir "$slot_root/jvm-slot-$n" 2>/dev/null; then
        echo F93i > "$slot_root/jvm-slot-$n/owner"
        held+=("$n")
        acquired=true
        break
      fi
    done
    if "$acquired"; then break; fi
    sleep 30
  done
  if ! "$acquired"; then echo 'PROBLEM: slot acquisition limit reached'; exit 75; fi
done
echo "OBSERVED slot wait seconds=$((SECONDS-started)) count=$count"
"$@"

#!/usr/bin/env bash
set -euo pipefail
count=$1; shift
base=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
slots=()
cleanup() { for n in "${slots[@]}"; do if [[ -f "$base/jvm-slot-$n/owner" && $(cat "$base/jvm-slot-$n/owner") == F93g ]]; then rm -rf "$base/jvm-slot-$n"; fi; done; slots=(); }
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
wait_started=$SECONDS
deadline=$((SECONDS+1500))
fixed_deadline_file=$(dirname "$0")/slot-wait-deadline
lane_count() {
 python3 - "$base" <<'COUNT'
from pathlib import Path
import sys
owners=set()
for p in Path(sys.argv[1]).glob('jvm-slot-*/owner'):
 try:
  owner=p.read_text().strip().split()
  if owner: owners.add(owner[0])
 except FileNotFoundError:
  pass
print(len(owners))
COUNT
}
own_slot_count() {
 local owned=0
 for p in "$base"/jvm-slot-*/owner; do
  if [[ -f "$p" && $(cat "$p") == F93g ]]; then owned=$((owned+1)); fi
 done
 echo "$owned"
}
while :; do
 if (( SECONDS >= deadline )) || { [[ -f "$fixed_deadline_file" ]] && (( $(date +%s) >= $(cat "$fixed_deadline_file") )); }; then echo 'PROBLEM: JVM slot deadline reached'; exit 75; fi
 if (( $(lane_count) >= 3 )) && ! ( for p in "$base"/jvm-slot-*/owner; do [[ -f "$p" && $(cat "$p") == F93g ]] && exit 0; done; exit 1; ); then
  sleep 30
  continue
 fi
 if (( $(own_slot_count) + count > 2 )); then
  sleep 30
  continue
 fi
 for n in 1 2 3 4 5 6; do
  if mkdir "$base/jvm-slot-$n" 2>/dev/null; then
   echo F93g > "$base/jvm-slot-$n/owner"; slots+=("$n")
   if (( ${#slots[@]} == count )); then
    if (( $(lane_count) <= 3 && $(own_slot_count) <= 2 )); then break 2; fi
    cleanup
    break
   fi
  fi
 done
 cleanup
 sleep 30
done
echo "SLOTS_ACQUIRED F93g slots=${slots[*]} utc=$(date -u +%FT%TZ) wait_seconds=$((SECONDS-wait_started))"
"$@"

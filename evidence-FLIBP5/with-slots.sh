#!/usr/bin/env bash
set -euo pipefail
count=$1; shift
root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
slots=()
cleanup() {
 for n in "${slots[@]}"; do
  python3 - "$root/jvm-slot-$n" <<'PY'
from pathlib import Path
import shutil,sys
p=Path(sys.argv[1])
if p.exists():
    assert (p/'owner').read_text().strip() == 'FLIBP5', 'Slot owner changed; refusing removal'
    shutil.rmtree(p)
PY
 done
 slots=()
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
limit=$((SECONDS+600))
while :; do
 free -m
 available=$(free -m | awk '/Mem:/{print $7}')
 if ((available>=4000 || SECONDS>=limit)); then break; fi
 sleep 60
done
deadline=$((SECONDS+3600))
while :; do
 for n in 1 2 3 4 5 6 7 8; do
  if mkdir "$root/jvm-slot-$n" 2>/dev/null; then
   echo FLIBP5 > "$root/jvm-slot-$n/owner"
   slots+=("$n")
   if ((${#slots[@]}==count)); then break 2; fi
  fi
 done
 cleanup
 if ((SECONDS>=deadline)); then echo 'PROBLEM: JVM slot deadline';exit 1;fi
 sleep 30
done
"$@"

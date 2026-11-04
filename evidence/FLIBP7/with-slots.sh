#!/usr/bin/env bash
set -euo pipefail
count=$1; shift
root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
slots=()
cleanup() {
 for n in "${slots[@]}"; do
  if [[ -f "$root/jvm-slot-$n/owner" && $(cat "$root/jvm-slot-$n/owner") == FLIBP7 ]]; then rm -rf "$root/jvm-slot-$n"; fi
 done
 slots=()
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
deadline=$((SECONDS+3600))
while :; do
 for n in 1 2 3 4 5 6; do
  if mkdir "$root/jvm-slot-$n" 2>/dev/null; then
   echo FLIBP7 > "$root/jvm-slot-$n/owner"
   slots+=("$n")
   if ((${#slots[@]}==count)); then break 2; fi
  fi
 done
 cleanup
 if ((SECONDS>=deadline)); then echo 'PROBLEM: JVM slot deadline reached'; exit 1; fi
 sleep 30
done
"$@"

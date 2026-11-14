#!/usr/bin/env bash
set -euo pipefail
count=$1
shift
slot_root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
held=()
release() {
    for slot in "${held[@]}"; do rm -rf "$slot_root/jvm-slot-$slot"; done
}
trap release EXIT INT TERM
start=$SECONDS
while ((${#held[@]} < count)); do
    acquired=false
    for slot in 1 2 3 4 5 6 7; do
        if ((slot >= 6)); then
            anon=$(awk '$1 == "anon" {print $2}' /sys/fs/cgroup/memory.stat)
            ((anon < 6442450944)) || continue
        fi
        if mkdir "$slot_root/jvm-slot-$slot" 2>/dev/null; then
            echo P93a > "$slot_root/jvm-slot-$slot/owner"
            held+=("$slot")
            acquired=true
            break
        fi
    done
    if ((${#held[@]} == count)); then break; fi
    if ((SECONDS - start >= 1500 && ${#held[@]} > 0)); then
        release
        held=()
        echo 'PROBLEM: released partial slots after 25 minutes' >&2
        exit 75
    fi
    if ((SECONDS - start >= 3600)); then
        echo 'PROBLEM: JVM slot wait reached 60 minutes' >&2
        exit 75
    fi
    if [[ $acquired == false ]]; then sleep 30; fi
done
echo "OBSERVED slots ${held[*]} wait_seconds $((SECONDS-start))"
"$@"

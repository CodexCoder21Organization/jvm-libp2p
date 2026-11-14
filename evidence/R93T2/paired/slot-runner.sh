#!/usr/bin/env bash
set -euo pipefail
count=$1
log=$2
shift 2
slot_root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
wait_file=/tmp/R93T2-paired/slot-wait-seconds
held=()
child_pid=''
cleanup() {
  if [[ -n "$child_pid" ]] && kill -0 "$child_pid" 2>/dev/null; then
    kill -TERM -- "-$child_pid" 2>/dev/null || kill -TERM "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
  for slot in "${held[@]}"; do rm -rf "$slot_root/jvm-slot-$slot"; done
}
trap cleanup EXIT
trap 'exit 143' TERM INT
started=$SECONDS
prior_wait=0
if [[ -f "$wait_file" ]]; then
  prior_wait=$(awk '{sum += $1} END {print sum+0}' "$wait_file")
fi
for ((i=0; i<count; i++)); do
  acquired=false
  deadline=$((SECONDS+1500))
  while ((SECONDS < deadline && prior_wait + SECONDS - started < 3600)); do
    for n in 1 2 3 4 5 6 7; do
      if ((n > 5)); then
        anon_bytes=$(awk '$1 == "anon" {print $2}' /sys/fs/cgroup/memory.stat)
        if ((anon_bytes >= 6442450944)); then continue; fi
      fi
      if mkdir "$slot_root/jvm-slot-$n" 2>/dev/null; then
        printf '%s\n' R93T2 > "$slot_root/jvm-slot-$n/owner"
        held+=("$n")
        acquired=true
        break
      fi
    done
    if "$acquired"; then break; fi
    sleep 30
  done
  if ! "$acquired"; then
    waited=$((SECONDS-started))
    printf '%s\n' "$waited" >> "$wait_file"
    printf 'PROBLEM: slot acquisition deadline reached; waited_seconds=%s acquired=%s\n' "$waited" "${#held[@]}"
    exit 75
  fi
done
waited=$((SECONDS-started))
printf '%s\n' "$waited" >> "$wait_file"
mkdir -p "$(dirname "$log")"
pid_file="$log.pid"
setsid timeout --signal=TERM --kill-after=15s 25m "$@" > "$log" 2>&1 &
child_pid=$!
printf '%s\n' "$child_pid" > "$pid_file"
if wait "$child_pid"; then
  result=0
else
  result=$?
fi
child_pid=''
rm -f "$pid_file"
printf 'command_exit=%s slot_wait_seconds=%s slots=%s\n' "$result" "$waited" "${held[*]}"
if ((result == 124)); then
  printf 'PROBLEM: command hit its 25-minute deadline\n'
elif ((result == 137)); then
  awk '/^oom_kill / {print "OBSERVED cgroup " $0}' /sys/fs/cgroup/memory.events
fi
exit "$result"

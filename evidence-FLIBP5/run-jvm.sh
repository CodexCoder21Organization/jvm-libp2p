#!/usr/bin/env bash
set -u
root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/out
free -m
deadline=$((SECONDS+600))
while [ "$(free -m | awk '/Mem:/ {print $7}')" -lt 4000 ] && [ "$SECONDS" -lt "$deadline" ]; do sleep 60; done
deadline=$((SECONDS+3600))
slot=''
while [ -z "$slot" ] && [ "$SECONDS" -lt "$deadline" ]; do
  for n in 1 2 3 4 5 6 7 8; do
    if mkdir "$root/jvm-slot-$n" 2>/dev/null; then
      echo FLIBP5 > "$root/jvm-slot-$n/owner"
      slot=$n
      break
    fi
  done
  [ -n "$slot" ] || sleep 30
done
[ -n "$slot" ] || { echo 'PROBLEM: JVM slot deadline reached'; exit 1; }
trap 'rm -rf "$root/jvm-slot-$slot"' EXIT
"$@"
code=$?
if [ "$code" -eq 137 ]; then
  cat /sys/fs/cgroup/memory.events 2>/dev/null
  echo 'PROBLEM: JVM exited 137; review before the one permitted retry'
fi
exit "$code"

#!/bin/bash
set -eu
cd /tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/workspace/JL62b/repo
OUT=/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/out
printf 'acquired %s\n' "$(date -u +%FT%TZ)" > "$OUT/JL62b-full-acquired.txt"
cat /sys/fs/cgroup/memory.current >> "$OUT/JL62b-full-acquired.txt"
git fetch origin
git rebase origin/develop
git rev-parse HEAD origin/develop > "$OUT/JL62b-suite-heads.txt"
if [ "$(git rev-parse HEAD)" != "091b6d17280045ec2a1260ec890d1a06991bb6ac" ]; then
    date -u +%s > "$OUT/JL62b-focus-start.txt"
    focus_exit=0
    timeout 30m ./gradlew --no-daemon :libp2p:cleanTest :libp2p:test --tests 'io.libp2p.mux.PausedChild*Test' > "$OUT/JL62b-focused.log" 2>&1 || focus_exit=$?
    printf '%s\n' "$focus_exit" > "$OUT/JL62b-focused-exit.txt"
    python3 "$OUT/JL62b-audit.py" "$PWD" "$OUT/JL62b-focused.json" "$(cat "$OUT/JL62b-focus-start.txt")"
    git fetch origin
    git rebase origin/develop
fi
date -u +%s > "$OUT/JL62b-full-start.txt"
exec timeout 4h ./gradlew --no-daemon cleanTest test --continue

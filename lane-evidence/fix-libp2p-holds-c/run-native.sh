#!/usr/bin/env bash
set -u
name=$1
shift
cd /code/workspace/fix-libp2p-holds-c/jvm-libp2p || exit 1
git fetch origin develop && git rebase --autostash origin/develop || exit 1
/tmp/claude-1000/-code/84b03628-0ec5-4336-aaa6-4c48b6b32072/scratchpad/tools/boxrun timeout 900 ./gradlew --no-daemon --max-workers=2 "$@" > "../scratch/upstream-$name-console.txt" 2>&1

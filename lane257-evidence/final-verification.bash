#!/bin/bash
set -euo pipefail
cd /tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/workspace/lane257/jvm-libp2p
steer=/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out/r257-steer.md
if test -f "$steer"; then cat "$steer"; fi
git fetch origin
git rebase origin/develop
checks=()
for iteration in {1..10}; do checks+=(":libp2p:lane257Repeat${iteration}"); done
JAVA_OPTS=-Xmx2g timeout 900 ./gradlew --no-daemon --continue --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=512m' \
  -Pkotlin.compiler.execution.strategy=in-process \
  -I /tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out/r257-repeat.init.gradle \
  -I /tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out/r257-format.init.gradle \
  :libp2p:spotlessKotlinCheck "${checks[@]}" :libp2p:lane257Original :libp2p:lane257Neighbors :libp2p:lane257Transport --stacktrace

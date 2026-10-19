#!/usr/bin/env bash
set -u
label=$1
printf 'WAIT %s\n' "$(date -u --iso-8601=seconds)"
exec 9>/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/local-slot.lock
flock 9
printf 'ACQUIRED %s HEAD %s\n' "$(date -u --iso-8601=seconds)" "$(git rev-parse HEAD)"
tasks=()
for ordinal in {1..10}; do printf -v suffix '%02d' "$ordinal"; tasks+=(":libp2p:reviewShutdown$suffix"); done
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx768m -XX:MaxMetaspaceSize=256m' -Pkotlin.compiler.execution.strategy=in-process -I /tmp/rev55both-rate.init.gradle "${tasks[@]}" --continue --stacktrace --info
result=$?
mkdir -p "/tmp/rev55both-evidence/$label"
for ordinal in {1..10}; do
 printf -v suffix '%02d' "$ordinal"
 mkdir -p "/tmp/rev55both-evidence/$label/$suffix"
 cp libp2p/build/test-results/reviewShutdown"$suffix"/*.xml "/tmp/rev55both-evidence/$label/$suffix/" 2>/dev/null || true
done
printf 'EXIT %s %s\n' "$result" "$(date -u --iso-8601=seconds)"
exit "$result"

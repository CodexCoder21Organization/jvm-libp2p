#!/usr/bin/env bash
set -u
printf 'WAIT %s\n' "$(date -u --iso-8601=seconds)"
exec 9>/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/local-slot.lock
flock 9
printf 'ACQUIRED %s\n' "$(date -u --iso-8601=seconds)"
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx768m -XX:MaxMetaspaceSize=256m' -Pkotlin.compiler.execution.strategy=in-process -I /tmp/rev55both-review.init.gradle :libp2p:test --tests 'io.libp2p.security.noise.NoiseHostIdentityPreparationTest' --tests 'io.libp2p.security.noise.NoisePreparedIdentityIsolationTest' --stacktrace
result=$?
mkdir -p /tmp/rev55both-evidence/head-owned
cp libp2p/build/test-results/test/*.xml /tmp/rev55both-evidence/head-owned/ 2>/dev/null || true
printf 'EXIT %s %s\n' "$result" "$(date -u --iso-8601=seconds)"
exit "$result"

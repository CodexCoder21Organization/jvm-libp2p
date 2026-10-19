#!/usr/bin/env bash
set -u
product=libp2p/src/main/kotlin/io/libp2p/security/noise/NoiseXXSecureChannel.kt
trap 'git restore --source=2d30c91160e3da98b900ac6ebc72b9ebfe99515e -- "$product"' EXIT
printf 'WAIT %s\n' "$(date -u --iso-8601=seconds)"
exec 9>/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/local-slot.lock
flock 9
printf 'ACQUIRED %s\n' "$(date -u --iso-8601=seconds)"
git restore --source=ecbc4a354c323f366150165842d6a039dd105128 -- "$product"
git diff --stat
sha256sum "$product"
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx768m -XX:MaxMetaspaceSize=256m' -Pkotlin.compiler.execution.strategy=in-process -I /tmp/rev55both-review.init.gradle :libp2p:test --tests 'io.libp2p.security.noise.NoiseHostIdentityPreparationTest.host startup prepares the Noise identity before any connection' --stacktrace
result=$?
mkdir -p /tmp/rev55both-evidence/product-reversal
cp libp2p/build/test-results/test/*.xml /tmp/rev55both-evidence/product-reversal/ 2>/dev/null || true
printf 'EXIT %s %s\n' "$result" "$(date -u --iso-8601=seconds)"
exit "$result"

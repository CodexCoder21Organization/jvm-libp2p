#!/usr/bin/env bash
set -euo pipefail
root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/workspace/FLIBP6-20261104
cd "$root/jvm-libp2p"
evidence="$root/jvm-libp2p-evidence/evidence/FLIBP6"
git fetch -q origin develop
git rebase origin/develop
# Preserve the exact final fix while putting only the two old production implementations back.
git diff origin/develop -- libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt > "$evidence/rebased-fix.patch"
for file in libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt; do git show "origin/develop:$file" > "$file"; done
restore_fix() { git apply "$evidence/rebased-fix.patch"; }
trap restore_fix EXIT
set +e
./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process :libp2p:test --tests io.libp2p.transport.PlainNettyTransportFailedCloseTest --tests io.libp2p.transport.QuicTransportFailedCloseTest > "$evidence/upstream-red.log" 2>&1
red=$?
set -e
mkdir -p "$evidence/upstream-red"
cp libp2p/build/test-results/test/TEST-*FailedCloseTest.xml "$evidence/upstream-red/"
echo "$red" > "$evidence/upstream-red.exit"
restore_fix
trap - EXIT
if [[ $red == 0 ]]; then echo 'PROBLEM: old product did not fail'; exit 1; fi
git fetch -q origin develop
git rebase origin/develop
./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process :libp2p:test --tests io.libp2p.transport.PlainNettyTransportFailedCloseTest --tests io.libp2p.transport.QuicTransportFailedCloseTest --tests io.libp2p.transport.ConnectionCloseRetryTest --tests io.libp2p.transport.ParentCloseOwnershipTest --tests io.libp2p.transport.PlainNettyTransportConcurrentListenCloseTest --tests io.libp2p.transport.PlainNettyTransportDaemonThreadTest --tests io.libp2p.transport.PlainNettyTransportShutdownTimingTest --tests io.libp2p.etc.types.CompletedNettyFutureConversionTest --tests io.libp2p.etc.types.NettyFutureConversionStateTest --tests io.libp2p.etc.types.PendingNettyFutureConversionTest :libp2p:spotlessCheck :libp2p:jar :libp2p:generatePomFileForMavenJavaPublication -PmavenArtifactId=jvm-libp2p > "$evidence/upstream-green.log" 2>&1
mkdir -p "$evidence/upstream-green"
cp libp2p/build/test-results/test/TEST-*.xml "$evidence/upstream-green/"
git rev-parse HEAD > "$evidence/upstream-tested-head.txt"

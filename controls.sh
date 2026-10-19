#!/bin/bash
set -u
lane=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$lane/evidence"
for rev in fork-before fork; do
  date -u '+WAIT %FT%TZ' >> "$lane/evidence/$rev-control.log"
  (
    flock 9
    date -u '+ACQUIRED %FT%TZ'
    cd "$lane/$rev"
    ./gradlew --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m' -Pkotlin.compiler.execution.strategy=in-process :libp2p:test --tests 'io.libp2p.transport.quic.QuicKuboTestJava.pingKubo' --tests 'io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame'
    rc=$?
    echo "GRADLE_EXIT=$rc"
    mkdir -p "$lane/evidence/$rev-control-native"
    cp libp2p/build/test-results/test/*.xml "$lane/evidence/$rev-control-native/"
  ) 9>/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/local-slot.lock >> "$lane/evidence/$rev-control.log" 2>&1
done

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../../jvm-libp2p"
export JAVA_HOME=/opt/java
export PATH="$JAVA_HOME/bin:$PATH"
evidence=../evidence/evidence/R93T5
"$evidence/with-slots.sh" 3 timeout 1200 ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -I ../evidence/evidence/R93T5/classpath.gradle -Df93h.output=../build-cp -I ../evidence/evidence/R93T5/gradle-control.gradle -Dr93t.xml=../head-xml :libp2p:test --tests io.libp2p.transport.R93A4LateOfferDeliveryTest --tests io.libp2p.transport.R93A3HeldCloseOrderingTest --tests io.libp2p.transport.R93A3ReentrantHeldCloseOrderingTest --tests io.libp2p.transport.F93mQuicDestroyedLoopReleaseTest --tests io.libp2p.transport.F93mPublishedCloseFailureTest --tests io.libp2p.transport.F93oQueuedCloseCompletionTest --tests io.libp2p.transport.F93oCloseCallbackExecutionTest :libp2p:f93hClasspath > "$evidence/head-build.log" 2>&1

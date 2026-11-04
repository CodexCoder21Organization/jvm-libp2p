#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cd "$here/../../../jvm-libp2p"
test "$(git rev-parse HEAD)" = e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe
exec "$here/with-slots.sh" 2 timeout 1800 env JAVA_HOME=/opt/java PATH=/opt/java/bin:$PATH ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -PmavenArtifactId=jvm-libp2p :libp2p:jar :libp2p:spotlessCheck :libp2p:generatePomFileForMavenJavaPublication

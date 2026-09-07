#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 || "$1" != --test || "$2" == . ]]; then
  echo 'Usage: scripts/test.bash --test <test-class-or-method>' >&2
  exit 2
fi
exec ./gradlew --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=384m' -Pkotlin.compiler.execution.strategy=in-process :libp2p:test --tests "$2"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COURSIER_PATH="${COURSIER_PATH:-$(command -v coursier || command -v cs || true)}"

if [[ -z "$COURSIER_PATH" ]]; then
  echo "Coursier is required to compile the investigation helper." >&2
  exit 2
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/v71-artifact-reader.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

RUNTIME_CLASSPATH="${V71_ARTIFACT_FETCHER_RUNTIME_CLASSPATH:-}"
if [[ -z "$RUNTIME_CLASSPATH" ]]; then
  RUNTIME_CLASSPATH="$("$COURSIER_PATH" fetch \
    --repository https://kotlin.directory/ \
    --repository central \
    foundation.url:resolver:0.0.673 \
    foundation.url:protocol:0.0.399 \
  community.kotlin.clocks.simple:community-kotlin-clocks-simple:0.0.3 \
  net.javadeploy.sjvm:libSJVM-jvm:0.0.35 \
  net.javadeploy.sjvm:avianStdlibHelper-jvm:0.0.35 \
  net.javadeploy.sjvm:stdlibHelperCommon-jvm:0.0.35 \
  org.ow2.asm:asm:9.6 \
  org.ow2.asm:asm-commons:9.6 \
  io.libp2p:jvm-libp2p:1.2.2-RELEASE \
  org.jetbrains.kotlin:kotlin-reflect:1.9.22 \
  community.kotlin.rpc:protocol-api:0.0.2 \
  community.kotlin.rpc:protocol-impl:0.0.11 \
  com.google.protobuf:protobuf-java:3.25.1 \
  tech.pegasys:noise-java:22.1.0 \
  org.jetbrains.kotlin:kotlin-stdlib:1.9.22 \
  org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22 \
  org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22 \
  org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0 \
  com.squareup.okio:okio-jvm:3.4.0 \
  org.slf4j:slf4j-api:1.7.36 \
  org.slf4j:slf4j-simple:1.7.36 \
  io.netty:netty-buffer:4.1.101.Final \
  io.netty:netty-codec:4.1.101.Final \
  io.netty:netty-codec-http:4.1.101.Final \
  io.netty:netty-codec-http2:4.1.101.Final \
  io.netty:netty-common:4.1.101.Final \
  io.netty:netty-handler:4.1.101.Final \
  io.netty:netty-resolver:4.1.101.Final \
  io.netty:netty-transport:4.1.101.Final \
  io.netty:netty-transport-classes-epoll:4.1.101.Final \
  io.netty:netty-transport-classes-kqueue:4.1.101.Final \
  io.netty:netty-transport-native-unix-common:4.1.101.Final \
  org.bouncycastle:bcpkix-jdk18on:1.78.1 \
  org.bouncycastle:bcprov-jdk18on:1.78.1 \
  org.bouncycastle:bcutil-jdk18on:1.78.1 \
  com.google.guava:guava:33.2.0-jre \
  com.google.guava:failureaccess:1.0.2 \
    --classpath)"
fi

COMPILER_CLASSPATH="${V71_ARTIFACT_FETCHER_COMPILER_CLASSPATH:-}"
if [[ -z "$COMPILER_CLASSPATH" ]]; then
  COMPILER_CLASSPATH="$("$COURSIER_PATH" fetch \
    --repository https://kotlin.directory/ \
    --repository central \
    org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.22 \
    --classpath)"
fi

java -cp "$COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 \
  -no-stdlib \
  -no-reflect \
  -classpath "$RUNTIME_CLASSPATH" \
  -d "$TEMP_DIR/fetch-existing-buildtest-artifact.jar" \
  "$SCRIPT_DIR/fetch_existing_buildtest_artifact.kt"

java -cp "$TEMP_DIR/fetch-existing-buildtest-artifact.jar:$RUNTIME_CLASSPATH" \
  investigations.v71.Fetch_existing_buildtest_artifactKt "$@"

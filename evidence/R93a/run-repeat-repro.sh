#!/usr/bin/env bash
set -euo pipefail
lane=$(cd "$(dirname "$0")/../../.." && pwd)
e="$lane/r93a-evidence/evidence/R93a"
b="$lane/r93a-build"
"$e/with-slot.sh" timeout 600 /opt/java/bin/java -cp "$(cat "$b/compiler-classpath")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 11 -language-version 1.6 -Xjvm-default=all -classpath "$(cat "$b/classpath")" -d "$b/repeat-tests" "$e/new-tests/"*.kt > "$e/repeat-compile.log" 2>&1
set +e
"$e/with-slot.sh" timeout 600 /opt/java/bin/java -jar /tmp/flibp6-junit-console.jar execute --class-path "$b/repeat-tests:$(cat "$b/classpath")" --select-class io.libp2p.transport.R93aRepeatedQuicShutdownFailureTest --select-class io.libp2p.transport.R93aRepeatedTcpShutdownFailureTest --reports-dir "$e/repeat-red" --fail-if-no-tests > "$e/repeat-red.log" 2>&1
code=$?
set -e
printf '%s\n' "$code" > "$e/repeat-red.exit"
exit "$code"

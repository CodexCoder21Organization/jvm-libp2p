#!/usr/bin/env bash
set -euo pipefail
lane=$(cd "$(dirname "$0")/../../.." && pwd)
e="$lane/r93a-evidence/evidence/R93a"
b="$lane/r93a-build"
"$e/with-slot.sh" timeout 600 /opt/java/bin/java -cp "$(cat "$b/compiler-classpath")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 11 -language-version 1.6 -Xjvm-default=all -classpath "$(cat "$b/classpath")" -d "$b/terminal-diagnostic-tests" "$e/new-tests/"*.kt > "$e/terminal-diagnostic-compile.log" 2>&1
set +e
"$e/with-slot.sh" timeout 600 /opt/java/bin/java -jar /tmp/flibp6-junit-console.jar execute --class-path "$b/terminal-diagnostic-tests:$(cat "$b/classpath")" --select-class io.libp2p.transport.R93aQuicTerminatedLoopTest --reports-dir "$e/terminal-diagnostic" --fail-if-no-tests > "$e/terminal-diagnostic.log" 2>&1
code=$?
set -e
printf '%s\n' "$code" > "$e/terminal-diagnostic.exit"
exit "$code"

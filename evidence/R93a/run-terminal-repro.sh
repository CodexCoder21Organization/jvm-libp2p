#!/usr/bin/env bash
set -euo pipefail
lane=$(cd "$(dirname "$0")/../../.." && pwd)
e="$lane/r93a-evidence/evidence/R93a"
b="$lane/r93a-build"
"$e/with-slot.sh" timeout 600 /opt/java/bin/java -cp "$(cat "$b/compiler-classpath")" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 11 -language-version 1.6 -Xjvm-default=all -classpath "$(cat "$b/classpath")" -d "$b/terminal-tests" "$e/new-tests/"*.kt > "$e/terminal-compile.log" 2>&1
set +e
"$e/with-slot.sh" timeout 600 /opt/java/bin/java -jar /tmp/flibp6-junit-console.jar execute --class-path "$b/terminal-tests:$(cat "$b/classpath")" --select-class io.libp2p.transport.R93aQuicTerminatedLoopTest --reports-dir "$e/terminal-result" --fail-if-no-tests > "$e/terminal-result.log" 2>&1
code=$?
set -e
printf '%s\n' "$code" > "$e/terminal-result.exit"
exit "$code"

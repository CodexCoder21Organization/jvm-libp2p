#!/usr/bin/env bash
set -euo pipefail
evidence=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$1" && pwd)
report=$2; shift 2
cd "$repo"
echo $$ > "$report.shell-pid"
git fetch -q origin main
git rebase origin/main
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dfoundation.url.test.isolated=true" \
 "$evidence/with-slots.sh" 2 java -cp "/tmp/flibp5-launcher:$(cat /tmp/flibp5-classpath)" SingleAttempt --local "$repo" "$report" "$@"

#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
evidence="$root/evidence/FLIBP7"
report=${1:?report name}
args=()
for cls in PlainNettyTransportFailedCloseTest QuicTransportFailedCloseTest PlainNettyTransportFailedCloseSetTest QuicTransportFailedConnectionSetTest ConnectionCloseRetryTest ParentCloseOwnershipTest PlainNettyTransportConcurrentListenCloseTest PlainNettyTransportDaemonThreadTest PlainNettyTransportShutdownTimingTest; do args+=(--select-class "io.libp2p.transport.$cls"); done
for cls in CompletedNettyFutureConversionTest NettyFutureConversionStateTest PendingNettyFutureConversionTest; do args+=(--select-class "io.libp2p.etc.types.$cls"); done
set +e
"$evidence/with-slots.sh" 1 timeout 600 java -jar /tmp/flibp6-junit-console.jar execute --class-path "$(cat /tmp/flibp7-upstream-test-classpath)" "${args[@]}" --reports-dir "$evidence/$report" --fail-if-no-tests > "$evidence/$report.log" 2>&1
status=$?
set -e
printf '%s\n' "$status" > "$evidence/$report.exit"
python3 "$evidence/count-native.py" "$evidence/$report"
exit "$status"

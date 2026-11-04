#!/usr/bin/env bash
set -euo pipefail
root=/tmp/claude-501/-code/eff1f329-f18f-4c39-ad01-4fe1302ac178/scratchpad/workspace/FLIBP6-20261104
cd "$root/jvm-libp2p"
evidence="$root/jvm-libp2p-evidence/evidence/FLIBP6"
slots="$evidence/with-slots.sh"
compile_tests() {
 git fetch -q origin develop
 git rebase origin/develop
 "$slots" 2 timeout 1500 ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process --init-script "$evidence/test-classpath.gradle" :libp2p:flibp6TestClasspath
}
git fetch -q origin develop
git rebase origin/develop
git diff origin/develop -- libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt > "$evidence/rebased-fix.patch"
for file in libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt; do git show "origin/develop:$file" > "$file"; done
restore_fix() { git apply "$evidence/rebased-fix.patch"; }
trap restore_fix EXIT
# The source-only negative control is intentionally dirty; origin/develop is already current.
"$slots" 2 timeout 1500 ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process --init-script "$evidence/test-classpath.gradle" :libp2p:flibp6TestClasspath > "$evidence/upstream-red-compile.log" 2>&1
set +e
"$slots" 1 timeout 600 java -jar /tmp/flibp6-junit-console.jar execute --class-path "$(cat /tmp/flibp6-upstream-test-classpath)" --select-class io.libp2p.transport.PlainNettyTransportFailedCloseTest --select-class io.libp2p.transport.QuicTransportFailedCloseTest --reports-dir "$evidence/upstream-red" --fail-if-no-tests > "$evidence/upstream-red.log" 2>&1
red=$?
set -e
echo "$red" > "$evidence/upstream-red.exit"
python3 - "$evidence/upstream-red" "$evidence/upstream-red-counts.json" <<'PYCOUNT'
from pathlib import Path
import xml.etree.ElementTree as E,json,sys
cases=[case for p in Path(sys.argv[1]).glob('*.xml') for case in E.parse(p).findall('.//testcase')]
failures=[c for c in cases if c.find('failure') is not None]
errors=[c for c in cases if c.find('error') is not None]
skips=[c for c in cases if c.find('skipped') is not None]
x={'tests':len(cases),'passes':len(cases)-len(failures)-len(errors)-len(skips),'failures':len(failures),'errors':len(errors),'skipped':len(skips)}
Path(sys.argv[2]).write_text(json.dumps(x,indent=2)+'\n')
assert x=={'tests':13,'passes':2,'failures':11,'errors':0,'skipped':0},x
assert all('Transport close must terminate the owned' in c.find('failure').get('message','') for c in failures)
PYCOUNT
restore_fix
trap - EXIT
if [[ $red == 0 ]]; then echo 'PROBLEM: old product did not fail'; exit 1; fi
compile_tests > "$evidence/upstream-green-compile.log" 2>&1
args=()
for cls in PlainNettyTransportFailedCloseTest QuicTransportFailedCloseTest ConnectionCloseRetryTest ParentCloseOwnershipTest PlainNettyTransportConcurrentListenCloseTest PlainNettyTransportDaemonThreadTest PlainNettyTransportShutdownTimingTest; do args+=(--select-class "io.libp2p.transport.$cls"); done
for cls in CompletedNettyFutureConversionTest NettyFutureConversionStateTest PendingNettyFutureConversionTest; do args+=(--select-class "io.libp2p.etc.types.$cls"); done
"$slots" 1 timeout 600 java -jar /tmp/flibp6-junit-console.jar execute --class-path "$(cat /tmp/flibp6-upstream-test-classpath)" "${args[@]}" --reports-dir "$evidence/upstream-green" --fail-if-no-tests > "$evidence/upstream-green.log" 2>&1
git fetch -q origin develop
git rebase origin/develop
"$slots" 2 timeout 1200 ./gradlew --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process :libp2p:spotlessCheck :libp2p:jar :libp2p:generatePomFileForMavenJavaPublication -PmavenArtifactId=jvm-libp2p > "$evidence/upstream-package.log" 2>&1
git rev-parse HEAD > "$evidence/upstream-tested-head.txt"

#!/bin/bash
set -u
cd /code/ws/W4/workspace/jvm-libp2p-012450 || exit 2
ping_path=libp2p/src/main/kotlin/io/libp2p/protocol/Ping.kt
circuit_path=libp2p/src/main/java/io/libp2p/protocol/circuit/CircuitHopProtocol.java
cp "$ping_path" /tmp/W4-012450-Ping-final.kt
cp "$circuit_path" /tmp/W4-012450-Circuit-final.java
restore_sources() {
  cp /tmp/W4-012450-Ping-final.kt "$ping_path"
  cp /tmp/W4-012450-Circuit-final.java "$circuit_path"
}
trap restore_sources EXIT
proof_source=$(git rev-parse HEAD)
git fetch origin && git rebase origin/develop || exit 2
test "$(git rev-parse HEAD)" = "$proof_source" || exit 2
git show origin/develop:"$ping_path" > "$ping_path"
python3 - <<'PY'
from pathlib import Path
p=Path('libp2p/src/main/java/io/libp2p/protocol/circuit/CircuitHopProtocol.java');s=p.read_text();needle='ctx.fireExceptionCaught(failure);';assert s.count(needle)==1;p.write_text(s.replace(needle,'// Negative control: omit the paired-close error report.'))
PY
git diff > /tmp/W4-012450-evidence/lifecycle-control/mutation.patch
git rev-parse HEAD > /tmp/W4-012450-evidence/lifecycle-control/source-head.txt
timeout -k 10s 600s flock -n /tmp/W4-review-local-jvm.lock env -u ANDROID_HOME ./gradlew --no-daemon --stacktrace -Pkotlin.compiler.execution.strategy=in-process :libp2p:test --tests io.libp2p.protocol.PingConcurrentLifetimeTest --tests io.libp2p.protocol.circuit.CircuitConcurrentCloseTest > /tmp/W4-012450-evidence/lifecycle-control/native.log 2>&1
phase_exit=$?
echo "$phase_exit" > /tmp/W4-012450-evidence/lifecycle-control/native.exit
cp libp2p/build/test-results/test/TEST-*.xml /tmp/W4-012450-evidence/lifecycle-control/
restore_sources
git fetch origin && git rebase origin/develop || exit 2
test "$(git rev-parse HEAD)" = "$proof_source" || exit 2
python3 - <<'PY'
from pathlib import Path
p=Path('libp2p/src/main/kotlin/io/libp2p/protocol/Ping.kt');s=p.read_text();needle='                handler.onClosed(stream)';assert s.count(needle)==1;p.write_text(s.replace(needle,'                // Negative control: omit failed-registration ownership release.'))
PY
git diff > /tmp/W4-012450-evidence/failed-registration-control/mutation.patch
git rev-parse HEAD > /tmp/W4-012450-evidence/failed-registration-control/source-head.txt
timeout -k 10s 600s flock -n /tmp/W4-review-local-jvm.lock env -u ANDROID_HOME ./gradlew --no-daemon --stacktrace -Pkotlin.compiler.execution.strategy=in-process :libp2p:test --tests io.libp2p.protocol.PingConcurrentLifetimeTest > /tmp/W4-012450-evidence/failed-registration-control/native.log 2>&1
phase_exit=$?
echo "$phase_exit" > /tmp/W4-012450-evidence/failed-registration-control/native.exit
cp libp2p/build/test-results/test/TEST-*.xml /tmp/W4-012450-evidence/failed-registration-control/
restore_sources
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as E
base=Path('/tmp/W4-012450-evidence')
for phase in ['lifecycle-control','failed-registration-control']:
 root=base/phase
 assert (root/'native.exit').read_text().strip()=='1',phase
 for p in root.glob('TEST-*.xml'):
  r=E.parse(p).getroot();print(phase,p.name,r.attrib)
  assert r.get('errors')=='0'
  for c in r.findall('testcase'):
   f=c.find('failure')
   if f is not None:
    print('FAIL',c.get('name'),f.get('type'));assert 'Assertion' in f.get('type','')
 r=E.parse(root/'TEST-io.libp2p.protocol.PingConcurrentLifetimeTest.xml').getroot()
 assert int(r.get('failures','0')) >= (4 if phase=='lifecycle-control' else 2)
 if phase=='failed-registration-control': assert r.get('tests')=='6' and r.get('failures')=='2'
 if phase=='lifecycle-control':
  r=E.parse(root/'TEST-io.libp2p.protocol.circuit.CircuitConcurrentCloseTest.xml').getroot();assert r.get('tests')=='3' and r.get('failures')=='2'
PY

from pathlib import Path
import subprocess,json,time,sys
b=Path(__file__).resolve().parents[3]/'f93f-build';e=Path(__file__).resolve().parent
cp=str(b/'additional-fixtures')+':'+str(b/'baseline-all')+':'+(b/'classpath').read_text()
cmd=[str(e/'with-slots.sh'),'1','timeout','1200','/opt/java/bin/java','-ea','-cp',cp,'io.libp2p.transport.F93fQuicLifecycleSoakProbe']
start=time.monotonic()
with (e/'soak-direct-measurement.log').open('w') as f:
 p=subprocess.Popen(cmd,stdout=f,stderr=subprocess.STDOUT);(e/'soak-direct-measurement.pid').write_text(str(p.pid));code=p.wait();(e/'soak-direct-measurement.pid').unlink()
elapsed=time.monotonic()-start
wait=None
for line in (e/'soak-direct-measurement.log').read_text().splitlines():
 if line.startswith('SLOTS_ACQUIRED '):wait=int(line.split('wait_seconds=')[1]);break
s={'exit':code,'elapsed_seconds':elapsed,'slot_wait_seconds':wait,'approximate_jvm_seconds':elapsed-wait if wait is not None else None,'junit_attempts':0,'cycles':64,'concurrent_callers':16}
(e/'soak-direct-measurement.json').write_text(json.dumps(s,indent=2));print(json.dumps(s));sys.exit(code)

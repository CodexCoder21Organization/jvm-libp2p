from pathlib import Path
import subprocess,time,os,signal,json,shutil,xml.etree.ElementTree as ET
r=Path(__file__).resolve().parent;repo=r.parent/'jvm-libp2p';base=repo/'libp2p/src/test';k=base/'kotlin/io/libp2p/security/noise'
files=[k/'NoiseAlgorithmReadinessTest.kt',k/'NoiseBindingReadinessTest.kt'];original={p:p.read_bytes() for p in files}
fixture=base/'java/com/southernstorm/noise/crypto/ClassLoadIntervalControl.java'
findings=repo.parent.parent.parent/'out/F68g-findings.md'
end=time.monotonic()+600
try:
 subprocess.run(['git','fetch','origin'],cwd=repo,check=True);subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
 fixture.parent.mkdir(parents=True,exist_ok=True);fixture.write_bytes((r/'control-src/com/southernstorm/noise/crypto/ClassLoadIntervalControl.java').read_bytes())
 for p in files:
  s=p.read_text().replace('"-javaagent:$agent",','''*(if (System.getProperty("noise.control.phase") == "missing-agent") emptyArray<String>() else arrayOf("-javaagent:$agent")),
                    "-Dnoise.control.phase=${System.getProperty("noise.control.phase")}",''')
  if p==files[0]:
   s=s.replace('            NoiseClassLoadObserver.begin()','''            val observationControl = System.getProperty("noise.control.phase")
            if (observationControl == "before") com.southernstorm.noise.crypto.ClassLoadIntervalControl.load()
            NoiseClassLoadObserver.begin()''')
   s=s.replace('            operation("CLASS_OBSERVATION_START_DONE")','''            operation("CLASS_OBSERVATION_START_DONE")
            if (observationControl == "inside") com.southernstorm.noise.crypto.ClassLoadIntervalControl.load()''')
   s=s.replace('                operation("CLASS_OBSERVATION_END_DONE")','''                operation("CLASS_OBSERVATION_END_DONE")
                if (observationControl == "after") com.southernstorm.noise.crypto.ClassLoadIntervalControl.load()''')
  p.write_text(s)
 (r/'observation-control.diff').write_bytes(subprocess.check_output(['git','diff'],cwd=repo))
 for phase in ['inside','before','after','missing-agent']:
  d=r/('observation-control-'+phase);d.mkdir()
  subprocess.run(['git','fetch','origin'],cwd=repo,check=True);subprocess.run(['git','rebase','--autostash','origin/develop'],cwd=repo,check=True)
  cmd=['./gradlew','-g',str(r/'gradle-home'),'-I',str(r/'observation-control.init.gradle'),'-Dnoise.control.phase='+phase,':libp2p:test','--tests','*NoiseBindingReadinessTest.sole cold WebSocket host with delegating Noise binding prepares before first stream','--max-workers=2']
  (d/'command.json').write_text(json.dumps(cmd));start=time.time()
  with (d/'console.log').open('w') as f:
   p=subprocess.Popen(cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
   try:rc=p.wait(timeout=min(300,max(1,end-time.monotonic())))
   except subprocess.TimeoutExpired:
    os.killpg(p.pid,signal.SIGTERM)
    try:p.wait(timeout=5)
    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
    rc=124
  xml=repo/'libp2p/build/test-results/test/TEST-io.libp2p.security.noise.NoiseBindingReadinessTest.xml'
  if not xml.exists() or xml.stat().st_mtime<start-2:raise RuntimeError('No fresh XML '+phase)
  (d/'exit.json').write_text(json.dumps({'exit':rc,'start':start}))
  subprocess.run(['python3',str(r/'archive.py'),str(d)],check=True)
  root=ET.parse(xml).getroot();failures=int(root.get('failures'))+int(root.get('errors'))
  if phase in ['inside','missing-agent']:
   if failures!=1:raise RuntimeError('Expected runtime assertion failure in '+phase)
   text='\n'.join((x.text or '') for x in root.findall('.//failure'))
   expected='1 first-dial class-load events: [com.southernstorm.noise.crypto.ClassLoadIntervalControl]' if phase=='inside' else 'Class-load observer did not record Noise preparation before the first stream'
   if expected not in text:raise RuntimeError('Wrong failure in '+phase)
  elif failures or rc:raise RuntimeError('Outside-interval control failed '+phase)
finally:
 for p,b in original.items():p.write_bytes(b)
 fixture.unlink(missing_ok=True)
 with findings.open('a') as f:f.write('\nOBSERVED: Temporary observation control source restored byte-for-byte to HEAD; diagnostic diff retained. No injected class/property/assertion mutation committed.\n')
(r/'observation-controls-done').write_text('done')

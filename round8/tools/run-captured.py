from pathlib import Path
import subprocess,time,json,shutil,os,signal,sys,xml.etree.ElementTree as ET,fcntl
w=Path(__file__).resolve().parent; repo=w/'jvm-libp2p'; ev=w/'round8'; config=ev/'captured-test-config.json'
label,cls,lane=sys.argv[1:4]
if len(sys.argv)>4: config=Path(sys.argv[4]).resolve()
e=ev/label;e.mkdir(exist_ok=False);d=w/('runner-'+lane);(d/'libp2p').mkdir(parents=True,exist_ok=True)
(d/'settings.gradle').write_text("rootProject.name='F71i-"+lane+"'\ninclude 'libp2p'\n")
shutil.copy2(w/'captured-build.gradle',d/'libp2p/build.gradle')
cmd=[str(repo/'gradlew'),'--gradle-user-home',str(w/'gradle-home'),'--offline','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2','-p',str(d),':libp2p:test','--tests',cls,'-PcapturedConfig='+str(config),'--stacktrace']
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
(e/'load-before.txt').write_text(Path('/proc/loadavg').read_text())
(e/'source-diff.patch').write_text(subprocess.check_output(['git','diff'],cwd=repo,text=True))
start=time.time()
with (ev/'command-count.lock').open('w') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 counts=ev/'command-count.json';c=json.loads(counts.read_text()) if counts.exists() else {'active':0,'max':1}
 assert c['active']<2,c
 c['active']+=1;c['max']=max(c['max'],c['active']);counts.write_text(json.dumps(c))
try:
 with (e/'gradle.log').open('w') as log:
  p=subprocess.Popen(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  (e/'process.json').write_text(json.dumps({'pid':p.pid,'command':cmd,'start':start,'head':head},indent=2))
  try:code=p.wait(timeout=600)
  except subprocess.TimeoutExpired:
   os.killpg(p.pid,signal.SIGTERM)
   try:code=p.wait(timeout=15)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);code=p.wait()
finally:
 with (ev/'command-count.lock').open('w') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX);c=json.loads(counts.read_text());c['active']-=1;counts.write_text(json.dumps(c))
r={'command':cmd,'head':head,'exit':code,'wall_ms':round((time.time()-start)*1000),'suites':[], 'tests':[], 'harness':'Captured original Gradle Test settings and freshly compiled source; separate output directory'}
for f in (d/'libp2p/build/test-results/test').glob('TEST-*.xml'):
 if f.stat().st_mtime<start:continue
 t=ET.parse(f).getroot()
 if t.attrib['name']!=cls:continue
 shutil.copy2(f,e/f.name);r['suites'].append(t.attrib)
 failures=[]
 for case in t.findall('testcase'):
  r['tests'].append(case.attrib)
  for a in case.findall('failure')+case.findall('error'):failures.append(case.attrib['name']+'\n'+a.attrib.get('message','')+'\n'+(a.text or ''))
 if failures:(e/'full-failure.txt').write_text('\n\n'.join(failures))
(e/'result.json').write_text(json.dumps(r,indent=2));print(json.dumps(r),flush=True)
sys.exit(1 if code or not r['suites'] else 0)

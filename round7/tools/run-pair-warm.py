from pathlib import Path
import subprocess,time,json,threading,concurrent.futures,xml.etree.ElementTree as ET,shutil,os,signal,sys
w=Path(__file__).resolve().parent;repo=w/'jvm-libp2p';ev=w.parent/'round7';config=ev/'captured-test-config.json'
c=json.loads(config.read_text());assert c['maxParallelForks']==1
assert all(Path(p).exists() for p in c['classes']+c['classpath'])
companion='CompletedCapacityResetNotificationTest'
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip();lock=threading.Lock();running=0;maximum=0
for worker in ['off-loop','companion']:
 d=w/('captured-'+worker);d.mkdir(exist_ok=True);shutil.copy2(w/'captured-build.gradle',d/'build.gradle');(d/'settings.gradle').write_text("rootProject.name='F71h-"+worker+"'\n")
base=[str(repo/'gradlew'),'--gradle-user-home',str(w/'gradle-home'),'--offline','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2']
with (ev/'pair-base-preflight.log').open('w') as log:
 subprocess.run(['git','fetch','origin'],cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True)
 subprocess.run(['git','rebase','origin/develop'],cwd=repo,stdout=log,stderr=subprocess.STDOUT,check=True)
# Warm only Gradle configuration; no test or child-JVM workload is run here.
for worker in ['off-loop','companion']:
 d=w/('captured-'+worker)
 with (ev/('pair-configure-'+worker+'.log')).open('w') as log:
  subprocess.run(base+['--project-dir',str(d),'help','-PcapturedConfig='+str(config)],cwd=d,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=300)
def run(worker):
 global running,maximum
 cls='io.libp2p.transport.'+('CompletedStreamWriteNotificationTest' if worker=='off-loop' else companion)
 label='pair-warm-'+worker;e=ev/label;e.mkdir(exist_ok=False);d=w/('captured-'+worker)
 cmd=base+['--project-dir',str(d),'test','--tests',cls,'-PcapturedConfig='+str(config),'--stacktrace']
 start=time.time()
 with (e/'gradle.log').open('w') as log:
  with lock:
   running+=1;maximum=max(maximum,running);(ev/'concurrency.json').write_text(json.dumps({'max':maximum,'active':running}))
  p=subprocess.Popen(cmd,cwd=d,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);(e/'process.json').write_text(json.dumps({'pid':p.pid,'command':cmd,'start':start,'head':head},indent=2))
  try:code=p.wait(timeout=600)
  except subprocess.TimeoutExpired:
   os.killpg(p.pid,signal.SIGTERM)
   try:code=p.wait(timeout=15)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);code=p.wait()
  with lock:running-=1;(ev/'concurrency.json').write_text(json.dumps({'max':maximum,'active':running}))
 r={'command':cmd,'head':head,'exit':code,'wall_ms':round((time.time()-start)*1000),'suites':[],'harness':'original Gradle Test configuration; same freshly compiled classes and original runtime settings; separate task outputs'}
 for f in (d/'build/test-results/test').glob('TEST-*.xml'):
  if f.stat().st_mtime<start:continue
  t=ET.parse(f).getroot()
  if t.attrib['name']!=cls:continue
  shutil.copy2(f,e/f.name);r['suites'].append(t.attrib)
  failures=[]
  for case in t.findall('testcase'):
   for a in case.findall('failure')+case.findall('error'):failures.append(case.attrib['name']+'\n'+a.attrib.get('message','')+'\n'+(a.text or ''))
  if failures:(e/'full-failure.txt').write_text('\n\n'.join(failures))
 (e/'result.json').write_text(json.dumps(r,indent=2));print(label+' '+json.dumps(r),flush=True)
 return r
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 rs=list(pool.map(run,['off-loop','companion']))
print('MAX_CONCURRENT_COMMANDS_OBSERVED: '+str(maximum),flush=True)
sys.exit(1 if any(r['exit'] or not r['suites'] for r in rs) else 0)

from pathlib import Path
import subprocess,time,json,threading,concurrent.futures,xml.etree.ElementTree as ET,shutil,os,signal
w=Path(__file__).resolve().parent;repo=w/'jvm-libp2p';ev=w/'evidence/F71f';config=ev/'captured-test-config.json'
c=json.loads(config.read_text());assert c['maxParallelForks']==1
assert all(Path(p).exists() for p in c['classes']+c['classpath'])
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip();lock=threading.Lock();running=0;maximum=0
for worker in ['binding','algorithm']:
 d=w/('captured-'+worker);d.mkdir(exist_ok=True);shutil.copy2(w/'captured-build.gradle',d/'build.gradle');(d/'settings.gradle').write_text("rootProject.name='F71f-"+worker+"'\n")
def run(worker,number):
 global running,maximum
 cls='io.libp2p.security.noise.Noise'+('Binding' if worker=='binding' else 'Algorithm')+'ReadinessTest';label='pair-'+str(number)+'-'+worker;e=ev/label;e.mkdir(exist_ok=False);d=w/('captured-'+worker)
 cmd=[str(repo/'gradlew'),'--project-dir',str(d),'--gradle-user-home',str(w/'gradle-home'),'--offline','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2','test','--tests',cls,'-PcapturedConfig='+str(config),'--stacktrace']
 start=time.time()
 with (e/'gradle.log').open('w') as log:
  with lock:
   running+=1;maximum=max(maximum,running);(ev/'concurrency.json').write_text(json.dumps({'max':maximum,'active':running}))
  p=subprocess.Popen(cmd,cwd=d,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);(e/'process.json').write_text(json.dumps({'pid':p.pid,'command':cmd,'start':start,'head':head},indent=2))
  try:code=p.wait(timeout=min(600,max(1,1792888080-time.time())))
  except subprocess.TimeoutExpired:
   os.killpg(p.pid,signal.SIGTERM)
   try:code=p.wait(timeout=15)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);code=p.wait()
  with lock:running-=1;(ev/'concurrency.json').write_text(json.dumps({'max':maximum,'active':running}))
 r={'command':cmd,'head':head,'exit':code,'wall_ms':round((time.time()-start)*1000),'suites':[],'harness':'original Gradle Test configuration; original freshly compiled classes, no output copied'}
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
for number in range(1,4):
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
  rs=list(pool.map(lambda worker:run(worker,number),['binding','algorithm']))
 if any(r['exit'] or not r['suites'] for r in rs):
  print('STOP: failed pair; investigate before any further attempt',flush=True);break
print('MAX_CONCURRENT_COMMANDS_OBSERVED: '+str(maximum),flush=True)

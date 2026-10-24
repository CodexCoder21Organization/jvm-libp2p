from pathlib import Path
import subprocess,time,json,concurrent.futures,threading,xml.etree.ElementTree as ET,shutil,os,signal
w=Path(__file__).resolve().parent;repo=w/'jvm-libp2p';e=repo/'investigation/F71e';config=e/'captured-test-config.json'
c=json.loads(config.read_text());assert c['maxParallelForks']==1
assert all(Path(p).exists() for p in c['classes']+c['classpath'])
classes=[x for x in (e/'register-classes.txt').read_text().splitlines() if not(e/('register-'+x.rsplit('.',1)[-1])/'process.json').exists()]
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip();lock=threading.Lock();queue=iter(classes);running=0;maximum=0
for worker in ['a','b']:
 d=w/('captured-'+worker);d.mkdir(exist_ok=True);shutil.copy2(w/'captured-build.gradle',d/'build.gradle');(d/'settings.gradle').write_text("rootProject.name='F71e-captured-"+worker+"'\n")
def work(worker):
 global running,maximum
 d=w/('captured-'+worker)
 while time.time()<1792881570:
  with lock:
   try:cls=next(queue)
   except StopIteration:return
  label='register-'+cls.rsplit('.',1)[-1];out=e/label
  assert not(out/'process.json').exists();out.mkdir(exist_ok=True)
  assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()==head
  cmd=[str(repo/'gradlew'),'--project-dir',str(d),'--gradle-user-home',str(w/'gradle-home'),'--offline','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2','test','--tests',cls,'-PcapturedConfig='+str(config),'--stacktrace']
  start=time.time()
  with (out/'gradle.log').open('w') as log:
   with lock:running+=1;maximum=max(maximum,running);(e/'captured-concurrency.json').write_text(json.dumps({'maximum':maximum,'running':running}))
   print('START '+cls+' worker='+worker,flush=True)
   p=subprocess.Popen(cmd,cwd=d,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);(out/'process.json').write_text(json.dumps({'pid':p.pid,'command':cmd,'start':start,'captured_project':worker}))
   try:code=p.wait(timeout=min(1500,max(1,1792881600-time.time())))
   except subprocess.TimeoutExpired:
    os.killpg(p.pid,signal.SIGTERM)
    try:code=p.wait(timeout=10)
    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);code=p.wait()
   with lock:running-=1
  r={'command':cmd,'code':code,'wall_ms':round((time.time()-start)*1000),'suites':[],'head':head,'harness':'captured original Gradle Test configuration'}
  for f in (d/'build/test-results/test').glob('TEST-*.xml'):
   if f.stat().st_mtime<start:continue
   t=ET.parse(f).getroot()
   if t.attrib['name']!=cls:continue
   shutil.copy2(f,out/f.name);r['suites'].append(t.attrib)
   failures=[]
   for case in t.findall('testcase'):
    for a in list(case.findall('failure'))+list(case.findall('error')):failures.append(case.attrib['name']+'\n'+a.attrib.get('message','')+'\n'+(a.text or ''))
   if failures:(out/'full-failure.txt').write_text('\n\n'.join(failures))
  (out/'result.json').write_text(json.dumps(r,indent=2));print('END '+cls+' '+json.dumps(r['suites'])+' code='+str(code),flush=True)
  if not r['suites']:
   print('HARNESS_PROBLEM '+cls,flush=True);return
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 results=[pool.submit(work,x) for x in ['a','b']]
 for f in results:f.result()
print('REGISTER_ENDED max='+str(maximum),flush=True)

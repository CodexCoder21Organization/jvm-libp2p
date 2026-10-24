import pathlib,subprocess,time,sys,json,shutil,os,signal,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parent
repo=root/'jvm-libp2p'; home=root/'gradle-home'; label,cls=sys.argv[1:3]
e=root/'evidence/F71f'/label;e.mkdir(parents=True,exist_ok=False)
cmd=['./gradlew','--gradle-user-home',str(home),'--offline','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2','-Pkotlin.daemon.jvmargs=-Xmx3G -XX:ActiveProcessorCount=2 -DF71f.compiler=true','-I',str(root/'capture.gradle'),':libp2p:test','--tests','io.libp2p.security.noise.'+cls,'--stacktrace']
with (e/'base-preflight.log').open('w') as preflight:
 subprocess.run(['git','fetch','origin'],cwd=repo,stdout=preflight,stderr=subprocess.STDOUT,check=True)
 subprocess.run(['git','rebase','origin/develop'],cwd=repo,stdout=preflight,stderr=subprocess.STDOUT,check=True)
start=time.time(); head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
with (e/'gradle.log').open('w') as log:
 p=subprocess.Popen(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 (e/'process.json').write_text(json.dumps({'pid':p.pid,'command':cmd,'start':start,'head':head},indent=2))
 try: code=p.wait(timeout=min(1800,max(1,1792888380-time.time())))
 except subprocess.TimeoutExpired:
  os.killpg(p.pid,signal.SIGTERM)
  try:code=p.wait(timeout=20)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);code=p.wait()
record={'command':cmd,'head':head,'exit':code,'wall_ms':round((time.time()-start)*1000),'suites':[]}
for f in (repo/'libp2p/build/test-results/test').glob('TEST-*.xml'):
 if f.stat().st_mtime < start: continue
 t=ET.parse(f).getroot()
 if t.attrib['name'].split('.')[-1]!=cls:continue
 shutil.copy2(f,e/f.name);record['suites'].append(t.attrib)
 failures=[]
 for case in t.findall('testcase'):
  for failure in case.findall('failure')+case.findall('error'):failures.append(case.attrib['name']+'\n'+failure.attrib.get('message','')+'\n'+(failure.text or ''))
 if failures:(e/'full-failure.txt').write_text('\n\n'.join(failures))
(e/'result.json').write_text(json.dumps(record,indent=2));print(json.dumps(record),flush=True)
sys.exit(1 if code or not record['suites'] else 0)

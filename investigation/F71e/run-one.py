import sys, subprocess, time, pathlib, json, shutil, xml.etree.ElementTree as ET, os
root=pathlib.Path(__file__).resolve().parent
repo=root/'jvm-libp2p'; home=root/'gradle-home'; name,selector=sys.argv[1:3]
e=repo/'investigation/F71e'/name; e.mkdir(parents=True,exist_ok=True)
cmd=['./gradlew','--gradle-user-home',str(home),'--offline','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx3G -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2','-Pkotlin.daemon.jvmargs=-Xmx3G -XX:ActiveProcessorCount=2 -DF71e.compiler=true',':libp2p:test','--tests',selector,'--stacktrace']
(e/'source.patch').write_text(subprocess.check_output(['git','diff','HEAD','--','libp2p/src','README.md'],cwd=repo,text=True))
start=time.time()
with (e/'gradle.log').open('w') as f:
 p=subprocess.Popen(cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
 (e/'process.json').write_text(json.dumps({'pid':p.pid,'command':cmd,'start':start}))
 try: code=p.wait(timeout=1500)
 except subprocess.TimeoutExpired:
  os.killpg(p.pid,15); code=p.wait(timeout=30)
record={'command':cmd,'code':code,'wall_ms':round((time.time()-start)*1000),'suites':[],'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()}
for file in (repo/'libp2p/build/test-results/test').glob('TEST-*.xml'):
 if file.stat().st_mtime < start: continue
 t=ET.parse(file).getroot()
 if selector.rsplit('.',1)[-1] != t.attrib['name'].rsplit('.',1)[-1]: continue
 shutil.copy2(file,e/file.name); record['suites'].append(t.attrib)
 failures=[]
 for case in t.findall('testcase'):
  for failure in list(case.findall('failure'))+list(case.findall('error')): failures.append(case.attrib['name']+'\n'+failure.attrib.get('message','')+'\n'+(failure.text or ''))
 if failures:(e/'full-failure.txt').write_text('\n\n'.join(failures))
(e/'result.json').write_text(json.dumps(record,indent=2));print(json.dumps(record))

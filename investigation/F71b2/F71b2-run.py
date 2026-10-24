import subprocess, time, json, sys, shutil, xml.etree.ElementTree as ET
from pathlib import Path
out=Path(__file__).resolve().parent
repo=out.parent/'ws/F71b2/jvm-libp2p'
label, cls=sys.argv[1:3]
subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
subprocess.run(['git','rebase','--autostash','origin/develop'],cwd=repo,check=True)
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
xml=repo/'libp2p/build/test-results/test'/('TEST-'+cls+'.xml')
xml.unlink(missing_ok=True)
cmd=['timeout','600','./gradlew','--gradle-user-home','/tmp/F71b2-gradle-home','--offline','--max-workers=2',':libp2p:test','--tests',cls,'--rerun-tasks','--stacktrace']
# Do not rebuild unrelated classes just to force test execution.
cmd.remove('--rerun-tasks')
with (out/f'F71b2-{label}.log').open('w') as log:
 start=time.monotonic(); rc=subprocess.run(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT).returncode
record={'head':head,'label':label,'class':cls,'exit':rc,'wall_ms':round((time.monotonic()-start)*1000),'command':cmd}
if xml.exists():
 root=ET.parse(xml).getroot();record.update(root.attrib)
 shutil.copy(xml,out/f'F71b2-{label}.xml')
record['exit']=rc
if xml.exists():
 failures=[]
 for case in ET.parse(xml).getroot().findall('testcase'):
  for failure in list(case.findall('failure'))+list(case.findall('error')):
   failures.append(case.attrib['name']+'\n'+(failure.text or failure.attrib.get('message','')))
 if failures:
  (out/f'F71b2-{label}-failures.txt').write_text('\n\n'.join(failures))
  record['full_failure_record']=f'F71b2-{label}-failures.txt'

(out/f'F71b2-{label}.json').write_text(json.dumps(record,indent=2))
with (out/'F71b2-findings.md').open('a') as f:f.write('\nOBSERVED: Test record '+json.dumps(record)+'\n')
print(json.dumps(record),flush=True)

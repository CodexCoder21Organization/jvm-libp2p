from pathlib import Path
import xml.etree.ElementTree as ET
import json,sys,shutil
name=sys.argv[1]
out=Path('lane-evidence/fix-libp2p-cancel-flake')/name
out.mkdir(parents=True,exist_ok=True)
rows=[]
log=Path('.lane-scratch')/(name+'.log')
started=not log.exists() or any(line in ['> Task :libp2p:test', '> Task :libp2p:test FAILED'] for line in log.read_text().splitlines())
for p in sorted(Path('libp2p/build/test-results/test').glob('TEST-*.xml')):
 if not started: continue
 shutil.copyfile(p,out/p.name)
 root=ET.parse(p).getroot()
 for c in root.findall('testcase'):
  failure=c.find('failure'); error=c.find('error'); skipped=c.find('skipped')
  row={'class':c.get('classname'),'name':c.get('name'),'time':c.get('time'),'result':'FAIL' if failure is not None or error is not None else 'SKIP' if skipped is not None else 'PASS'}
  if failure is not None: row['message']=failure.get('message')
  rows.append(row)
summary={'passed':sum(r['result']=='PASS' for r in rows),'failed':sum(r['result']=='FAIL' for r in rows),'skipped':sum(r['result']=='SKIP' for r in rows),'total':len(rows),'cases':rows}
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
log=Path('.lane-scratch')/(name+'.log')
if log.exists(): shutil.copyfile(log,out/'console.log')
print(json.dumps({k:v for k,v in summary.items() if k!='cases'}))
for row in rows:
 if row['result']=='FAIL': print(json.dumps(row))

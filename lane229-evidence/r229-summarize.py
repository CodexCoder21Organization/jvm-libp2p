import sys, json, shutil, xml.etree.ElementTree as ET
from pathlib import Path
label=sys.argv[1]; repo=Path(sys.argv[2]); tasks=sys.argv[3:]
out=Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out')
summary={}
for task in tasks:
 src=repo/'libp2p/build/test-results'/task; dst=out/f'r229-{label}-{task}-xml'
 if not src.exists(): summary[task]={'missing':True}; continue
 dst.mkdir(exist_ok=True)
 counts={k:0 for k in ('tests','failures','errors','skipped')}; failures=[]; cases=[]
 for p in sorted(src.glob('TEST-*.xml')):
  shutil.copy2(p,dst/p.name)
  r=ET.parse(p).getroot()
  for k in counts: counts[k]+=int(r.get(k,0))
  for c in r.findall('testcase'):
   key={'class':c.get('classname'),'name':c.get('name'),'time':c.get('time')}; cases.append(key)
   fail=c.find('failure')
   if fail is None: fail=c.find('error')
   if fail is not None: failures.append({**key,'message':fail.get('message'),'stack':fail.text})
 counts['passed']=counts['tests']-counts['failures']-counts['errors']-counts['skipped']
 summary[task]={'counts':counts,'failures':failures,'cases':cases}
(out/f'r229-{label}-summary.json').write_text(json.dumps(summary,indent=2))
for task,data in summary.items():
 print(task,data.get('counts','MISSING'))
 for f in data.get('failures',[]): print('FAIL',f['class'],f['name'],f['message'])

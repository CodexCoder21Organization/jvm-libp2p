import subprocess,sys,shutil,json,xml.etree.ElementTree as ET
from pathlib import Path
e=Path('.review/R66b'); label=sys.argv[1]; selectors=sys.argv[2:]; d=e/label; d.mkdir(parents=True,exist_ok=True)
cmd=['./gradlew','--no-daemon','--max-workers=2',':libp2p:test']
for s in selectors: cmd += ['--tests',s]
cmd += ['--rerun']
(d/'command.json').write_text(json.dumps(cmd))
for old in Path('libp2p/build/test-results/test').glob('TEST-*.xml'): old.unlink()
try:
 with (d/'run.log').open('w') as log: r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=900); status=r.returncode
except subprocess.TimeoutExpired: status=124
rows=[]
for p in Path('libp2p/build/test-results/test').glob('TEST-*.xml'):
 shutil.copy(p,d/p.name)
 for t in ET.parse(p).getroot().iter('testcase'):
  rows.append({'class':t.get('classname'),'name':t.get('name'),'red':t.find('failure') is not None or t.find('error') is not None,'skip':t.find('skipped') is not None,'extra':len(t.findall('rerunFailure'))+len(t.findall('flakyFailure'))+len(t.findall('retry'))})
(d/'summary.json').write_text(json.dumps({'status':status,'rows':rows},indent=2))
print(json.dumps({'label':label,'status':status,'cases':len(rows),'pass':sum(not r['red'] and not r['skip'] for r in rows),'red':sum(r['red'] for r in rows),'skip':sum(r['skip'] for r in rows),'extra':sum(r['extra'] for r in rows)}))

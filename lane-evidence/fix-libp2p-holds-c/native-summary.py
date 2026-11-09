from pathlib import Path
import xml.etree.ElementTree as E,json,sys,shutil
folder=Path(sys.argv[1]);folder.mkdir(parents=True,exist_ok=True)
rows=[]
for p in Path('jvm-libp2p/libp2p/build/test-results/test').glob('TEST-*.xml'):
 shutil.copy(p,folder/p.name)
 root=E.parse(p).getroot()
 for t in root.findall('testcase'):
  f=t.find('failure');e=t.find('error');s=t.find('skipped')
  row=dict(name=t.attrib['classname']+'.'+t.attrib['name'],passed=f is None and e is None and s is None,time=t.attrib.get('time'))
  if f is not None:row['failure']=f.attrib.get('message','')
  if e is not None:row['error']=e.attrib.get('message','')
  rows.append(row)
result=dict(firstPassed=sum(t['passed'] for t in rows),firstTotal=len(rows),tests=rows)
if not rows: raise SystemExit('No native test XML: no behavior count is available')
(folder/'summary.json').write_text(json.dumps(result,indent=2));print('FIRST',result['firstPassed'],'/',result['firstTotal'])
for r in rows:
 if not r['passed']:print(r['name'],str(r.get('failure',r.get('error'))).splitlines()[0])

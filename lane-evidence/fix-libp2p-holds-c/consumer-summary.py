from pathlib import Path
import xml.etree.ElementTree as E,json,sys
p=Path(sys.argv[1]);root=E.parse(p).getroot();rows=[]
for t in root.findall('test'):
 if t.get('filtered-out')=='true':continue
 history=next((h for h in root.findall('attempt-history') if h.get('name')==t.get('name') and h.get('file')==t.get('file')),None)
 attempts=[] if history is None else history.findall('attempt')
 first=next((a for a in attempts if a.get('number',a.get('attempt',''))=='1'),None)
 if first is None and attempts:first=attempts[0]
 passed=(t.get('success')=='true') if first is None else (first.get('success')=='true')
 row=dict(name=t.get('name'),passed=passed,attempts=len(attempts) or 1,time=t.get('time'))
 trace=t.find('stacktrace')
 if not passed and trace is not None:row['failure']=(trace.text or '').splitlines()[0]
 rows.append(row)
if not rows:raise SystemExit('No test body rows: no FIRST verdict is available')
result=dict(firstPassed=sum(r['passed'] for r in rows),firstTotal=len(rows),tests=rows)
p.with_suffix('.summary.json').write_text(json.dumps(result,indent=2));print('FIRST',result['firstPassed'],'/',result['firstTotal'])
for r in rows:
 if not r['passed']:print(r['name'],r.get('failure','first attempt failed'))

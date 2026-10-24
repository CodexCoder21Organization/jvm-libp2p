from pathlib import Path
import json,decimal
script=Path(__file__).resolve()
repo=script.parents[2] if script.parent.name=='F71e' and script.parent.parent.name=='investigation' else script.parent/'jvm-libp2p'
ev=repo/'investigation/F71e'
rows=[];missing=[];failed=[]
for cls in (ev/'register-classes.txt').read_text().splitlines():
 path=ev/('register-'+cls.rsplit('.',1)[-1])/'result.json'
 if not path.exists():missing.append(cls);continue
 r=json.loads(path.read_text());suites=[s for s in r['suites'] if s['name']==cls]
 if not suites:missing.append(cls);continue
 s=suites[0];row={'class':cls,'tests':int(s['tests']),'failures':int(s['failures']),'errors':int(s['errors']),'skipped':int(s.get('skipped',0)),'milliseconds':int(decimal.Decimal(s['time'])*1000),'head':r['head'],'command_code':r['code']}
 rows.append(row)
 if row['failures'] or row['errors'] or row['skipped'] or r['code']!=0: failed.append(row)
summary={'classes':len(rows),'required':26,'tests':sum(r['tests'] for r in rows),'passed':sum(r['tests']-r['failures']-r['errors']-r['skipped'] for r in rows),'rows':rows,'missing':missing,'failed':failed}
(ev/'register-summary.json').write_text(json.dumps(summary,indent=2))
table='| Class | Tests | Failures | Errors | Milliseconds |\n|---|---:|---:|---:|---:|\n'+''.join('| '+r['class'].rsplit('.',1)[-1]+' | '+str(r['tests'])+' | '+str(r['failures'])+' | '+str(r['errors'])+' | '+str(r['milliseconds'])+' |\n' for r in rows)
(ev/'register-table.md').write_text(table)
print(json.dumps({k:v for k,v in summary.items() if k!='rows'},indent=2))

from pathlib import Path
import xml.etree.ElementTree as E,json
base=Path(__file__).resolve().parent
summary={}
for rev in ['fork-before','fork']:
 rows=[]
 for p in (base/'evidence'/f'{rev}-control-native').glob('*.xml'):
  r=E.parse(p).getroot()
  for t in r.iter('testcase'):
   failures=list(t.findall('failure'))+list(t.findall('error'))
   rows.append({'class':t.get('classname'),'name':t.get('name'),'seconds':t.get('time'),'attempt':1,'status':'FAILED' if failures else 'SKIPPED' if t.find('skipped') is not None else 'PASSED','failures':[x.text for x in failures]})
 summary[rev]=rows
(base/'evidence/control-attempts.json').write_text(json.dumps(summary,indent=2))
with (base/'evidence/control-audit.txt').open('w') as f:
 for rev,rows in summary.items():
  f.write(rev+'\n')
  for row in rows:
   f.write(f"{row['class']}.{row['name']} attempt={row['attempt']} status={row['status']} duration={row['seconds']}s\n")
   f.write('\n'.join(row['failures'])+'\n')
print('\n'.join(f"{rev}: {r['class']}.{r['name']} attempt={r['attempt']} {r['status']} {r['seconds']}s" for rev,rows in summary.items() for r in rows))

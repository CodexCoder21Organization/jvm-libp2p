import json, sys, xml.etree.ElementTree as E
from pathlib import Path
suites=[]
seen=set()
for filename in sys.argv[1:]:
 root=E.parse(filename).getroot()
 if root.tag!='tests': raise ValueError(f'Unexpected native XML root in {filename}: {root.tag}')
 histories={(h.get('file'),h.get('name')):h for h in root.iter('attempt-history')}
 tests=list(root.iter('test'))
 if not tests: raise ValueError(f'No completed tests in {filename}')
 failures=[]
 records=[]
 for t in tests:
  identity=(Path(t.get('file')).name,t.get('name'))
  if identity in seen: raise ValueError(f'Duplicate scenario across XMLs: {identity}')
  seen.add(identity)
  h=histories.get((t.get('file'),t.get('name')))
  first=next(iter(h),None) if h is not None else t
  if first is None: raise ValueError(f'Empty attempt history: {identity}')
  ok=first.get('success')=='true'
  row={'name':t.get('name'),'file':t.get('file'),'final':t.get('success'),'first':first.attrib,'trace':first.findtext('stacktrace') or first.findtext('error'),'first_pass':ok}
  records.append(row)
  if not ok: failures.append(row)
 suites.append({'xml':str(Path(filename).resolve()),'tests':len(tests),'final_pass':sum(t.get('success')=='true' for t in tests),'final_fail':sum(t.get('success')!='true' for t in tests),'attempt_histories':len(histories),'first_pass':len(tests)-len(failures),'first_fail':len(failures),'failures':failures,'records':records,'build_rules':[b.attrib for b in root.iter('buildrule')]})
print(json.dumps(suites,indent=2))

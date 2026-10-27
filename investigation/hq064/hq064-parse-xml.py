import sys,json,xml.etree.ElementTree as E
from pathlib import Path
rows=[]
for filename in sys.argv[1:]:
 root=E.parse(filename).getroot()
 histories={(h.get('file'),h.get('name')):h for h in root.iter('attempt-history')}
 tests=list(root.iter('test'))
 failures=[]
 for t in tests:
  h=histories.get((t.get('file'),t.get('name')))
  first=next(iter(h),None) if h is not None else t
  ok=first is not None and first.get('success')=='true'
  if not ok:failures.append({'name':t.get('name'),'file':t.get('file'),'final':t.get('success'),'first':first.attrib if first is not None else None,'trace':first.findtext('stacktrace') if first is not None else None})
 rows.append({'xml':str(Path(filename).resolve()),'tests':len(tests),'final_pass':sum(t.get('success')=='true' for t in tests),'final_fail':sum(t.get('success')!='true' for t in tests),'attempt_histories':len(histories),'first_pass':len(tests)-len(failures),'first_fail':len(failures),'failures':failures,'build_rules':[b.attrib for b in root.iter('buildrule')]})
print(json.dumps(rows,indent=2))

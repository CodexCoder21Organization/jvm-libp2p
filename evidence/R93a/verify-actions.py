from pathlib import Path
import json,hashlib,xml.etree.ElementTree as E
raw=Path('/tmp/R93a-actions-raw2'); out=Path(__file__).resolve().parent; rows=[]; hashes={}; retry=[]; files=[]
for p in raw.rglob('*.xml'):
 t=E.parse(p); r=t.getroot()
 if r.tag not in ('testsuite','testsuites'): continue
 rel=str(p.relative_to(raw)); hashes[rel]=hashlib.sha256(p.read_bytes()).hexdigest(); files.append(rel)
 for c in r.iter('testcase'):
  rows.append({'file':rel,'class':c.get('classname'),'name':c.get('name'),'failure':c.find('failure') is not None,'error':c.find('error') is not None,'skip':c.find('skipped') is not None})
  for el in c.iter():
   if any(k in el.tag.lower() for k in ('retry','rerun','flaky')): retry.append({'class':c.get('classname'),'name':c.get('name'),'tag':el.tag})
 for par in r.iter():
  for ch in list(par):
   if ch.tag=='properties': par.remove(ch)
 dest=out/'actions-xml'/rel; dest.parent.mkdir(parents=True,exist_ok=True); t.write(dest,encoding='unicode')
summary={'head':'e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe','run':'https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41914319187','xml_files':len(files),'tests':len(rows),'passes':sum(not (x['failure'] or x['error'] or x['skip']) for x in rows),'failures':sum(x['failure'] for x in rows),'errors':sum(x['error'] for x in rows),'skips':[x for x in rows if x['skip']],'retry_tags':retry}
(out/'actions-summary.json').write_text(json.dumps(summary,indent=2)); (out/'actions-hashes.json').write_text(json.dumps(hashes,indent=2)); (out/'actions-cases.json').write_text(json.dumps(rows,indent=2))

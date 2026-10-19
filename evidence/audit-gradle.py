from pathlib import Path
import xml.etree.ElementTree as ET,json,sys,collections
root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
rows=[]
for p in sorted(root.glob('TEST-*.xml')):
 tree=ET.parse(p).getroot()
 for t in tree.iter('testcase'):
  bad=[e for e in t if e.tag in ('failure','error')]
  rows.append({'class':t.get('classname'),'name':t.get('name'),'seconds':t.get('time'),'status':'FAILED' if bad else 'SKIPPED' if t.find('skipped') is not None else 'PASSED','failure':'\n'.join(e.text or '' for e in bad)})
 (out/p.name).write_bytes(p.read_bytes())
summary={'nativeFiles':len(list(root.glob('TEST-*.xml'))),'attempts':len(rows),'status':dict(collections.Counter(r['status'] for r in rows)),'failing':[r for r in rows if r['status']=='FAILED']}
(out/'attempts.json').write_text(json.dumps(rows,indent=2));(out/'summary.json').write_text(json.dumps(summary,indent=2))
print(json.dumps({k:v for k,v in summary.items() if k!='failing'},indent=2))
for r in summary['failing']:print(r['class']+' '+r['name']+'\n'+r['failure'])

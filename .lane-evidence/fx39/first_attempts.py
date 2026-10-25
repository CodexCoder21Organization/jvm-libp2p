from pathlib import Path
import xml.etree.ElementTree as ET,json,sys,collections
root=Path(sys.argv[1]);out=[]
for f in sorted(root.rglob('native*.xml')):
 r=ET.parse(f).getroot()
 for t in r.findall('test'):
  history=next((h for h in r.findall('attempt-history') if h.get('name')==t.get('name')),None)
  attempts=list(history) if history is not None else [t];first=attempts[0]
  out.append(dict(file=str(f.relative_to(root)),name=t.get('name'),firstPassed=first.get('success')=='true',firstRuntimeMs=int(first.get('time','0')),attemptCount=len(attempts),firstStacktrace=first.findtext('stacktrace','')))
(root/'first-attempts.json').write_text(json.dumps(out,indent=2));counts=collections.defaultdict(lambda:collections.Counter())
for x in out:counts[x['file']]['pass' if x['firstPassed'] else 'fail']+=1
print(json.dumps({k:dict(v) for k,v in counts.items()},indent=2));print('preparation_failures',sum(x['firstRuntimeMs']==0 for x in out))

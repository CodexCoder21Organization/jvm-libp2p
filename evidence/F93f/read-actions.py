from pathlib import Path
import zipfile,xml.etree.ElementTree as E,json,hashlib,sys
archive,name,head,run,artifact=sys.argv[1:];p=Path(__file__).resolve().parent/name;p.mkdir(exist_ok=True)
s={'head':head,'run':'https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/'+run,'artifact_id':int(artifact),'artifact_sha256':hashlib.sha256(Path(archive).read_bytes()).hexdigest(),'attempts':0,'passed':0,'failed':0,'skipped':0,'retry_elements':[],'failures':[],'xml_files':0}
with zipfile.ZipFile(archive) as z:
 for n in z.namelist():
  if not n.endswith('.xml'):continue
  try:t=E.fromstring(z.read(n))
  except E.ParseError:continue
  cases=list(t.iter('testcase'))
  if not cases:continue
  s['xml_files']+=1
  for node in t.iter():
   for c in list(node):
    if c.tag in ['properties','system-out','system-err']:node.remove(c)
  for c in cases:
   s['attempts']+=1
   failure=c.find('failure');error=c.find('error');skip=c.find('skipped')
   if skip is not None:s['skipped']+=1
   elif failure is not None or error is not None:
    s['failed']+=1;f=failure if failure is not None else error
    s['failures'].append({'class':c.get('classname'),'name':c.get('name'),'message':f.get('message')})
   else:s['passed']+=1
  s['retry_elements'] += [x.tag for x in t.iter() if any(y in x.tag.lower() for y in ['retry','rerun','flaky'])]
  (p/(str(s['xml_files'])+'-'+Path(n).name)).write_text(E.tostring(t,encoding='unicode'))
(p/'summary.json').write_text(json.dumps(s,indent=2));print(json.dumps(s,indent=2))

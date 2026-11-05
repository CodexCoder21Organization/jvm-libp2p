from pathlib import Path
import subprocess,sys,time,json,xml.etree.ElementTree as E
root=Path(__file__).resolve().parents[3];e=Path(__file__).resolve().parent;b=root/'f93g-build';repo=root/'jvm-libp2p'
name,slots,repeats,overlays,*selectors=sys.argv[1:];cp=':'.join(str(b/x) for x in overlays.split(','))+':'+(b/'classpath').read_text()+':'+str(b/'gate')
cmd=['/opt/java/bin/java','-ea','-Df93d.measureAll='+str(name.startswith('noise-')).lower(),'-cp',cp,'F93dGate',str(e/name),repeats]+selectors
start=time.time()
with (e/(name+'.log')).open('w') as f:
 p=subprocess.Popen([str(e/'with-slots.sh'),slots,'timeout','1200']+cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT)
 (e/(name+'.pid')).write_text(str(p.pid));code=p.wait();(e/(name+'.pid')).unlink()
summary={'exit':code,'seconds':round(time.time()-start,3),'attempts':0,'passed':0,'failed':0,'skipped':0,'retry_elements':[],'failures':[]}
for xml in (e/name).rglob('*.xml'):
 t=E.parse(xml)
 for node in t.iter():
  for child in list(node):
   if child.tag=='properties':node.remove(child)
 for c in t.iter('testcase'):
  summary['attempts']+=1
  failure=c.find('failure');error=c.find('error');skip=c.find('skipped')
  if skip is not None:summary['skipped']+=1
  elif failure is not None or error is not None:
   summary['failed']+=1;problem=failure if failure is not None else error
   summary['failures'].append({'class':c.get('classname'),'name':c.get('name'),'message':problem.get('message')})
  else:summary['passed']+=1
 summary['retry_elements']+= [x.tag for x in t.iter() if 'retry' in x.tag.lower() or 'rerun' in x.tag.lower() or 'flaky' in x.tag.lower()]
 t.write(xml,encoding='unicode')
(e/(name+'-summary.json')).write_text(json.dumps(summary,indent=2));print(json.dumps({k:v for k,v in summary.items() if k!='failures'}),flush=True)
sys.exit(code)

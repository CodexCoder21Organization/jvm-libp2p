from pathlib import Path
import xml.etree.ElementTree as E
import json,collections,sys,re
label=sys.argv[1]
base=Path('/tmp/rev55both-evidence')
rows=[]; hist=[]
for p in sorted((base/label).glob('*/*.xml')):
 root=E.parse(p)
 ts=list(root.iter('testcase'))
 assert len(ts)==1,(p,len(ts))
 t=ts[0]
 failure=t.find('failure')
 if failure is None:failure=t.find('error')
 state='FAIL' if failure is not None else 'SKIP' if t.find('skipped') is not None else 'PASS'
 assert t.get('classname')=='io.libp2p.core.ParentCloseListenerReviewTest'
 assert t.get('name')=='mplexVisitorClosedChildrenReleaseParentCloseListeners()'
 rows.append({'sample':p.parent.name,'result':state,'seconds':t.get('time'),'timestamp':root.getroot().get('timestamp'),'failure_message':failure.get('message') if failure is not None else None,'stack':failure.text if failure is not None else None})
 hist.extend(str(p) for e in root.iter() if 'attempt-history' in e.tag)
log=(base/(label+'-rate.log')).read_text()
starts=re.findall(r"Successfully started process 'Gradle Test Executor (\d+)'",log)
audit={'label':label,'counts':dict(collections.Counter(r['result'] for r in rows)),'attempts':rows,'test_executor_starts':starts,'attempt_history':hist}
assert len(rows)==10,(label,len(rows))
assert len(starts)==len(set(starts))==10,(label,starts)
assert len(hist)==0,hist
(base/(label+'-audit.json')).write_text(json.dumps(audit,indent=2)+'\n')
print(json.dumps({k:v for k,v in audit.items() if k!='attempts'},indent=2))

import pathlib, xml.etree.ElementTree as ET, json, sys, datetime
root=pathlib.Path(sys.argv[1]); output=pathlib.Path(sys.argv[2]); since=float(sys.argv[3])
cases=[]; reports=[]; stale=[]
for p in root.glob('**/build/test-results/test/TEST-*.xml'):
 if p.stat().st_mtime < since:
  stale.append(str(p.relative_to(root))); continue
 r=ET.parse(p).getroot()
 reports.append(str(p.relative_to(root)))
 for t in r.findall('testcase'):
  bad=t.find('failure'); bad=bad if bad is not None else t.find('error')
  cases.append({'class':t.get('classname'),'name':t.get('name'),'seconds':t.get('time'),'status':'failed' if bad is not None else 'skipped' if t.find('skipped') is not None else 'passed','failure_type':bad.get('type') if bad is not None else None,'message':bad.get('message') if bad is not None else None,'stack':bad.text if bad is not None else None})
summary={'head':__import__('subprocess').check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'since':since,'reports':reports,'stale':stale,'counts':{k:sum(t['status']==k for t in cases) for k in ['passed','failed','skipped']},'total':len(cases),'failures':[t for t in cases if t['status']=='failed']}
output.write_text(json.dumps(summary,indent=2));print(json.dumps({k:summary[k] for k in ['head','counts','total','stale']}));print('\n'.join(t['class']+' :: '+t['name']+' :: '+str(t['message']) for t in summary['failures']))

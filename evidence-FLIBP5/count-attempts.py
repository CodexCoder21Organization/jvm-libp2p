import glob,json,sys,xml.etree.ElementTree as E
paths=[]
for arg in sys.argv[1:]: paths.extend(glob.glob(arg))
entries=[]
for path in sorted(paths):
 root=E.parse(path).getroot()
 histories={h.get('name'):h for h in root.findall('attempt-history')}
 for t in root.findall('test'):
  history=histories.get(t.get('name'))
  attempts=[{'number':int(a.get('number')),'success':a.get('success')=='true','duration_ms':int(a.get('time','0'))} for a in history.findall('attempt')] if history is not None else [{'number':1,'success':t.get('success')=='true','duration_ms':int(t.get('time','0'))}]
  attempts.sort(key=lambda a:a['number'])
  assert attempts and attempts[0]['number']==1
  entries.append({'file':path,'test':t.get('name'),'success':t.get('success')=='true','duration_ms':int(t.get('time','0')),'attempts':attempts})
print(json.dumps({'reports':len(paths),'test_results':len(entries),'passed':sum(x['success'] for x in entries),'failed':sum(not x['success'] for x in entries),'first_attempt_passed':sum(x['attempts'][0]['success'] for x in entries),'total_attempts':sum(len(x['attempts']) for x in entries),'retries':[x['test'] for x in entries if len(x['attempts'])>1],'results':entries},indent=2))

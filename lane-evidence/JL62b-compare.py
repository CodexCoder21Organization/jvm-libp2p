import pathlib,subprocess,json,xml.etree.ElementTree as ET,time,datetime,re
out=pathlib.Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/out')
lane=out.parent/'workspace/JL62b'; results=out/'JL62b-comparison'
failures=json.loads((out/'JL62b-full-summary.json').read_text())['failures']
heads={branch:subprocess.check_output(['git','rev-parse','HEAD'],cwd=lane/('repo' if branch=='pr' else branch),text=True).strip() for branch in ['develop','pr']}
expected={'develop':'bd7edb2570fb609c20245305c25af29d166a67c6','pr':'091b6d17280045ec2a1260ec890d1a06991bb6ac'}
assert heads==expected,(heads,expected)
order=[8]+[i for i in range(len(failures)) if i!=8]
records=[]
for i in order:
 case=failures[i]
 m=re.match(r'\[(\d+)\]',case['name'])
 index=int(m.group(1)) if m else 0
 method='secureInterconnect' if index else case['name'].removesuffix('()')
 row={'index':i,'class':case['class'],'name':case['name'],'branches':{}}
 for branch in ['develop','pr']:
  xml=results/f'{branch}-{i:02d}.xml'; log=results/f'{branch}-{i:02d}.log'
  cp=str(out/'JL62b-runner')+':'+(out/f'JL62b-{branch}-classpath.txt').read_text()
  command=['java','-ea','-cp',cp,'JL62bOneTest',case['class'],method,str(index),str(xml)]
  reused=branch=='pr' and i==8 and xml.exists()
  started=time.monotonic()
  if not reused:
   with log.open('w') as f:
    p=subprocess.run(command,cwd=lane/('repo' if branch=='pr' else branch)/'libp2p',stdout=f,stderr=subprocess.STDOUT,timeout=300)
   assert p.returncode in [0,1],(branch,i,p.returncode,log)
  root=ET.parse(xml).getroot()
  assert root.get('exactlyOne')=='true' and root.get('tests')=='1' and root.get('started')=='1' and root.get('skipped')=='0' and root.get('aborted')=='0',(branch,i,root.attrib)
  testcase=root.find('testcase'); bad=testcase.find('failure')
  stack=bad.text if bad is not None else ''
  result={'head':heads[branch],'firstAttempt':True,'tests':1,'passed':bad is None,'selector':root.get('selector'),'failure_type':bad.get('type') if bad is not None else None,'message':(bad.get('message') or '').splitlines()[0] if bad is not None else None,'causes':[l.strip() for l in stack.splitlines() if l.startswith('Caused by:')],'seconds':float(testcase.get('time')),'existing_first_attempt_report':reused}
  row['branches'][branch]=result
  print(datetime.datetime.now(datetime.timezone.utc).isoformat(),i,branch,'PASS' if result['passed'] else 'FAIL',case['class'],case['name'],result['message'],flush=True)
 records.append(row)
 (out/'JL62b-comparison-progress.json').write_text(json.dumps(records,indent=2))
 with (out/'JL62b.md').open('a') as f:f.write('\n'+datetime.datetime.now(datetime.timezone.utc).isoformat()+' OBSERVED: comparison '+str(i)+' '+case['class']+' :: '+case['name']+' develop='+('PASS' if row['branches']['develop']['passed'] else 'FAIL')+' PR='+('PASS' if row['branches']['pr']['passed'] else 'FAIL')+'; exactly one test discovered/started per process, zero skips/aborts, first attempt.\n')
records.sort(key=lambda x:x['index'])
(out/'JL62b-comparison-summary.json').write_text(json.dumps({'heads':heads,'cases':records,'counts':{'cases':len(records),'pre_existing':sum(not r['branches']['develop']['passed'] and not r['branches']['pr']['passed'] for r in records),'pr_only':sum(r['branches']['develop']['passed'] and not r['branches']['pr']['passed'] for r in records),'passes_alone_both':sum(all(x['passed'] for x in r['branches'].values()) for r in records)}},indent=2))
print('Comparison complete',flush=True)

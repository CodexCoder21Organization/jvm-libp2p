import pathlib,subprocess,json,xml.etree.ElementTree as ET,socket,datetime
out=pathlib.Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/out')
lane=out.parent/'workspace/JL62b'; results=out/'JL62b-port-controls';results.mkdir(exist_ok=True)
failures=json.loads((out/'JL62b-full-counts.json').read_text())['failures']
records=[]
for i in range(8):
 case=failures[i]; method=case['name'].removesuffix('()')
 row={'index':i,'class':case['class'],'name':case['name'],'condition':'Real loopback TCP listener already bound at 127.0.0.1:40002','branches':{}}
 for branch in ['develop','pr']:
  cp=str(out/'JL62b-runner')+':'+(out/f'JL62b-{branch}-classpath.txt').read_text()
  xml=results/f'{branch}-{i:02d}.xml';log=results/f'{branch}-{i:02d}.log'
  command=['java','-ea','-cp',cp,'JL62bOneTest',case['class'],method,'0',str(xml)]
  with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as guard:
   guard.bind(('127.0.0.1',40002));guard.listen(1)
   with log.open('w') as f:p=subprocess.run(command,cwd=lane/('repo' if branch=='pr' else branch)/'libp2p',stdout=f,stderr=subprocess.STDOUT,timeout=300)
  assert p.returncode==1,(i,branch,p.returncode,log)
  root=ET.parse(xml).getroot()
  assert root.get('exactlyOne')=='true' and root.get('tests')=='1' and root.get('started')=='1' and root.get('skipped')=='0' and root.get('aborted')=='0',(i,branch,root.attrib)
  bad=root.find('testcase/failure');assert bad is not None
  causes=[l.strip() for l in bad.text.splitlines() if l.startswith('Caused by:')]
  assert 'Caused by: java.net.BindException: Address already in use' in causes,(i,branch,causes)
  row['branches'][branch]={'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=lane/('repo' if branch=='pr' else branch),text=True).strip(),'tests':1,'firstAttempt':True,'skipped':0,'aborted':0,'message':bad.get('message'),'causes':causes,'seconds':float(root.find('testcase').get('time'))}
  print(datetime.datetime.now(datetime.timezone.utc).isoformat(),i,branch,'FAIL with exact BindException',flush=True)
 records.append(row)
 (out/'JL62b-port-control-progress.json').write_text(json.dumps(records,indent=2))
 with (out/'JL62b.md').open('a') as f:f.write('\n'+datetime.datetime.now(datetime.timezone.utc).isoformat()+' OBSERVED: real occupied-port control '+str(i)+' '+case['class']+' :: '+case['name']+' failed with java.net.BindException: Address already in use on develop and PR, exactly1testperprocess, no skips/aborts/retries. Guard socket closed after each selected test.\n')
(out/'JL62b-port-control-summary.json').write_text(json.dumps({'cases':records,'test_executions':len(records)*2,'all_match_full_suite_bind_failure':True},indent=2))

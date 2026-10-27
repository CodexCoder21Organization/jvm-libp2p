import datetime,json,os,pathlib,signal,subprocess,time,xml.etree.ElementTree as E
repo=pathlib.Path('/home/u/lanes/hd27/ws/hq023/workspace/UrlProtocol')
out=pathlib.Path('/home/u/lanes/hd27/out')
def snapshot(root):
 allp={}
 for p in pathlib.Path('/proc').iterdir():
  if not p.name.isdigit(): continue
  try:
   s=(p/'stat').read_text(); tail=s[s.rfind(')')+2:].split(); allp[int(p.name)]=(int(tail[1]),p,tail)
  except (OSError,ValueError):pass
 keep={root}
 while True:
  new=keep|{pid for pid,(ppid,_,_) in allp.items() if ppid in keep}
  if new==keep:break
  keep=new
 rows=[]
 for pid in keep:
  if pid not in allp:continue
  _,p,s=allp[pid]
  try:
   cmd=(p/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
   if 'java' not in cmd:continue
   status=dict(line.split(':',1) for line in (p/'status').read_text().splitlines() if ':' in line)
   rows.append({'pid':pid,'state':s[0],'minorFaults':int(s[7]),'majorFaults':int(s[9]),'cpuUserTicks':int(s[11]),'cpuSystemTicks':int(s[12]),'scheduler':(p/'schedstat').read_text().strip(),'rss':status.get('VmRSS','').strip(),'swap':status.get('VmSwap','').strip(),'command':cmd[:250]})
  except OSError:pass
 return rows
start=time.monotonic();deadline=start+1500
for i in range(1,11):
 if time.monotonic()>=deadline:raise SystemExit('PROBLEM: campaign deadline before next command')
 xml=out/f'hq054-stress-startup-{i}.xml'
 with (out/f'hq054-stress-startup-{i}.log').open('w') as log,(out/f'hq054-stress-startup-{i}-processes.jsonl').open('w') as stats:
  env=dict(os.environ);env['JAVA_TOOL_OPTIONS']='-Dio.netty.eventLoopThreads=1'
  p=subprocess.Popen(['scripts/test.bash','--local','--test','tests/stressTestRejectedDrainConcurrentHostStops.kts','--log',str(xml)],cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  try:
   commandDeadline=min(deadline,time.monotonic()+600)
   while p.poll() is None:
    stats.write(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'processes':snapshot(p.pid)})+'\n');stats.flush()
    if time.monotonic()>=commandDeadline:raise TimeoutError('PROBLEM: bounded stress command did not finish')
    time.sleep(2)
  finally:
   if p.poll() is None:
    os.killpg(p.pid,signal.SIGTERM)
    try:p.wait(timeout=10)
    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
  if p.returncode!=0:raise SystemExit(f'PROBLEM: command{i} exit{p.returncode}')
  tests=E.parse(xml).getroot().findall('test')
  if len(tests)!=1 or tests[0].get('success')!='true' or list(tests[0]):
   raise SystemExit(f'PROBLEM: command{i} not a single first-attempt pass; inspect XML')
  print('PASS',i,tests[0].attrib,flush=True)
print('CAMPAIGN_DONE ten first-attempt passes',flush=True)

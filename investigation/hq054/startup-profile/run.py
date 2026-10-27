import concurrent.futures,datetime,hashlib,json,os,pathlib,signal,subprocess,time
base=pathlib.Path('/home/u/lanes/hd27/ws/hq054/startup-probe')
root=pathlib.Path(__file__).parent
inputs=json.loads((base/'inputs.json').read_text())
def launch(item):
 mode,index=item
 command=['java','-Xmx128m','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-XX:TieredStopAtLevel=1','-Xlog:class+init=info:file='+str(root/f'{mode}-{index}-classes.log')+':uptime,pid']
 if mode=='agent':command+=['-javaagent:'+str(base/'agent.jar')+'='+inputs['native']]
 command+=['-cp',str(base),'StartupProbe']
 begin=time.monotonic();rows=[];dumped=False
 with (root/f'{mode}-{index}.log').open('w') as output:
  process=subprocess.Popen(command,stdout=output,stderr=subprocess.STDOUT)
  try:
   while process.poll() is None:
    elapsed=time.monotonic()-begin
    if elapsed>=50 and not dumped:process.send_signal(signal.SIGQUIT);dumped=True
    if elapsed>=60:process.kill();process.wait(timeout=5);return {'mode':mode,'index':index,'exit':124,'seconds':time.monotonic()-begin,'telemetry':rows}
    path=pathlib.Path('/proc')/str(process.pid)
    try:
     raw=(path/'stat').read_text();s=raw[raw.rfind(')')+2:].split()
     rows.append({'elapsed':elapsed,'state':s[0],'cpuTicks':int(s[11])+int(s[12]),'majorFaults':int(s[9]),'minorFaults':int(s[7]),'scheduler':(path/'schedstat').read_text().strip()})
    except OSError:pass
    time.sleep(.25)
   return {'mode':mode,'index':index,'exit':process.returncode,'seconds':time.monotonic()-begin,'telemetry':rows}
  finally:
   if process.poll() is None:process.kill();process.wait(timeout=5)
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
 results=[]
 for result in executor.map(launch,[(mode,index) for index in range(32) for mode in ['agent','jdk']]):
  results.append(result);print(json.dumps(result),flush=True)
(root/'results.json').write_text(json.dumps(results,indent=2))
print('PROFILE_DONE',[(m,sum(r['exit']!=0 for r in results if r['mode']==m)) for m in ['agent','jdk']],flush=True)

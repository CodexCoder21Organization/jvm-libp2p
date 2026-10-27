import concurrent.futures,json,pathlib,subprocess,time,hashlib
root=pathlib.Path(__file__).parent
inputs=json.loads((root/'inputs.json').read_text())
for f in ['agent.jar',inputs['manager'],inputs['native']]:
 p=pathlib.Path(f); p=p if p.is_absolute() else root/p
 print('INPUT',p,hashlib.sha256(p.read_bytes()).hexdigest(),flush=True)
def run(item):
 mode,index=item
 cmd=['java','-Xmx128m','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-XX:TieredStopAtLevel=1']
 if mode=='agent': cmd+=['-javaagent:'+str(root/'agent.jar')+'='+inputs['native']]
 cmd+=['-cp',str(root),'StartupProbe']
 start=time.monotonic()
 try:
  p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=60)
  out=p.stdout; code=p.returncode
 except subprocess.TimeoutExpired as e:
  out=str(e.stdout); code=124
 duration=time.monotonic()-start
 (root/(mode+'-'+str(index)+'.log')).write_text(out)
 return {'mode':mode,'index':index,'code':code,'seconds':duration,'output':out}
items=[(mode,index) for index in range(32) for mode in ['agent','jdk']]
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
 results=[]
 for row in executor.map(run,items):
  results.append(row);print(json.dumps(row),flush=True)
(root/'results.json').write_text(json.dumps(results,indent=2))
assert all(r['code']==0 for r in results), 'At least one child failed; inspect captured output.'

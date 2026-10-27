import hashlib, json, os, signal, subprocess, sys, time
from pathlib import Path
repo=Path('/home/u/lanes/hd27/ws/hq023/workspace/UrlProtocol')
out=Path('/home/u/lanes/hd27/out')
mode,label=sys.argv[1:]
if mode not in ('queue','stress'): raise ValueError(mode)
subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
subprocess.run(['git','rebase','origin/main'],cwd=repo,check=True)
filename='testHostStopObservesCompletedCloseWithFullEventLoop' if mode=='queue' else 'stressTestRejectedDrainConcurrentHostStops'
path=repo/'tests'/f'{filename}.kts'
original=path.read_text()
if mode=='queue':
 before='System.setProperty("io.netty.eventLoop.maxPendingTasks", "1024")'
 after='System.setProperty("io.netty.eventLoop.maxPendingTasks", "8192")'
else:
 before='assertTrue(releaseLoop.await(10, TimeUnit.SECONDS), "The test must release the root loop")'
 after=before+'\n            check(false) { "hq071 controlled held-loop callback failure" }'
assert original.count(before)==1
control=original.replace(before,after)
(out/f'{label}-source.kts.txt').write_text(control)
meta={'mode':mode,'label':label,'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'originalSha256':hashlib.sha256(original.encode()).hexdigest(),'controlSha256':hashlib.sha256(control.encode()).hexdigest(),'startedAt':time.time(),'command':['scripts/test.bash','--local','--test',f'tests/{filename}.kts','--log',str(out/f'{label}.xml')]}
child=None
signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
try:
 path.write_text(control)
 with (out/f'{label}.log').open('w') as log:
  child=subprocess.Popen(meta['command'],cwd=repo,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
  meta['pid']=child.pid
  (out/f'{label}-meta.json').write_text(json.dumps(meta,indent=2))
  try: meta['exit']=child.wait(timeout=900)
  except subprocess.TimeoutExpired:
   meta['outerLimitReached']=True
   os.killpg(child.pid,signal.SIGTERM)
   try: child.wait(timeout=10)
   except subprocess.TimeoutExpired: os.killpg(child.pid,signal.SIGKILL);child.wait()
   meta['exit']=124
finally:
 if child is not None and child.poll() is None:
  os.killpg(child.pid,signal.SIGTERM)
  try: child.wait(timeout=10)
  except subprocess.TimeoutExpired: os.killpg(child.pid,signal.SIGKILL);child.wait()
 path.write_text(original)
 meta['endedAt']=time.time()
 meta['restoredSha256']=hashlib.sha256(path.read_bytes()).hexdigest()
 (out/f'{label}-meta.json').write_text(json.dumps(meta,indent=2))
print(json.dumps(meta,indent=2))
sys.exit(meta.get('exit',1))

from pathlib import Path
from concurrent.futures import ThreadPoolExecutor,as_completed
import subprocess,json,datetime
out=Path(__file__).parent
cp=(out/'FIX67-develop-classpath.txt').read_text()
def one(i):
 label=f'FIX67-develop-noise-concurrent-{i}'
 before=int(Path('/sys/fs/cgroup/memory.current').read_text())
 with (out/(label+'.log')).open('w') as log:
  r=subprocess.run(['timeout','--kill-after=5s','30s','java','-cp',cp,'io.libp2p.security.noise.NoiseHandshakePathHarness','saturated'],stdout=log,stderr=subprocess.STDOUT,timeout=40)
 return {'iteration':i,'exit':r.returncode,'memory_before':before,'utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
rows=[]
with ThreadPoolExecutor(max_workers=2) as workers:
 for batch in range(10):
  futures=[workers.submit(one,i) for i in (batch*2+1,batch*2+2)]
  for f in as_completed(futures):
   row=f.result();rows.append(row); print(row,flush=True)
   (out/'FIX67-develop-noise-concurrent-summary.json').write_text(json.dumps(rows,indent=2)+'\n')
  if any(r['exit']==137 for r in rows):
   print('PROBLEM: killed child; stopping without retry',flush=True);break
print('baseline concurrent failures',sum(r['exit']!=0 for r in rows),'of',len(rows),flush=True)

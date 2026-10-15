import subprocess, os, signal, sys, time, json
from pathlib import Path
label=sys.argv[1]; bound=int(sys.argv[2]); args=sys.argv[3:]
out=Path('/tmp/claude-1000/-code/bc9e914b-1f43-4800-9ffc-3e16c08ce74e/scratchpad/out')
started=time.time()
def descendants(root):
 rows=[x.split(None,3) for x in subprocess.check_output(['ps','-eo','pid,ppid,pgid,comm'],text=True).splitlines()[1:]]
 selected={root}
 for _ in range(10):
  for row in rows:
   if int(row[1]) in selected: selected.add(int(row[0]))
 return {int(row[2]) for row in rows if int(row[0]) in selected}

with (out/f'r229-{label}.log').open('w') as log:
 p=subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT,start_new_session=True,env={**os.environ,'JAVA_OPTS':'-Xmx2g'})
 (out/f'r229-{label}-pid.json').write_text(json.dumps({'pid':p.pid,'args':args}))
 groups={p.pid}
 try:
  while True:
   groups.update(descendants(p.pid))
   try: code=p.wait(timeout=min(1,max(.01,started+bound-time.time()))); break
   except subprocess.TimeoutExpired:
    if time.time() >= started+bound: code=124; break
 finally:
  groups.update(descendants(p.pid))
  (out/f'r229-{label}-groups.json').write_text(json.dumps(sorted(groups)))
  for sig in [signal.SIGTERM,signal.SIGKILL]:
   for group in groups:
    try: os.killpg(group,sig)
    except ProcessLookupError: pass
   if sig==signal.SIGTERM:
    try: p.wait(timeout=3)
    except subprocess.TimeoutExpired: pass
  p.wait()

result={'label':label,'exit':code,'seconds':time.time()-started,'command':args}
(out/f'r229-{label}-result.json').write_text(json.dumps(result,indent=2)); print(json.dumps(result)); sys.exit(code)

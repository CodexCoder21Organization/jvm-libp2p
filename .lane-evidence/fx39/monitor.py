import subprocess,json,time,sys
from pathlib import Path
runs=[40163573415]
last={}; changed={}; started=time.monotonic(); failures=[]
# Respect the previous watcher and initial discovery reads before the first combined probe.
time.sleep(180)
while runs:
 if time.monotonic()-started>45*60: print('PROBLEM: 45-minute watch bound reached',flush=True);sys.exit(5)
 for run in runs[:]:
  try:
   p=subprocess.run(['gh','api',f'repos/CodexCoder21Organization/UrlResolver/actions/runs/{run}/jobs'],capture_output=True,text=True,timeout=60)
   if p.returncode: raise RuntimeError(p.stderr[:500])
   jobs=json.loads(p.stdout)['jobs']
   if not jobs: raise RuntimeError('no jobs exist for expected run')
   state=[(j['status'],j['conclusion'],[(s['name'],s['status'],s['conclusion']) for s in j['steps']]) for j in jobs]
   now=time.monotonic()
   if last.get(run)!=state: changed[run]=now;last[run]=state
   print(json.dumps({'run':run,'elapsed_s':round(now-started),'jobs':state}),flush=True)
   if all(j['status']=='completed' for j in jobs):
    runs.remove(run)
    if any(j['conclusion']!='success' for j in jobs):failures.append(run)
    print('CHANGE: terminal run '+str(run),flush=True)
   elif now-changed[run]>20*60:
    print('PROBLEM: no step advancement for 20 minutes in '+str(run),flush=True);sys.exit(5)
  except Exception as e:
   print('PROBLEM: monitor request failed: '+str(e),flush=True);sys.exit(6)
 if runs:time.sleep(180)
print('RESULT: all diagnostic workflows terminal; failed='+str(failures),flush=True)
sys.exit(1 if failures else 0)

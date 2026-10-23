import subprocess, pathlib, time, datetime, json
out=pathlib.Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/out')
def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
log=open(out/'JL62b-full.log','w')
p=subprocess.Popen(['flock','/tmp/claude-501/complete-suite.lock','bash',str(out/'JL62b-full.sh')],stdout=log,stderr=subprocess.STDOUT)
(out/'JL62b-full-pid.json').write_text(json.dumps({'driver':__import__('os').getpid(),'flock':p.pid,'queued':now()}))
with open(out/'JL62b-memory.jsonl','a',buffering=1) as mem:
 while True:
  row={'time':now(),'memory_current':int(pathlib.Path('/sys/fs/cgroup/memory.current').read_text()),'events':pathlib.Path('/sys/fs/cgroup/memory.events').read_text(),'acquired':(out/'JL62b-full-acquired.txt').exists()}
  mem.write(json.dumps(row)+'\n')
  try: code=p.wait(timeout=120); break
  except subprocess.TimeoutExpired: pass
(out/'JL62b-full-exit.json').write_text(json.dumps({'exit':code,'ended':now()}))
print('Full suite finished',code,flush=True)

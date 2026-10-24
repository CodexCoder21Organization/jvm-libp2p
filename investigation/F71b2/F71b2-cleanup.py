from pathlib import Path
import subprocess,os,signal,time,json
out=Path(__file__).resolve().parent
repo=out.parent/'ws/F71b2/jvm-libp2p'
owned=[2866067,2868437,2873056,2874200,2881140,2882562]
def info(pid):
 try:
  text=(Path('/proc')/str(pid)/'stat').read_text(); tail=text[text.rfind(')')+2:].split()
  args=(Path('/proc')/str(pid)/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
  return {'pid':pid,'state':tail[0],'start':tail[19],'args':args}
 except FileNotFoundError:return {'pid':pid,'state':'absent'}
before={pid:info(pid) for pid in owned}
with (out/'F71b2-gradle-stop.log').open('w') as f:
 rc=subprocess.run(['timeout','60','./gradlew','--gradle-user-home','/tmp/F71b2-gradle-home','--stop'],cwd=repo,stdout=f,stderr=subprocess.STDOUT).returncode
limit=time.monotonic()+10
while time.monotonic()<limit and any(info(p)['state'] not in ('absent','Z') for p in owned):time.sleep(.1)
for pid in owned:
 now=info(pid);old=before[pid]
 if now['state'] not in ('absent','Z') and now.get('start')==old.get('start'):
  if 'GradleDaemon' not in now['args'] and 'KotlinCompileDaemon' not in now['args']:raise RuntimeError('Unexpected process for owned pid '+str(pid))
  os.kill(pid,signal.SIGTERM)
limit=time.monotonic()+10
while time.monotonic()<limit and any(info(p)['state'] not in ('absent','Z') for p in owned):time.sleep(.1)
after={pid:info(pid) for pid in owned}
# Keep the complete machine process list local; share only this task's process audit.
with Path('/tmp/F71b2-final-ps.txt').open('w') as f:subprocess.run(['ps','-eo','pid,args','-ww'],stdout=f,check=True)
repo_processes=[]
for p in Path('/proc').iterdir():
 if p.name.isdigit():
  try:
   if p.stat().st_uid != os.getuid(): continue
   if str((p/'cwd').resolve()).startswith(str(repo)):
    state=info(int(p.name))
    if state['state'] not in ('absent','Z'):repo_processes.append(state)
  except (FileNotFoundError, PermissionError):pass
record={'stop_exit':rc,'before':before,'after':after,'active_checkout_processes':repo_processes,'raw_ps':'/tmp/F71b2-final-ps.txt'}
(out/'F71b2-process-audit.json').write_text(json.dumps(record,indent=2))
print(json.dumps({'stop_exit':rc,'owned_states':{p:i['state'] for p,i in after.items()},'active_checkout_processes':len(repo_processes)}))
assert all(i['state'] in ('absent','Z') for i in after.values()) and not repo_processes

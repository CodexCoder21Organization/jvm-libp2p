import pathlib,subprocess,sys,datetime,json,os
root=pathlib.Path(__file__).resolve().parents[1]; repo=root/'consumer'; ev=root/'evidence'; label=sys.argv[1]
selectors=json.loads((ev/(label+'-selectors.json')).read_text())
cmd=['scripts/test.bash','--remote','--log',str(ev/(label+'-native.xml')),'--result-file',str(ev/(label+'-result.json'))]
for s in selectors:cmd+=['--test',s]
(ev/(label+'-command.json')).write_text(json.dumps(cmd,indent=2))
print(datetime.datetime.now(datetime.timezone.utc).isoformat(), 'START',label,len(selectors),flush=True)
p=subprocess.Popen(cmd,cwd=repo,start_new_session=True)
(ev/(label+'-pid.json')).write_text(json.dumps({'pid':p.pid,'start':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
try:
 rc=p.wait(timeout=5000)
except subprocess.TimeoutExpired:
 print('WAIT_LIMIT: remote job still active; no remote cancellation issued; inspect runner record',label,flush=True)
 sys.exit(75)
print('REMOTE_CLIENT_TERMINAL',label,rc,flush=True);sys.exit(rc)

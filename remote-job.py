import pathlib,subprocess,sys,datetime,json
root=pathlib.Path(__file__).resolve().parents[1];repo=root/'consumer';ev=root/'evidence';label=sys.argv[1]
selectors=json.loads((ev/(label+'-selectors.json')).read_text())
cmd=['scripts/test.bash','--remote','--log',str(ev/(label+'-native.xml')),'--result-file',str(ev/(label+'-result.json'))]
for s in selectors:cmd+=['--test',s]
(ev/(label+'-command.json')).write_text(json.dumps(cmd,indent=2))
with (ev/(label+'-client.log')).open('w') as f:
 p=subprocess.Popen(cmd,cwd=repo,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
(ev/(label+'-pid.json')).write_text(json.dumps({'pid':p.pid,'start':datetime.datetime.now(datetime.timezone.utc).isoformat()}))
print('REMOTE_CLIENT_STARTED',label,p.pid,flush=True)
# The command is tracked by PID. The lane polls the authoritative remote record under
# the run-sized bound; it never imposes a client-side build/test cutoff.
rc=p.wait();print('REMOTE_CLIENT_TERMINAL',label,rc,flush=True);sys.exit(rc)

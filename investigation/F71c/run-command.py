import subprocess,time,pathlib,json,sys
root=pathlib.Path.cwd(); evidence=root.parent.parent.parent/'out'/'F71c-evidence'; label=sys.argv[1]
subprocess.run(['git','fetch','origin'],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['git','rebase','--autostash','origin/develop'],check=True)
cmd=['timeout','--foreground','--signal=TERM','--kill-after=30s','25m','./gradlew','--gradle-user-home','/tmp/F71c-gradle-home','--offline','--max-workers=2']+sys.argv[2:]+['--stacktrace']
start=time.monotonic(); head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
with (evidence/(label+'.log')).open('w') as log:p=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
r={'label':label,'head':head,'exit':p.returncode,'wall_ms':round((time.monotonic()-start)*1000),'command':cmd}
(evidence/(label+'.json')).write_text(json.dumps(r,indent=2));print(json.dumps(r))
with (evidence.parent/'F71c-findings.md').open('a') as f:f.write('\nOBSERVED: '+json.dumps(r)+'\n')
sys.exit(p.returncode)

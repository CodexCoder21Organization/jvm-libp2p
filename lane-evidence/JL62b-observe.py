import pathlib, subprocess, json, time, datetime, shutil
out=pathlib.Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/out')
evidence=out.parent/'workspace/JL62b/evidence'
end=time.monotonic()+float(__import__('sys').argv[1])*60
last_note=0; last_push=time.monotonic()
while time.monotonic()<end:
 now=datetime.datetime.now(datetime.timezone.utc).isoformat()
 acquired=(out/'JL62b-full-acquired.txt').exists(); done=(out/'JL62b-full-exit.json').exists()
 locks=subprocess.check_output(['lslocks','-o','PID,COMMAND,MODE,PATH'],text=True)
 locks='; '.join(line.strip() for line in locks.splitlines() if 'complete-suite.lock' in line)
 mem=int(pathlib.Path('/sys/fs/cgroup/memory.current').read_text())
 state=f'{now} OBSERVED: full suite '+('finished '+(out/'JL62b-full-exit.json').read_text() if done else 'executing' if acquired else 'waiting for shared lock')+f'; memory.current={mem}; locks: {locks}'
 print(state,flush=True)
 if time.monotonic()-last_note>=300 or done:
  with open(out/'JL62b.md','a') as f: f.write('\n'+state+'\n')
  last_note=time.monotonic()
 if time.monotonic()-last_push>=1500 or done:
  for name in ['JL62b.md','JL62b-audit.py','JL62b-full.sh','JL62b-run-full.py','JL62b-observe.py','JL62b-memory.jsonl']:
   shutil.copy2(out/name,evidence/'lane-evidence'/name)
  subprocess.run(['git','add','lane-evidence'],cwd=evidence,check=True)
  changed=subprocess.check_output(['git','diff','--cached','--stat'],cwd=evidence,text=True)
  if changed:
   subprocess.run(['git','commit','-m','Record full-suite verification observations','-m','Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'],cwd=evidence,check=True)
   subprocess.run(['git','push','origin','wip/JL62b-evidence-20261023'],cwd=evidence,check=True)
  last_push=time.monotonic()
 if done: break
 time.sleep(min(55,max(0,end-time.monotonic())))

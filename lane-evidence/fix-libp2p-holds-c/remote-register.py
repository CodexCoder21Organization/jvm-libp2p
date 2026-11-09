from pathlib import Path
import subprocess,selectors,os,signal,time,json
root=Path('/code/workspace/fix-libp2p-holds-c');scratch=root/'scratch'
args=['scripts/test.bash','--remote']
for name in (scratch/'consumer-selectors.txt').read_text().splitlines():args+=['--test',name]
args+=['--log',str(scratch/'consumer-remote.xml'),'--result-file',str(scratch/'consumer-remote-result.json')]
started=time.monotonic();dispatched=False;recent=b''
process=subprocess.Popen(args,cwd=root/'UrlResolver',stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
state=dict(pid=process.pid,startedUtc=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),noDispatchLimitSeconds=600,totalLimitSeconds=720,dispatched=False)
(scratch/'consumer-remote-process.json').write_text(json.dumps(state,indent=2))
selector=selectors.DefaultSelector();selector.register(process.stdout,selectors.EVENT_READ)
bound=None
with (scratch/'consumer-remote-console.txt').open('wb') as log:
 while True:
  remaining=(720 if dispatched else 600)-(time.monotonic()-started)
  if remaining<=0:
   bound='total run bound' if dispatched else 'no dispatched run within 600 seconds'
   os.killpg(process.pid,signal.SIGTERM)
   try:process.wait(timeout=10)
   except subprocess.TimeoutExpired:
    os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=5)
   break
  events=selector.select(timeout=remaining)
  if not events:continue
  chunk=os.read(process.stdout.fileno(),65536)
  if not chunk:break
  log.write(chunk);log.flush();recent=(recent+chunk)[-32768:]
  if not dispatched and any(marker in recent for marker in [b'Remote build status: INSTALLING',b'Remote build status: BUILDING',b'Remote build status: RUNNING',b'Remote build status: TESTING']):
   dispatched=True;state['dispatched']=True;(scratch/'consumer-remote-process.json').write_text(json.dumps(state,indent=2))
process.wait(timeout=10);selector.close();process.stdout.close()
state.update(returncode=process.returncode,elapsedSeconds=round(time.monotonic()-started,2),bound=bound)
(scratch/'consumer-remote-process.json').write_text(json.dumps(state,indent=2));print(json.dumps(state))
raise SystemExit(124 if bound else process.returncode)

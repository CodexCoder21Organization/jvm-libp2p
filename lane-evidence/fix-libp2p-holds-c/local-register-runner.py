import json,subprocess,sys
from pathlib import Path
root=Path('/code/workspace/fix-libp2p-holds-c')
print('READY: serial local register runner',flush=True)
for line in sys.stdin:
 request=json.loads(line)
 if request.get('stop'):break
 log=root/'scratch'/request['console']
 print('START:',request['tag'],flush=True)
 try:
  with log.open('w') as output:
   result=subprocess.run(request['args'],cwd=root,stdout=output,stderr=subprocess.STDOUT,timeout=900)
  print('COMPLETE:',request['tag'],'rc='+str(result.returncode),flush=True)
 except subprocess.TimeoutExpired:
  print('COMPLETE:',request['tag'],'rc=124',flush=True)
print('CLOSED: serial local register runner',flush=True)

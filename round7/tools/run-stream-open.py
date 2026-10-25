from pathlib import Path
import subprocess,json,sys,datetime
w=Path(__file__).resolve().parent
results=[]
for i in range(1,4):
 label='item2-alone-'+str(i)
 p=subprocess.run([sys.executable,str(w/'run-one.py'),label,'io.libp2p.transport.CompletedStreamWriteNotificationTest'],cwd=w.parent)
 result=json.loads((w.parent/'round7'/label/'result.json').read_text());results.append(result)
 with Path('/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/F71h-findings.md').open('a') as f:
  f.write('\nOBSERVED '+datetime.datetime.now(datetime.timezone.utc).isoformat()+': Item 2 solo '+str(i)+' FIRST ATTEMPT: '+json.dumps(result['suites'])+'; command exit '+str(p.returncode)+'. Planned reproduction, not an asserted timeout fix.\n')
(w.parent/'round7'/'item2-solo-summary.json').write_text(json.dumps(results,indent=2))
sys.exit(1 if any(r['exit'] or not r['suites'] for r in results) else 0)

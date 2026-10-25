from pathlib import Path
import subprocess,json,sys,datetime
w=Path(__file__).resolve().parent
for i in range(1,4):
 label='item3-alone-'+str(i)
 p=subprocess.run([sys.executable,str(w/'run-one.py'),label,'io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest'],cwd=w.parent)
 result=json.loads((w.parent/'round6'/label/'result.json').read_text())
 with Path('/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/F71g-findings.md').open('a') as f:
  f.write('\nOBSERVED '+datetime.datetime.now(datetime.timezone.utc).isoformat()+': Item 3 solo '+str(i)+' FIRST ATTEMPT: '+json.dumps(result['suites'])+'; command exit '+str(p.returncode)+'. This is planned reproduction, not an asserted fix.\n')
 if p.returncode:sys.exit(p.returncode)

from pathlib import Path
import subprocess,json,sys
out=Path(__file__).resolve().parent
for label,cls in [('baseline-close','io.libp2p.transport.CompletedConnectionCloseNotificationTest'),('baseline-converter','io.libp2p.etc.types.CompletedNettyFutureConversionTest')]:
 subprocess.run(['python3',str(out/'F71b2-run.py'),label,cls],check=True)
 r=json.loads((out/f'F71b2-{label}.json').read_text())
 if r['exit'] or int(r.get('failures',0)) or int(r.get('errors',0)) or not r.get('tests'):
  print('STOP: baseline did not produce a passing runtime record',flush=True);sys.exit(1)

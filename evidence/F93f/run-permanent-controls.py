from pathlib import Path
import subprocess,json,sys
p=Path(__file__).resolve().parent
selectors=['io.libp2p.transport.F93fQuicFullQueueTest','io.libp2p.transport.F93fQuicLifecycleSoakTest']
# Test-only overlay must not contain the candidate implementation on old-source controls.
for name,overlay,expected in [('permanent-old-red','permanent-test-only',1),('permanent-head-green','permanent-fixtures,baseline-all',0),('permanent-revert-red','permanent-test-only',1)]:
 result=subprocess.run(['python3',str(p/'run-gate.py'),name,'2','1',overlay,*selectors])
 s=json.loads((p/(name+'-summary.json')).read_text())
 print(name,{k:s[k] for k in ['attempts','passed','failed','seconds']},flush=True)
 if result.returncode!=expected or s['attempts']!=2 or s['failed']!=(2 if expected else 0):sys.exit('Unexpected control result: '+name)

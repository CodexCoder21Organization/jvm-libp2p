from pathlib import Path
import subprocess,json,sys
p=Path(__file__).resolve().parent
selectors=['io.libp2p.transport.F93dQuicFullQueueTest','io.libp2p.transport.F93fQuicLifecycleSoakTest']
for name,overlay,expected in [('remaining-old-red','soak-fixed-budget,additional-fixtures',1),('remaining-head-green','soak-fixed-budget,additional-fixtures,baseline-all',0),('remaining-revert-red','soak-fixed-budget,additional-fixtures',1)]:
 result=subprocess.run(['python3',str(p/'run-gate.py'),name,'2','1',overlay,*selectors])
 s=json.loads((p/(name+'-summary.json')).read_text())
 print(name,{k:s[k] for k in ['attempts','passed','failed','seconds']},flush=True)
 if result.returncode!=expected or s['attempts']!=2 or s['failed']!=(2 if expected else 0):sys.exit('Unexpected control result: '+name)

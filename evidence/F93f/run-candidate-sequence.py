from pathlib import Path
import subprocess,sys,json
root=Path(__file__).resolve().parent
selectors=['io.libp2p.transport.F93dQuicHandshakeShutdownTest','io.libp2p.transport.F93dQuicOrdinaryCloseOrderingTest','io.libp2p.transport.F93dQuicShutdownHandoffOrderingTest']
for name,overlay,expected in [('candidate-three-green','baseline-all',0),('candidate-three-revert-red','pre-candidate,baseline-all',1)]:
 result=subprocess.run(['python3',str(root/'run-gate.py'),name,'1','1',overlay,*selectors])
 if result.returncode!=expected: sys.exit('Unexpected result for '+name+': '+str(result.returncode))
print('Candidate green and repeated old-source control completed.')

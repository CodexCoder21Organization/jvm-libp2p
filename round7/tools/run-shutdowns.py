from pathlib import Path
import subprocess,json,sys,datetime
w=Path(__file__).resolve().parent
classes=['ReentrantChildWriteCloseTest','OffLoopParentWriteNotificationTest','ReviewTerminalWriteConversionTest','ReviewCompletedShutdownTest','ReviewChildLoopAffinityTest']
for cls in classes:
 label='shutdown-'+cls
 p=subprocess.run([sys.executable,str(w/'run-one.py'),label,'io.libp2p.transport.'+cls],cwd=w.parent)
 result=json.loads((w.parent/'round7'/label/'result.json').read_text())
 with Path('/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/F71h-findings.md').open('a') as f:
  f.write('\nOBSERVED '+datetime.datetime.now(datetime.timezone.utc).isoformat()+': FIRST ATTEMPT '+cls+': '+json.dumps(result['suites'])+'; command exit '+str(p.returncode)+'.\n')
 if p.returncode:sys.exit(p.returncode)

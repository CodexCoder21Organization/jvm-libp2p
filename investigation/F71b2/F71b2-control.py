from pathlib import Path
import subprocess,sys
out=Path(__file__).resolve().parent
repo=out.parent/'ws/F71b2/jvm-libp2p'
p=repo/'libp2p/src/main/kotlin/io/libp2p/etc/types/NettyExt.kt'
original=p.read_bytes()
mode=sys.argv[1]
try:
 text=original.decode()
 if mode=='pending':
  assert text.count('addListener { transfer() }')==2
  text=text.replace('addListener { transfer() }', 'addListener { if (isSuccess) transfer() }')
  cls='io.libp2p.etc.types.PendingNettyFutureConversionTest'
 elif mode=='state':
  assert text.count('if (isDone) transfer()')==2
  text=text.replace('if (isDone) transfer()', '// Post-registration recheck removed only for this local control.')
  cls='io.libp2p.etc.types.NettyFutureConversionStateTest'
 else: raise ValueError(mode)
 p.write_text(text)
 subprocess.run(['python3',str(out/'F71b2-run.py'),mode+'-mutation',cls],check=True)
finally:
 p.write_bytes(original)
 print('Restored exact original converter source',flush=True)

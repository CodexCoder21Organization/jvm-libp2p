from pathlib import Path
import subprocess,json,sys
out=Path(__file__).resolve().parent
for label,cls in [("pending-green","io.libp2p.etc.types.PendingNettyFutureConversionTest"),("state-green","io.libp2p.etc.types.NettyFutureConversionStateTest")]:
 subprocess.run(["python3",str(out/"F71b2-run.py"),label,cls],check=True)
 r=json.loads((out/f"F71b2-{label}.json").read_text())
 if r["exit"] or int(r.get("failures",0)) or int(r.get("errors",0)) or not r.get("tests"):
  print("STOP: first head failure retained",flush=True);sys.exit(1)

from pathlib import Path
import subprocess,time
out=Path(__file__).resolve().parent
# Serial commands each retain their own 600s bound and record. The first is already tracked separately.
subprocess.run(['python3',str(out/'F71b2-run.py'),'initial-converter','io.libp2p.etc.types.CompletedNettyFutureConversionTest'],check=True)

from datetime import datetime,timezone
from pathlib import Path
import sys
with Path('/code/workspace/fix-libp2p-holds-c-findings.md').open('a') as f:
 for s in sys.argv[1:]:f.write(datetime.now(timezone.utc).isoformat()+' '+s+'\n')

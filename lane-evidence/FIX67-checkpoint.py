from pathlib import Path
import shutil, subprocess
root=Path('/tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad')
repo=root/'workspace/FIX67-evidence'; dest=repo/'lane-evidence'; out=root/'out'
for p in out.glob('FIX67*'):
    if p.is_dir(): shutil.copytree(p,dest/p.name,dirs_exist_ok=True)
    else: shutil.copy2(p,dest/p.name)
if (out/'lane-evidence/REV67-develop-control-results').exists():
    shutil.copytree(out/'lane-evidence/REV67-develop-control-results',dest/'REV67-develop-control-results',dirs_exist_ok=True)
subprocess.run(['git','add','-f','lane-evidence'],cwd=repo,check=True)
changed=subprocess.run(['git','diff','--cached','--quiet'],cwd=repo).returncode
if changed:
    subprocess.run(['git','commit','-m','Checkpoint FIX67 validation evidence','-m','Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'],cwd=repo,check=True)
    subprocess.run(['git','push'],cwd=repo,check=True)

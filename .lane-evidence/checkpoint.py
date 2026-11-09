from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parent.parent
os.chdir(root)
Path('.lane-evidence/findings.md').write_bytes(Path('../fix-libp2p-ports-findings.md').read_bytes())
ref = 'refs/heads/wip/fix-libp2p-ports-evidence'
previous = subprocess.run(['git', 'rev-parse', '--verify', ref], capture_output=True, text=True)
parent = previous.stdout.strip() if previous.returncode == 0 else subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
index = root / '.git' / 'lane-evidence-index'
index.unlink(missing_ok=True)
env = dict(os.environ, GIT_INDEX_FILE=str(index))
subprocess.run(['git', 'read-tree', 'HEAD'], env=env, check=True)
subprocess.run(['git', 'add', '-f', '.lane-evidence'], env=env, check=True)
tree = subprocess.check_output(['git', 'write-tree'], env=env, text=True).strip()
commit = subprocess.check_output(['git', 'commit-tree', tree, '-p', parent], input='Record native listener-port evidence\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>\n', text=True).strip()
subprocess.run(['git', 'update-ref', ref, commit], check=True)
subprocess.run(['git', 'push', 'origin', ref + ':' + ref], check=True)
index.unlink(missing_ok=True)
print(commit)

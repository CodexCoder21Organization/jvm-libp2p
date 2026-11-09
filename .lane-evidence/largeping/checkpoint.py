import os
import subprocess
from pathlib import Path

root = Path.cwd()
env = os.environ.copy()
env['GIT_INDEX_FILE'] = str(root / '.lane-evidence/largeping/checkpoint.index')

def git(*args, **kwargs):
    return subprocess.check_output(['git', *args], env=env, **kwargs).decode().strip()

(root / '.lane-evidence/largeping/findings.md').write_bytes((root.parent / 'fix-largeping-findings.md').read_bytes())
git('read-tree', 'HEAD')
paths = []
for path in (root / '.lane-evidence/largeping').rglob('*'):
    if path.is_file() and path.suffix in {'.md', '.xml', '.txt', '.log', '.py', '.json'}:
        if path.name not in {'TESTING.md', 'CODE_REVIEW.md', 'PHILOSOPHY.md', 'failing-job.log', 'ci-host-output.txt'}:
            paths.append(str(path.relative_to(root)))
git('add', '-f', *paths)
tree = git('write-tree')
head = git('rev-parse', 'HEAD')
previous = subprocess.run(['git', 'rev-parse', '--verify', 'refs/heads/wip/fix-largeping-evidence'], capture_output=True, text=True)
parents = ['-p', head]
if previous.returncode == 0 and previous.stdout.strip() != head:
    parents.extend(['-p', previous.stdout.strip()])
commit = git('commit-tree', tree, *parents, input=b'Record large ping verification evidence\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>\n')
git('update-ref', 'refs/heads/wip/fix-largeping-evidence', commit)
subprocess.run(['git', 'push', 'origin', 'wip/fix-largeping-evidence'], check=True)
Path(env['GIT_INDEX_FILE']).unlink(missing_ok=True)
print('Evidence commit', commit)

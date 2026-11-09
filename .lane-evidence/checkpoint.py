from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
os.chdir(root)
(root / ".lane-evidence/findings.md").write_text((root.parent / "fix-yamux-budget-findings.md").read_text())
index = root / ".git/lane-evidence.index"
env = dict(os.environ, GIT_INDEX_FILE=str(index))
def git(*args, **kwargs):
    return subprocess.check_output(["git", *args], env=env, text=True, **kwargs).strip()
git("read-tree", "HEAD")
git("add", "-u")
git("add", "-f", ".lane-evidence")
tree = git("write-tree")
branch = "refs/heads/wip/fix-yamux-budget-evidence"
prior = subprocess.run(["git", "rev-parse", "--verify", branch], capture_output=True, text=True)
parent = prior.stdout.strip() if prior.returncode == 0 else git("rev-parse", "HEAD")
message = sys.argv[1] + "\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>\n"
commit = git("commit-tree", tree, "-p", parent, input=message)
git("update-ref", branch, commit)
print(git("push", "origin", "wip/fix-yamux-budget-evidence"))
print("https://github.com/CodexCoder21Organization/jvm-libp2p/commit/" + commit)

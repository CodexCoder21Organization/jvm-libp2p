from pathlib import Path
import subprocess,shutil,sys
b=Path(__file__).resolve().parent
repo=b.parent/'evidence-repo'
branch='wip/flake-urlprotocol-dial10-evidence'
expected=(b/'evidence-expected-head.txt').read_text().strip()
def git(*args):return subprocess.check_output(['git','-C',str(repo),*args],text=True).strip()
git('fetch','origin')
actual=git('rev-parse','refs/remotes/origin/'+branch)
if actual!=expected:raise SystemExit(f'STOP: Evidence branch moved from {expected} to {actual}')
dst=repo/'evidence/flake-urlprotocol-dial10'
for name in sys.argv[2:]:
 source=b/name;target=dst/name
 if source.is_dir():shutil.copytree(source,target,dirs_exist_ok=True)
 else:target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
shutil.copy2(Path('/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/out/flake-urlprotocol-dial10-findings.md'),dst/'findings.md')
shutil.copy2(Path(__file__),dst/'checkpoint.py')
git('add','evidence')
git('commit','-m',sys.argv[1],'-m','Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>')
new=git('rev-parse','HEAD')
git('push','--force-with-lease=refs/heads/'+branch+':'+expected,'origin','HEAD:refs/heads/'+branch)
(b/'evidence-expected-head.txt').write_text(new+'\n')
print(new)

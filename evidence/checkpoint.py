from pathlib import Path
import subprocess,shutil,gzip
base=Path(__file__).resolve().parent.parent
repo=base/'evidence-tree'
branch='wip/flake-urlprotocol-dial5-evidence'
def run(*a):return subprocess.check_output(a,text=True).strip()
expected=run('git','-C',str(repo),'rev-parse','HEAD')
run('git','-C',str(repo),'fetch','origin')
remote=run('git','-C',str(repo),'ls-remote','origin','refs/heads/'+branch).split()[0]
if remote!=expected:raise SystemExit('STOP: evidence branch moved, expected '+expected+' actual '+remote)
findings=Path('/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/out/flake-urlprotocol-dial5-findings.md')
shutil.copy2(findings,base/'evidence/findings.md')
for p in (base/'evidence').rglob('*'):
 if not p.is_file():continue
 target=repo/'evidence'/p.relative_to(base/'evidence');target.parent.mkdir(parents=True,exist_ok=True)
 data=p.read_bytes()
 if len(data)>100000 and p.suffix!='.gz':target=Path(str(target)+'.gz');data=gzip.compress(data)
 target.write_bytes(data)
run('git','-C',str(repo),'add','evidence')
if run('git','-C',str(repo),'status','--porcelain'):
 run('git','-C',str(repo),'commit','-m','Checkpoint snapshot qualification evidence\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>')
 run('git','-C',str(repo),'push','--force-with-lease='+branch+':'+expected,'origin','HEAD:refs/heads/'+branch)
print(run('git','-C',str(repo),'rev-parse','HEAD'))

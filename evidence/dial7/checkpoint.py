import subprocess,pathlib,shutil,sys
root=pathlib.Path(__file__).resolve().parents[1]; repo=root/'evidence-tree'; source=root/'evidence'; state=root/'evidence-remote-head'; branch='wip/flake-urlprotocol-dial7-evidence'
def run(args,**kw): return subprocess.check_output(args,cwd=repo,text=True,**kw).strip()
subprocess.run(['git','fetch','origin'],cwd=repo,check=True,stdout=subprocess.DEVNULL)
actual=run(['git','ls-remote','origin',f'refs/heads/{branch}']).split()
actual=actual[0] if actual else ''
expected=state.read_text().strip() if state.exists() else ''
if actual!=expected: raise SystemExit(f'STOP remote branch moved: expected={expected} actual={actual}')
shutil.copytree(source,repo/'evidence/dial7',dirs_exist_ok=True)
shutil.copy2('/tmp/claude-1000/-code/2e1aa1b4-104e-4f23-9d8b-414b02ecb20f/scratchpad/out/flake-urlprotocol-dial7-findings.md',repo/'evidence/dial7/findings.md')
subprocess.run(['git','add','evidence'],cwd=repo,check=True)
if subprocess.run(['git','diff','--cached','--quiet'],cwd=repo).returncode==0: print('No evidence changes'); sys.exit()
subprocess.run(['git','commit','-m',sys.argv[1]+'\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>'],cwd=repo,check=True)
subprocess.run(['git','push',f'--force-with-lease={branch}:{expected}','origin',f'HEAD:refs/heads/{branch}'],cwd=repo,check=True)
state.write_text(run(['git','rev-parse','HEAD'])+'\n')

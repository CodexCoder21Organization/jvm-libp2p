from pathlib import Path
import shutil,subprocess,sys
w=Path(__file__).resolve().parent;r=w.parent/'round7';dest=w/'evidence-branch'/'round7'
shutil.copytree(r,dest,dirs_exist_ok=True)
shutil.copy2('/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/F71h-findings.md',dest/'findings.md')
t=dest/'tools';t.mkdir(exist_ok=True)
for name in ['run-one.py','capture.gradle','checkpoint.py','run-pair.py','captured-build.gradle']:
 shutil.copy2(w/name,t/name)
repo=w/'evidence-branch'
for cmd in [['git','fetch','origin'],['git','rebase','--autostash','origin/evidence/pr71-20261024'],['git','add','-f','round7'],['git','commit','-m',sys.argv[1]],['git','push','origin','evidence/pr71-20261024']]:
 subprocess.run(cmd,cwd=repo,check=True)

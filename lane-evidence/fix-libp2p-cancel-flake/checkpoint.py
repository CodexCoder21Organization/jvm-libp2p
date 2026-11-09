from pathlib import Path
import subprocess, os, shutil, sys
root=Path.cwd(); evidence=root/'lane-evidence/fix-libp2p-cancel-flake'
shutil.copyfile(root.parent/'fix-libp2p-cancel-flake-findings.md', evidence/'findings.md')
branch='wip/fix-libp2p-cancel-flake-evidence'
def git(*a, **kw): return subprocess.check_output(['git',*a],text=True,**kw).strip()
old=subprocess.run(['git','rev-parse','--verify',branch],capture_output=True,text=True)
parent=old.stdout.strip() if old.returncode==0 else git('rev-parse','HEAD')
env=os.environ.copy(); env['GIT_INDEX_FILE']=str(root/'.lane-scratch/evidence-index')
subprocess.run(['git','read-tree',parent],env=env,check=True)
subprocess.run(['git','add','-f','lane-evidence/fix-libp2p-cancel-flake'],env=env,check=True)
tree=git('write-tree',env=env)
message=sys.argv[1]+'\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>\n'
commit=git('commit-tree',tree,'-p',parent,input=message)
subprocess.run(['git','update-ref','refs/heads/'+branch,commit],check=True)
subprocess.run(['git','push','origin',branch],check=True)
print(commit)

import os
from pathlib import Path
import shutil
import subprocess

root=Path.cwd()
evidence=root/'.lane-evidence'
shutil.copyfile(root.parent/'fix-libp2p98b-findings.md', evidence/'findings.md')
env=os.environ.copy()
env['GIT_INDEX_FILE']=str(root/'.git/lane-evidence-index')
def git(*args, input=None):
    return subprocess.check_output(['git',*args],env=env,input=input,text=True).strip()
head=git('rev-parse','HEAD')
ref='refs/heads/wip/fix-libp2p98b-evidence'
prior=subprocess.run(['git','rev-parse','--verify',ref],capture_output=True,text=True)
git('read-tree',head)
files=[str(p.relative_to(root)) for p in evidence.iterdir() if p.suffix in ('.log','.xml') or p.name in ('findings.md','pr-checks.json','pr-body.md','review.md','verify.py','checkpoint.py')]
git('add','-f','--',*files,'.github/scripts/download-verified.sh','.github/scripts/test_download_verified.py')
tree=git('write-tree')
parents=['-p',prior.stdout.strip()] if prior.returncode==0 else ['-p',head]
if prior.returncode==0:
    parents += ['-p',head]
commit=git('commit-tree',tree,*parents,input='Record downloader pacing evidence\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>\n')
git('update-ref',ref,commit)
subprocess.run(['git','push','origin',ref+':'+ref],check=True)
print('Evidence checkpoint:',commit)
(root/'.git/lane-evidence-index').unlink(missing_ok=True)

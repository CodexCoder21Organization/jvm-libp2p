from pathlib import Path
import subprocess,os,tempfile
out=Path(__file__).resolve().parent
repo=out.parent/'ws/F71b2/jvm-libp2p'
branch='wip/F71b2-20261024'
def git(*a,**kw):return subprocess.check_output(['git',*a],cwd=repo,**kw).decode().strip()
index=out/'F71b2-index.tmp';index.unlink(missing_ok=True)
env=dict(os.environ,GIT_INDEX_FILE=str(index))
git('read-tree','HEAD',env=env)
for p in sorted(out.glob('F71b2-*')):
 if p.is_file() and p.suffix not in ['.tmp'] and not p.name.endswith('mutation.patch'):
  blob=git('hash-object','-w',str(p))
  git('update-index','--add','--cacheinfo','100644',blob,'investigation/F71b2/'+p.name,env=env)
tree=git('write-tree',env=env)
parents=['-p','HEAD']
try:prev=git('rev-parse','--verify','refs/heads/'+branch);parents+=['-p',prev]
except subprocess.CalledProcessError:pass
commit=git('commit-tree',tree,*parents,input=b'Checkpoint resumed round 2 evidence and current work\n')
git('update-ref','refs/heads/'+branch,commit)
print(git('push','origin','refs/heads/'+branch))
index.unlink(missing_ok=True)
print(branch+'@'+commit)

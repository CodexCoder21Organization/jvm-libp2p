from pathlib import Path
import subprocess,os,json,sys
repo=Path('/code/ws/W4/workspace/UrlProtocol-012450'); upstream=sys.argv[1]
v=json.loads(Path('/tmp/W4-012450-evidence/verification.json').read_text())
assert isinstance(v['required_checks'],list) and len(v['required_checks'])==2
assert all(c['conclusion']=='success' and c['head_sha']==upstream for c in v['required_checks'])
assert upstream==v['head']
env=os.environ.copy();env['GIT_INDEX_FILE']='/tmp/W4-012450-consumer-index'
Path(env['GIT_INDEX_FILE']).unlink(missing_ok=True)
def git(*a,data=None,indexed=False):
 return subprocess.check_output(['git',*a],cwd=repo,input=data,text=True,env=env if indexed else None).strip()
base=git('rev-parse','HEAD'); branch='verification/W4-012450-final-consumer';git('read-tree',base,indexed=True)
def put(path,s):
 blob=git('hash-object','-w','--stdin',data=s);git('update-index','--add','--cacheinfo',f'100644,{blob},{path}',indexed=True)
put('.github/w4-012450-consumer-stage.py',Path('/tmp/W4-012450-consumer-stage.py').read_text())
workflow=Path('/tmp/W4-012450-consumer-template.yml').read_text().replace('verification/W4-234447-consumer-qualified-v2',branch).replace('90d8a4ea17b4d67f25bb724ce6bb1334808e5c82',upstream).replace('w4-234447-consumer-stage.py','w4-012450-consumer-stage.py').replace('W4-234447-consumer-qualified-v2','W4-012450-final-consumer')
assert '--local-and-remote' in workflow
assert workflow.count('--test ')==13
put('.github/workflows/w4-012450-consumer.yml',workflow)
tree=git('write-tree',indexed=True);proof=git('commit-tree',tree,'-p',base,data='Verify thirteen consumer cases against a local upstream build\n')
git('update-ref','refs/heads/'+branch,proof);print(git('push','origin',branch))
d={'source':base,'proof':proof,'branch':branch,'upstream':upstream};Path('/tmp/W4-012450-consumer-proof.json').write_text(json.dumps(d,indent=2)+'\n');print(json.dumps(d,indent=2))

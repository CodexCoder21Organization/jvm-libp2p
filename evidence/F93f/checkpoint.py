from pathlib import Path
import subprocess,sys,json,datetime
root=Path(__file__).resolve().parents[2];e=Path(__file__).resolve().parent

def git(*args):
 return subprocess.check_output(['git',*args],cwd=root,text=True).strip()
branch=git('rev-parse','--abbrev-ref','HEAD')
assert branch=='wip/F93f-evidence-20261105',branch
state=json.loads(subprocess.check_output(['gh','pr','view','https://github.com/CodexCoder21Organization/jvm-libp2p/pull/93','--json','state'],text=True))
assert state['state']=='OPEN',state
stamp=datetime.datetime.now(datetime.timezone.utc).isoformat()
with (e/'findings.md').open('a') as f:
 f.write('\n'+stamp+' OBSERVED: Before evidence checkpoint: '+json.dumps({'remotes':git('remote','-v'),'branch':branch,'previous_head':git('log','-1','--oneline'),'pr':state})+'. INFER: Next checkpoint remains the current plan.\n')
subprocess.run(['git','add','evidence/F93f'],cwd=root,check=True)
patterns=['ghp_'+'[A-Za-z0-9]{20,}','github'+'_pat_','TOKEN'+'=','SECRET'+'=','PASSWORD'+'=','dop_v1_'+'[0-9a-f]{10,}','BEGIN OPENSSH'+' PRIVATE']
scan=subprocess.run(['git','grep','-I','-n','-E','|'.join(patterns)],cwd=root,capture_output=True,text=True)
if scan.returncode!=1:
 print('Evidence scan found matches or failed; push stopped.');sys.exit(1)
subprocess.run(['git','commit','-m',sys.argv[1]],cwd=root,check=True,stdout=subprocess.DEVNULL)
for args in [('remote','-v'),('rev-parse','--abbrev-ref','HEAD'),('log','-1','--oneline')]:print(git(*args))
subprocess.run(['git','push','origin',branch],cwd=root,check=True)
local=git('rev-parse','HEAD');remote=git('ls-remote','origin','refs/heads/'+branch).split()[0]
assert local==remote,(local,remote)
with (e/'findings.md').open('a') as f:f.write('\n'+datetime.datetime.now(datetime.timezone.utc).isoformat()+' OBSERVED: Evidence push verified local=remote '+local+'. INFER: Next checkpoint remains the current plan.\n')
print('Verified evidence',local)

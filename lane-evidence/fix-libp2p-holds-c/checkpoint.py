import subprocess,pathlib,shutil,os,datetime
root=pathlib.Path('/code/workspace/fix-libp2p-holds-c')
for repo in ['jvm-libp2p','UrlResolver']:
 p=root/repo; evidence=p/'lane-evidence/fix-libp2p-holds-c';evidence.mkdir(parents=True,exist_ok=True)
 shutil.copy('/code/workspace/fix-libp2p-holds-c-findings.md',evidence/'findings.md')
 for x in (root/'scratch').iterdir():
  if x.name.startswith(('upstream-','consumer-','invariants','independent-reviews','test-edits')) and x.is_file(): shutil.copy(x,evidence/x.name)
  if x.is_dir() and x.name.startswith('upstream-'):shutil.copytree(x,evidence/x.name,dirs_exist_ok=True)
 for name in ['SerialLocal.java','serial-local.py','native-summary.py','run-native.sh','consumer-selectors.txt','consumer-summary.py','local-register-runner.py','remote-register.py','checkpoint.py','note.py']:
  shutil.copy(root/'scratch'/name,evidence/name)
 idx=root/'scratch'/f'{repo}-index';idx.unlink(missing_ok=True);env=dict(os.environ,GIT_INDEX_FILE=str(idx))
 def git(*a):return subprocess.check_output(['git','-C',str(p),*a],env=env,text=True).strip()
 git('read-tree','HEAD');git('add','-f','lane-evidence/fix-libp2p-holds-c');tree=git('write-tree')
 names=git('ls-tree','-r','--name-only',tree,'--','lane-evidence/fix-libp2p-holds-c').splitlines()
 assert not any('/consumer-cache/' in n or n.endswith(('.jar','.class','.zip')) for n in names), 'Build output selected for evidence' 
 commit=git('commit-tree',tree,'-p','HEAD','-m','Record cancellation lane evidence\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>')
 git('push','origin',f'{commit}:refs/heads/wip/fix-libp2p-holds-c-evidence','--force-with-lease');print(repo,commit)

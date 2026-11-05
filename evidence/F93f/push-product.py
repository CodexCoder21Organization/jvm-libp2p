from pathlib import Path
import subprocess,json,datetime
base=Path(__file__).resolve().parents[3];repo=base/'jvm-libp2p';e=Path(__file__).resolve().parent
expected='460504238f9f66d3f8aab3400adb8c5e45d404be'
def git(*args):return subprocess.check_output(['git',*args],cwd=repo,text=True).strip()
assert git('rev-parse','--abbrev-ref','HEAD')=='flibp5-acceptor-shutdown-snapshot41'
assert not git('status','--porcelain')
for name,passed,failed in [('candidate-three-red',1,2),('candidate-three-green',3,0),('candidate-three-revert-red',1,2)]:
 s=json.loads((e/(name+'-summary.json')).read_text())
 assert (s['attempts'],s['passed'],s['failed'],s['skipped'],s['retry_elements'])==(3,passed,failed,0,[]),(name,s)
subprocess.run(['git','fetch','origin'],cwd=repo,check=True)
subprocess.run(['git','rebase','origin/develop'],cwd=repo,check=True)
assert git('rev-parse','HEAD')==expected,'Rebase changed tested head; rebuild/retest before pushing'
state=json.loads(subprocess.check_output(['gh','pr','view','https://github.com/CodexCoder21Organization/jvm-libp2p/pull/93','--json','state,headRefOid,body,mergeStateStatus'],text=True))
assert state['state']=='OPEN',state['state']
(e/'pr-before-candidate.json').write_text(json.dumps(state,indent=2))
identity={'remotes':git('remote','-v'),'branch':git('rev-parse','--abbrev-ref','HEAD'),'head':git('log','-1','--oneline'),'default_head':git('rev-parse','origin/develop'),'pr_state':state['state']}
with (e/'findings.md').open('a') as f:f.write('\n'+datetime.datetime.now(datetime.timezone.utc).isoformat()+' OBSERVED: Before product push, from product checkout: '+json.dumps(identity)+'.\n')
for k,v in identity.items():print(k+': '+v)
subprocess.run(['git','push','--force-with-lease','origin','flibp5-acceptor-shutdown-snapshot41'],cwd=repo,check=True)
remote=git('ls-remote','origin','refs/heads/flibp5-acceptor-shutdown-snapshot41').split()[0]
assert remote==expected,remote
with (e/'findings.md').open('a') as f:f.write('\n'+datetime.datetime.now(datetime.timezone.utc).isoformat()+' OBSERVED: Candidate product push verified local=remote '+remote+'. OBSERVED: Red3 attempts=1pass/2fail, restored green3/3, removed-fix again1pass/2fail; all single attempts with no retries. INFER: Next checkpoint is late-child diagnostic and soak/full-queue regressions.\n')
print('Verified product',remote)

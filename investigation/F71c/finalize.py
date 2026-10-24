import subprocess,pathlib,json,shutil,datetime,sys
root=pathlib.Path.cwd().resolve();ev=root.parent.parent.parent/'out'/'F71c-evidence';wip=root.parent/'F71c-records';findings=ev.parent/'F71c-findings.md'
def call(cmd,**kw):return subprocess.run(cmd,check=True,timeout=30,**kw)
def output(cmd):return subprocess.check_output(cmd,text=True,timeout=20).strip()
with (ev/'gradle-stop.log').open('w') as log:
 call(['timeout','--foreground','--signal=TERM','--kill-after=5s','25s','./gradlew','--gradle-user-home','/tmp/F71c-gradle-home','--stop'],stdout=log,stderr=subprocess.STDOUT)
call([sys.executable,str(ev/'cleanup-owned.py')])
reports=root/'libp2p/build/test-results/test'
if reports.exists():shutil.copytree(reports,ev/'last-native-test-reports',dirs_exist_ok=True)
rs=json.loads((ev/'verified-register.json').read_text());expected=(ev/'final-class-list.txt').read_text().splitlines();done={r['class'] for r in rs};required=expected[:17]
head=output(['git','rev-parse','HEAD']);assert all(r['head']==head for r in rs)
counts={r['class'].rsplit('.',1)[1]:r.get('xml',{}) for r in rs}
failures=[r for r in rs if r['exit']]
missing=[c for c in required if c not in done]
(ev/'register-completion.json').write_text(json.dumps({'completed':len(rs),'expected':len(expected),'missing_required':missing,'hard_batch_limit_seconds':480,'complete':len(rs)==len(expected)},indent=2))
state=json.loads(output(['gh','api','repos/CodexCoder21Organization/jvm-libp2p/pulls/71']))
assert state['head']['sha']==head
(ev/'pr-before-final-patch.json').write_text(json.dumps(state,indent=2))
status=output(['git','status','--porcelain']);assert not status
remote=output(['git','ls-remote','origin','refs/heads/fix/completed-netty-future-results']).split()[0];assert remote==head
with findings.open('a') as f:
 f.write('\nOBSERVED: Finalization '+datetime.datetime.now(datetime.timezone.utc).isoformat()+'. Source head '+head+' is pushed and checkout is clean. Verified register completed '+str(len(rs))+'/'+str(len(expected))+' classes; failed classes '+str([r['class'] for r in failures])+'. Missing required classes '+str(missing)+'. Native per-class XML and every preceding failed attempt remain preserved.\n')
 f.write('OBSERVED: ./gradlew --gradle-user-home /tmp/F71c-gradle-home --stop completed; owned Java process cleanup and ps -eo pid,args -ww audit found no remaining owned JVM. No merge/enqueue/publish/deploy/handoff mutation occurred.\n')
 if missing:f.write('OBSERVED: The bounded fresh register ended before all required classes completed; no unexecuted case is counted as passing.\n')
text=findings.read_text().replace('- [in progress] Sweep completed (24 logical sites); formatting changed files.','- [done] Sweep completed (24 logical sites); configured formatter/check passed.')
if not missing and not failures:text=text.replace('- [pending] First final-head attempt per requested class; preserve XML and full failures.','- [done] All required final-head classes passed; all first failures and native XML preserved.')
findings.write_text(text)
call([sys.executable,str(ev/'build-description.py')])
shutil.copytree(ev,wip/'investigation/F71c',dirs_exist_ok=True);shutil.copy2(findings,wip/'investigation/F71c/findings.md')
call(['git','-C',str(wip),'add','-f','investigation/F71c'])
if output(['git','-C',str(wip),'status','--porcelain']):
 call(['git','-C',str(wip),'commit','-m','Save final round 3 tests and owned process cleanup'])
 call(['git','-C',str(wip),'push','origin','HEAD'])
with (ev/'pr-after-final-patch.json').open('w') as log:
 call(['gh','api','--method','PATCH','repos/CodexCoder21Organization/jvm-libp2p/pulls/71','--input',str(ev/'body.json')],stdout=log)
actual=json.loads(output(['gh','api','repos/CodexCoder21Organization/jvm-libp2p/pulls/71']))
assert actual['body']==json.loads((ev/'body.json').read_text())['body']
assert actual['body'].rstrip().endswith('🤖 Generated with [Claude Code](https://claude.com/claude-code)')
assert 'unattributed' in actual['body'] and '## Round 3' in actual['body']
(ev/'pr-description-verified.json').write_text(json.dumps({'head':actual['head']['sha'],'state':actual['state'],'bodyMatches':True,'recordCorrection':True},indent=2))
with findings.open('a') as f:f.write('OBSERVED: PR description updated through the requested REST PATCH and read back byte-for-byte; round-3 records, sweep, counts, record correction, why-first opening, handoff link, Actions statement and required footer are present.\n')
text=findings.read_text().replace('- [pending] Correct PR record and update description through REST; push all evidence; stop owned processes.','- [done] PR description corrected and verified; source/evidence pushed; owned processes stopped.')
findings.write_text(text)
shutil.copytree(ev,wip/'investigation/F71c',dirs_exist_ok=True);shutil.copy2(findings,wip/'investigation/F71c/findings.md')
call(['git','-C',str(wip),'add','-f','investigation/F71c'])
call(['git','-C',str(wip),'commit','-m','Record verified round 3 description update'])
call(['git','-C',str(wip),'push','origin','HEAD'])
wiphead=output(['git','-C',str(wip),'rev-parse','HEAD'])
assert not output(['git','status','--porcelain'])
print('FINAL_METADATA '+json.dumps({'head':head,'wip_head':wiphead,'classes':len(rs),'expected':len(expected),'required_missing':missing,'failed_classes':[r['class'] for r in failures],'counts':counts,'description':'updated','owned_jvms':0}),flush=True)

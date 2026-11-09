from pathlib import Path
import subprocess,sys,json,shutil,signal
from datetime import datetime,timezone
def stop(signum, frame): raise SystemExit(128+signum)
signal.signal(signal.SIGTERM, stop)
root=Path.cwd(); ev=root/'lane-evidence/fix-libp2p-cancel-flake'; src=root/'libp2p/src/test/kotlin/io/libp2p/network'
def note(kind,txt):
 with (root.parent/'fix-libp2p-cancel-flake-findings.md').open('a') as f: f.write(datetime.now(timezone.utc).isoformat()+' '+kind+': '+txt+'\n')
def run(*cmd,**kw): return subprocess.run(cmd,check=True,**kw)
def checkpoint(message): run('python3','.lane-scratch/checkpoint.py',message)
def summary(name): return json.loads((ev/name/'summary.json').read_text())
def record(name):
 s=summary(name); note('OBSERVED', f'{name}: FIRST {s["passed"]}/{s["total"]}, failed={s["failed"]}, skipped={s["skipped"]}; native XML and console at lane-evidence/fix-libp2p-cancel-flake/{name}/. No retries.')
 return s
def build(name,selectors,parallel=False):
 run('df','-h','/code')
 if shutil.disk_usage('/code').free < 8*1024**3: sys.exit('Less than 8 GiB free; stopping heavy work')
 run('git','fetch','origin','develop'); run('git','rebase','--autostash','origin/develop')
 cmd=['timeout','900','./gradlew','--no-daemon','--max-workers=2']
 if parallel: cmd+=['-I','.lane-scratch/parallel.gradle']
 cmd+=[':libp2p:test']
 for selector in selectors: cmd+=['--tests',selector]
 cmd+=['--rerun']
 with (root/'.lane-scratch'/f'{name}.log').open('w') as out: result=subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT)
 run('python3','.lane-scratch/capture.py',name,stdout=subprocess.DEVNULL)
 s=record(name)
 print(name,result.returncode,{k:s[k] for k in ['passed','failed','skipped','total']},flush=True)
 return result.returncode,s
rc,s=build('sibling-ordering-red',['io.libp2p.network.NetworkProvisionalCancellationSiblingOrderingEvidenceTest'],True)
if rc!=1 or s['total']!=2 or s['failed']!=2 or not all('expected: <1> but was: <0>' in r.get('message','') for r in s['cases']):
 checkpoint('Preserve sibling ordering experiment'); sys.exit('Sibling ordering did not reproduce the exact count assertion')
checkpoint('Preserve sibling responder ordering failure before correction')
run('python3','.lane-scratch/apply-sibling-wait.py')
selectors=['io.libp2p.network.NetworkProvisionalCancellationBeforePublicationTest','io.libp2p.network.NetworkProvisionalMplexCancellationBeforePublicationTest','io.libp2p.network.NetworkProvisionalCancellationSiblingTest','io.libp2p.network.*Cancellation*EvidenceTest']
rc,s=build('corrected-green',selectors,True)
if rc or s['total']!=66 or s['passed']!=66:
 checkpoint('Preserve callback ordering correction results'); sys.exit('The corrected register did not pass all sixty-six cases')
with (ev/'causal-waits-final.patch').open('w') as out: run('git','diff',stdout=out)
checkpoint('Preserve passing responder callback register')
originals=['NetworkProvisionalCancellationBeforePublicationTest.kt','NetworkProvisionalMplexCancellationBeforePublicationTest.kt','NetworkProvisionalCancellationSiblingTest.kt']
for name in originals:
 (src/name).write_text(subprocess.check_output(['git','show','a926ff23:'+str((src/name).relative_to(root))],text=True))
for p in (root/'.lane-scratch').glob('*EvidenceTest.kt'): shutil.copyfile(p,src/p.name)
rc,s=build('reversal',['io.libp2p.network.*Cancellation*OrderingEvidenceTest'],True)
if rc!=1 or s['total']!=22 or s['failed']!=22 or not all('expected: <1> but was: <0>' in r.get('message','') for r in s['cases']):
 checkpoint('Preserve callback ordering reversal results'); sys.exit('Reversal did not reproduce all twenty-two exact count assertions')
checkpoint('Preserve repeated failure after reversing responder waits')
run('python3','.lane-scratch/apply-causal-waits.py'); run('python3','.lane-scratch/apply-sibling-wait.py')
mux=root/'libp2p/src/main/kotlin/io/libp2p/mux/MuxHandler.kt'; saved=mux.read_text()
try:
 mux.write_text(subprocess.check_output(['git','show','origin/develop:'+str(mux.relative_to(root))],text=True))
 rc,s=build('cancellation-control',selectors[:3])
 if rc!=1 or s['total']!=4 or s['failed']!=4 or any('AssertionFailedError' not in r.get('message','') for r in s['cases']):
  checkpoint('Preserve prior cancellation implementation controls'); sys.exit('Corrected scenarios did not all reject the prior cancellation implementation')
finally: mux.write_text(saved)
note('OBSERVED','Corrected scenarios reject the prior production cancellation implementation FIRST 0/4. All earlier assertions remain, and the sibling fixture adds a zero-start assertion before the later child. MuxHandler restored byte-for-byte after this control.')
for p in src.glob('*Cancellation*EvidenceTest.kt'): p.unlink()
run('git','fetch','origin','develop'); run('git','rebase','--autostash','origin/develop')
with (root/'.lane-scratch/format.log').open('w') as out: run('timeout','600','./gradlew','--no-daemon','--max-workers=2',':libp2p:spotlessKotlinApply',stdout=out,stderr=subprocess.STDOUT)
for name in originals: run('git','add',str((src/name).relative_to(root)))
run('git','commit','-m','Proposed test change: wait for later responder callbacks\n\nInitiator readiness does not guarantee the peer responder callback has run. Wait for that callback before the exact count and child closure, preserving the original assertions and bounds. Check zero cancelled responders before opening the later sibling.\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>')
sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(); candidate='wip/fix-libp2p-cancel-flake-candidate-'+sha[:8]
run('git','branch',candidate,sha); run('git','push','origin',candidate)
(ev/'candidate.txt').write_text(sha+'\nhttps://github.com/CodexCoder21Organization/jvm-libp2p/tree/'+candidate+'\n')
note('OBSERVED','Candidate '+sha+' saved at https://github.com/CodexCoder21Organization/jvm-libp2p/tree/'+candidate+' before the full module register. Three scenario files only; no production edit or artifact publication.')
checkpoint('Preserve responder ordering candidate before full native register')
run('df','-h','/code')
if shutil.disk_usage('/code').free < 8*1024**3: sys.exit('Less than 8 GiB free; stopping heavy work')
run('git','fetch','origin','develop'); run('git','rebase','origin/develop')
cmd=['timeout','900','python3','.github/scripts/kubo-node.py','run',str(root/'.lane-scratch/kubo/ipfs'),str(root/'.lane-scratch/kubo.log'),'libp2p/build/kubo-node.properties','./gradlew','--no-daemon','--max-workers=2','--continue',':libp2p:test','--rerun','spotlessCheck','detekt']
with (root/'.lane-scratch/full-native.log').open('w') as out: result=subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT)
run('python3','.lane-scratch/capture.py','full-native',stdout=subprocess.DEVNULL)
s=record('full-native'); shutil.copyfile(root/'.lane-scratch/kubo.log',ev/'kubo.log')
note('OBSERVED','Full native module register plus spotlessCheck and detekt command exit='+str(result.returncode)+'. Owned Kubo node managed by the repository helper. No native reruns or retry plugin.')
checkpoint('Preserve full native module and static check results')
print('RESUME_COMPLETE candidate='+sha+' exit='+str(result.returncode),flush=True)
sys.exit(result.returncode)

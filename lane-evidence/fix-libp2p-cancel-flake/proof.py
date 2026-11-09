from pathlib import Path
import subprocess,sys,json,shutil
from datetime import datetime,timezone
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
 run('git','fetch','origin','develop'); run('git','rebase','--autostash','origin/develop')
 cmd=['timeout','900','./gradlew','--no-daemon','--max-workers=2']
 if parallel: cmd+=['-I','.lane-scratch/parallel.gradle']
 cmd+=[':libp2p:test']
 for selector in selectors: cmd+=['--tests',selector]
 cmd+=['--rerun-tasks']
 with (root/'.lane-scratch'/f'{name}.log').open('w') as out: result=subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT)
 run('python3','.lane-scratch/capture.py',name,stdout=subprocess.DEVNULL)
 s=record(name)
 print(name,result.returncode,{k:s[k] for k in ['passed','failed','skipped','total']},flush=True)
 return result.returncode,s
s=record('repetition'); s=record('forced-red')
if not (s['total']==20 and s['failed']==20 and all('expected: <1> but was: <0>' in row.get('message','') for row in s['cases'])):
 checkpoint('Preserve ordering results requiring further analysis'); sys.exit('Forced ordering did not reach the intended assertion in all twenty attempts')
note('INFER','Classification (b), a test-ordering defect. Negotiator.kt:153 sends acceptance before ProtocolSelect.kt:84-88 installs the responder binding; the test at lines 148-151 waits only for the local controller, closes the child, then reads the peer counter. Holding only the public responder binding reproduces the CI exact count (one expected, zero observed) in every forced attempt while local readiness succeeds. No production cancellation edit is needed.')
checkpoint('Preserve deterministic responder count failure before correction')
run('python3','.lane-scratch/apply-causal-waits.py')
with (ev/'causal-waits.patch').open('w') as out: run('git','diff',stdout=out)
selectors=['io.libp2p.network.NetworkProvisionalCancellationBeforePublicationTest','io.libp2p.network.NetworkProvisionalMplexCancellationBeforePublicationTest','io.libp2p.network.NetworkProvisionalCancellationSiblingTest','io.libp2p.network.*Cancellation*EvidenceTest']
rc,s=build('green',selectors,True)
if rc or s['total']!=64 or s['passed']!=64:
 checkpoint('Preserve first causal ordering candidate results'); sys.exit('Causal ordering register did not pass all sixty-four cases')
checkpoint('Preserve passing causal ordering register')
originals=['NetworkProvisionalCancellationBeforePublicationTest.kt','NetworkProvisionalMplexCancellationBeforePublicationTest.kt','NetworkProvisionalCancellationSiblingTest.kt']
for name in originals:
 (src/name).write_text(subprocess.check_output(['git','show','HEAD:'+str((src/name).relative_to(root))],text=True))
for p in (root/'.lane-scratch').glob('*EvidenceTest.kt'): shutil.copyfile(p,src/p.name)
rc,s=build('reversal',['io.libp2p.network.*CancellationOrderingEvidenceTest'],True)
if rc!=1 or s['total']!=20 or s['failed']!=20 or not all('expected: <1> but was: <0>' in row.get('message','') for row in s['cases']):
 checkpoint('Preserve causal ordering reversal results'); sys.exit('Reversal did not repeat the exact zero-count assertion twenty times')
checkpoint('Preserve repeated failure after reversing causal waits')
run('python3','.lane-scratch/apply-causal-waits.py')
mux=root/'libp2p/src/main/kotlin/io/libp2p/mux/MuxHandler.kt'; saved=mux.read_text()
try:
 mux.write_text(subprocess.check_output(['git','show','origin/develop:'+str(mux.relative_to(root))],text=True))
 rc,s=build('cancellation-control',selectors[:3])
 if rc!=1 or s['total']!=4 or s['failed']!=4 or any('AssertionFailedError' not in row.get('message','') for row in s['cases']):
  checkpoint('Preserve cancellation control results'); sys.exit('Corrected scenarios did not all reject the prior cancellation implementation')
finally: mux.write_text(saved)
note('OBSERVED','The corrected scenarios still reject the prior production cancellation implementation, FIRST 0/4. This checks that the added waits did not remove their original cancellation coverage. MuxHandler restored byte-for-byte afterwards.')
for p in src.glob('*Cancellation*EvidenceTest.kt'): p.unlink()
run('git','fetch','origin','develop'); run('git','rebase','--autostash','origin/develop')
with (root/'.lane-scratch/format.log').open('w') as out: run('timeout','600','./gradlew','--no-daemon','--max-workers=2',':libp2p:spotlessKotlinApply',stdout=out,stderr=subprocess.STDOUT)
for name in originals: run('git','add',str((src/name).relative_to(root)))
run('git','commit','-m','Proposed test change: wait for each later responder to start\n\nThe initiator controller can complete before the peer responder callback. Order the exact responder counts and child closure after responder readiness, preserving cancellation and sibling checks.\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>')
sha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(); candidate='wip/fix-libp2p-cancel-flake-candidate-'+sha[:8]
run('git','branch',candidate,sha); run('git','push','origin',candidate)
(ev/'candidate.txt').write_text(sha+'\nhttps://github.com/CodexCoder21Organization/jvm-libp2p/tree/'+candidate+'\n')
note('OBSERVED','Candidate '+sha+' saved at https://github.com/CodexCoder21Organization/jvm-libp2p/tree/'+candidate+' before the full module register. Only the three scenario files are committed; no production change or publication. Full module register and static checks remain pending.')
checkpoint('Preserve corrected scenario candidate before full register')
print('PROOF_COMPLETE candidate='+sha,flush=True)

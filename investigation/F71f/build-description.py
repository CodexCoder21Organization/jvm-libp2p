from pathlib import Path
import json,datetime
w=Path(__file__).resolve().parent;ev=w/'evidence/F71f';branch='https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/pr71-20261024';blob='https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024'
base=(w/'pr-before.md').read_text();closing='🤖 Generated with [Claude Code](https://claude.com/claude-code)';assert base.rstrip().endswith(closing);base=base.rstrip()[:-len(closing)].rstrip()
for kind in ['tree','blob']:
 base=base.replace(f'{kind}/efa10e9731b174191f424d2894078cef2ed95ce0/investigation/F71e',f'{kind}/evidence/pr71-20261024/investigation/F71e')
 base=base.replace(f'{kind}/wip/F71c-20261024/investigation/F71c',f'{kind}/evidence/pr71-20261024/investigation/F71c')
rows=[]
for line in (w/'evidence-branch/investigation/F71e/register-table.md').read_text().splitlines():
 if not line.startswith('|') or 'Class' in line or '---' in line:continue
 a=[s.strip() for s in line.strip('|').split('|')];rows.append(a)
off=json.loads((ev/'off-loop-first/result.json').read_text())['suites'][0];ms=lambda d:round(float(d['time'])*1000)
if not any(a[0]=='NoiseHandshakeOffEventLoopTest' for a in rows):rows.append(['NoiseHandshakeOffEventLoopTest','—','—','—','—'])
for a in rows:
 name=a[0];extra='—'
 if name=='NoiseBindingReadinessTest':
  runs=[json.loads((ev/f'{n}/result.json').read_text())['suites'][0] for n in ['alone-1','alone-2','alone-3','pair-1-binding','pair-2-binding','pair-3-binding']]
  extra='6 attempts, each 3/0/0; '+', '.join(str(ms(d)) for d in runs)+' ms'
 if name=='NoiseAlgorithmReadinessTest':
  runs=[json.loads((ev/f'pair-{i}-algorithm/result.json').read_text())['suites'][0] for i in (1,2,3)]
  extra='3 attempts, each 1/0/0; '+', '.join(str(ms(d)) for d in runs)+' ms'
 if name=='NoiseHandshakeOffEventLoopTest':
  a[1:]=[off['tests'],off['failures'],off['errors'],str(ms(off))];extra='First attempt completed in round 5'
 a.append(extra)
table='| Class | First-attempt tests | Failures | Errors | XML ms | Round 5 reproduction attempts (tests/failures/errors; XML ms) |\n|---|---:|---:|---:|---:|---|\n'+'\n'.join('| '+' | '.join(a)+' |' for a in rows)
(ev/'register-combined.md').write_text(table+'\n')
mechanism='Unknown: the original cold child spent 19657ms before READY and had not negotiated its first stream by the 30-second aggregate deadline, but the original record and six successful reproduction attempts do not identify the cause of that delay.'
section=f'''## Round 5

The owner requested investigation of the one round-4 first-attempt readiness failure, execution of the missed class, and removal of investigation records from the landing tree. This round preserves the original failure and all reproduction attempts; it makes no production or test-source changes.

**Item 1 — NOT_REACHED; mechanism unknown.** {mechanism} READY is a probe-start marker emitted before host construction, rather than handshake readiness. The old record reaches host construction at 22049ms and startup at 23758ms, then has no stream-negotiated marker before the aggregate guard fires. The settlement-before-termination change needs close/shutdown, which had not started. Retained delivery needs a rejected child-loop completion dispatch; the old record shows no such rejection. Neither observation proves the cause of the pending stream, and no claim that this is pre-existing is made. No speculative fix was applied.

Three isolated class attempts passed **9/9** checks. Three paired-command attempts with `NoiseAlgorithmReadinessTest`, which shares the cold-child probe and transport fixture, passed **9/9** readiness checks and **3/3** companion checks. Pair 1 had no test-body overlap because one Gradle daemon started later; pairs 2 and 3 had verified overlap of **12200ms** and **13832ms**. All six readiness attempts passed **18/18**, with zero failures/errors/skips. The 30-second aggregate deadline and every assertion and iteration count are unchanged. These passes do not erase or explain the [original failure and full stack]({blob}/investigation/F71e/register-NoiseBindingReadinessTest/full-failure.txt).

Reproduction entry commands (each selected class has its own Gradle command):

```bash
python3 workspace/run-one.py alone-1 NoiseBindingReadinessTest
python3 workspace/run-one.py alone-2 NoiseBindingReadinessTest
python3 workspace/run-one.py alone-3 NoiseBindingReadinessTest
python3 workspace/run-pairs.py
```

The isolated attempts use `:libp2p:test --tests io.libp2p.security.noise.NoiseBindingReadinessTest`. Paired commands use two isolated standard Gradle Test projects with the original task's captured class directories, runtime classpath, JVM defaults, working directory, JUnit configuration and single-fork setting. They run the freshly compiled classes from this checkout; no compiled outputs are copied. Every command uses the private workspace Gradle home, `--offline` and `--max-workers=2`. Maximum concurrent test commands observed: **2**. [Exact commands, exit codes, XML, class settings and observations]({branch}/investigation/F71f).

**Item 2 — first attempt PASS.** `NoiseHandshakeOffEventLoopTest` passed **29 tests, 0 failures, 0 errors, 0 skips; 50682ms XML** on `efa10e9731b174191f424d2894078cef2ed95ce0`, with no concurrent test command. Entry command: `python3 workspace/run-one.py off-loop-first NoiseHandshakeOffEventLoopTest`. It uses the original `:libp2p:test` task. [Result and exact command]({blob}/investigation/F71f/off-loop-first/result.json), [XML]({blob}/investigation/F71f/off-loop-first/TEST-io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.xml).

**Item 3 — evidence moved.** [The evidence branch]({branch}) retains round 4 unchanged, recovers `investigation/F71c/` from its historical branch (it was already absent from the starting PR tree), and preserves `.evidence/FURR3/`. Git tree equality verifies all three copies. [The cleanup commit](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/3e1751baddc1e37fc40c51ff1e28b9135c6acc23) removes 205 evidence files and nothing else. The PR tree is clean of evidence directories. All this round's records are on the evidence branch. [Preservation audit]({blob}/investigation/F71f/evidence-preservation.json), [PR tree/source audit]({blob}/investigation/F71f/pr-tree-audit.json).

Source, tests, fixtures and build inputs at the cleanup head are identical to the tested head. Existing-test edit list (file, before, after, justification): **none**. No test, assertion, timeout or iteration count was changed, skipped, weakened or deleted. No reflection or mocks were added. The round-4 formatter result still applies because no formatter input changed.

Combined register: **26/26 classes attempted; 144/145 checks passed on their recorded first attempt**. The single original readiness failure remains visible below. Round-5 diagnostic attempts are separate columns and are not substituted for that failure. The missed class's first attempt is now folded into the register. This is not a claim of a clean register or a known readiness mechanism.

{table}

The private-home `./gradlew --stop` completed, and the owned-process audit found no remaining Gradle/compiler/test/probe JVM. This round did not merge, approve, close, enqueue, publish or deploy. The PR remains open for the orchestrator's re-reviews and merge decision. [Full round-5 findings]({blob}/investigation/F71f/findings.md).
'''
body=base+'\n\n'+section+'\n'+closing+'\n';assert body.startswith((w/'pr-before.md').read_text().split('\n\n')[0]);assert 'Handoff: https://www.handoff.wasmserver.com/' in body;assert body.count(closing)==1
(w/'pr-description.md').write_text(body);(w/'pr-patch.json').write_text(json.dumps({'body':body}));print({'chars':len(body),'register_rows':len(rows),'total_tests':sum(int(a[1]) for a in rows),'total_failures':sum(int(a[2]) for a in rows)})

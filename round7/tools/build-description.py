from pathlib import Path
import json,subprocess
w=Path(__file__).resolve().parent;r=w.parent/'round7'
p=json.loads((r/'pr-description-baseline.json').read_text());body=p['body']
footer='🤖 Generated with [Claude Code](https://claude.com/claude-code)'
assert body.rstrip().endswith(footer)
assert '\n## Round 7\n' not in body
original=body[:body.rfind(footer)].rstrip()
evidence=subprocess.check_output(['git','rev-parse','HEAD'],cwd=w/'evidence-branch',text=True).strip()
root='https://github.com/CodexCoder21Organization/jvm-libp2p'
ev=root+'/blob/'+evidence+'/round7/'
section=f'''## Round 7

The owner requested accepted unflushed writes be settled even after an earlier parent delivery has finished, and requested investigation of the first-attempt stream-open timeout recorded by the coverage reviewer. This round continues the handoff above and leaves the PR open for the orchestrator's final review.

### Accepted unflushed writes after completed parent delivery

The child now keeps termination ownership while its outbound buffer retains accepted writes, independently of the pending parent result. Normal buffer drain and close retire that ownership. Shutdown uses the existing settle-before-termination path, preserving a completed parent result before failing remaining writes. Callback close is rechecked before installing ownership. Cancelled unflushed promises keep cancellation while their retained payloads are released.

- [Regression test commit]({root}/commit/5dfaaff7c36d50c664f46ceda76852f04637b5bc): four public-API cases, with/without earlier foreign-executor parent delivery, each pending/caller-cancelled, with refused child close and a terminated-owner barrier.
- Red on the requested head: 4/4 assertion failures, XML 3222 ms. Pending cases: `An accepted unflushed write after earlier delivery must settle before owner termination ==> expected: <true> but was: <false>`. Cancelled cases: `Termination must release the accepted payload exactly once ==> expected: <0> but was: <1>`. [XML]({ev}item1-red/TEST-io.libp2p.transport.UnflushedWriteTerminationTest.xml), [complete failure traces]({ev}item1-red/full-failure.txt).
- [Fix commit]({root}/commit/4320368aae65b86d2e5defd3232e09cc17c3db01): first fixed attempt 4/4 pass, XML 8116 ms. [XML]({ev}item1-green/TEST-io.libp2p.transport.UnflushedWriteTerminationTest.xml).
- One production reversal: the same four assertions fail, XML 4299 ms. [XML]({ev}item1-reversal/TEST-io.libp2p.transport.UnflushedWriteTerminationTest.xml), [complete traces]({ev}item1-reversal/full-failure.txt). The fixed source was restored byte-for-byte before the five-class register; [restoration record]({ev}restoration.json).

Every requested shutdown class passed its first restored-head attempt:

| Class | Checks / failures | XML ms |
|---|---:|---:|
| ReentrantChildWriteCloseTest | 1 / 0 | 5777 |
| OffLoopParentWriteNotificationTest | 6 / 0 | 64492 |
| ReviewTerminalWriteConversionTest | 4 / 0 | 92497 |
| ReviewCompletedShutdownTest | 6 / 0 | 46795 |
| ReviewChildLoopAffinityTest | 3 / 0 | 28636 |

### First-attempt stream-open timeout

Stream open waits for the controller future after TCP/Noise connection setup, mux activation, child registration and protocol selection; negotiation writes traverse the changed MuxChannel path, but the historical record captures no pending phase, rejected dispatch or shutdown, so the cause of its five-second wait is unknown. The held transport and queue saturation are installed only after this wait. The recovered reviewer stack is [preserved here]({ev}reviewer-stream-failure-transcript.txt).

The historical timeout was **NOT_REACHED** in 36/36 target checks. These are planned reproduction attempts, not proof of a timeout fix:

| Attempt | Target pass / total | Companion pass / total | XML ms, target / companion |
|---|---:|---:|---:|
| Solo 1 | 6 / 6 | — | 29458 / — |
| Solo 2 | 6 / 6 | — | 25373 / — |
| Solo 3 | 6 / 6 | — | 21016 / — |
| First pair, command overlap only | 6 / 6 | 1 / 2 | 33213 / 10529 |
| Second pair, command overlap only | 6 / 6 | 2 / 2 | 94040 / 26475 |
| Coordinated pair, actual fixture overlap | 6 / 6 | 2 / 2 | 92027 / 33687 |

The first two pairs' test bodies did not overlap because a Gradle daemon restarted. The final pair used a bounded TCP launch barrier outside the repository's tests, before either test task started, and achieved [30.401 seconds of test-body overlap]({ev}pair-overlap.json). Test source, deadlines, iterations, assertions and runtime inputs were unchanged. Reproduction commands were `python3 workspace/run-stream-open.py`, `python3 workspace/run-pair.py`, `python3 workspace/run-pair-warm.py`, and `python3 workspace/run-pair-barrier.py`; each result directory records the exact one-class Gradle command.

The companion's first failure is retained separately: Mplex rejected a filler task at `CompletedStreamWriteNotificationTest.kt:230`, before reset assertions. [Complete failure trace]({ev}pair-companion/full-failure.txt). **PRE_EXISTING fixture condition:** its fixed 2048 queue-fill calls assume all slots in the shared loop queue are free. A [deterministic public-Netty-API counterexample]({ev}queue-probe/CapacityQueueOccupancyProbe.java) with one queued completion barrier admits only 2047 fillers while the owner is live; [output]({ev}queue-probe/run.log), [commands]({ev}queue-probe/results.json). The original extra task's identity was not recorded. Proper isolation requires control of all producers of the shared transport loop; reducing the existing filler count is forbidden. Existing tests were left unchanged, and later passes are not presented as fixing this failure.

### README and review audit

[README commit]({root}/commit/01c16ae9db8c1aa09a5b1cd0fa040c4bbd32f018) removes the pending-parent-hook restriction and the exclusion for accepted unflushed writes after completed delivery. It states the verified worker-loop settlement behavior, including refused child closure.

Edits to existing test files: **none**. The only added PR test file is `UnflushedWriteTerminationTest.java`. Java formatting changed only its layout between the initial red record and its test commit. No timeout was raised, iteration reduced, or test/assertion weakened, skipped or deleted; no reflection or mocks were added. Dedicated test-comprehensiveness and code-review passes are recorded in [the findings]({ev}findings.md); no new external review approval is claimed. Kotlin/Java formatting checks and whitespace checks passed.

[Final source audit]({ev}final-source-audit.json), [all command intervals]({ev}command-concurrency-audit.json): 17 terminal Gradle test commands, **MAX_CONCURRENT_COMMANDS_OBSERVED: 2**. The private-home Gradle stop completed and [no owned JVM remained]({ev}cleanup-after-stop.json). Evidence remains on `evidence/pr71-20261024`, under `round7/`, outside the PR branch. Verification is local; no executed CI pass is claimed. No merge, approval, closure, publication or deployment was performed.
'''
new=original+'\n\n'+section.rstrip()+'\n\n'+footer+'\n'
assert new.startswith(original)
assert 'Handoff: https://www.handoff.wasmserver.com/handoffs/' in new
assert new.count(footer)==1
(r/'round7-description-section.md').write_text(section)
(r/'description-payload.json').write_text(json.dumps({'body':new},ensure_ascii=False))
(r/'description-body.md').write_text(new)
(r/'description-source-evidence.json').write_text(json.dumps({'evidence_commit':evidence,'opening_and_history_preserved':True,'footer_count':new.count(footer)},indent=2))
print({'evidence':evidence,'description_bytes':len(new.encode()),'new_section_bytes':len(section.encode())})

from pathlib import Path
import json,subprocess,datetime
w=Path(__file__).resolve().parent;e=w/'round8';repo=w/'jvm-libp2p';base='https://github.com/CodexCoder21Organization/jvm-libp2p';ev=base+'/blob/evidence/pr71-20261024/round8/'
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
def result(label):return json.loads((e/label/'result.json').read_text())
def score(label):
 r=result(label);n=sum(int(s['tests']) for s in r['suites']);f=sum(int(s['failures'])+int(s['errors']) for s in r['suites']);ms=round(sum(float(s['time']) for s in r['suites'])*1000);return f'{n-f}/{n}',ms
def link(label):return f'[record]({ev+label}/result.json)'
rows=[]
for title,red,green,rev in [('Zero-byte unflushed/refill entries','item1-red','item1-green','item1-reversal'),('Queued parent completion after shutdown starts','queued-completion-red','queued-completion-green','queued-completion-reversal'),('Queue already contains an ordinary task','queue-occupancy-expanded-red','queue-occupancy-green','queue-occupancy-reversal'),('Retired hook in the captured shutdown batch','stale-batch-red','stale-batch-green','stale-batch-reversal'),('Foreign completion with full owner queue','foreign-owner-direct-red','foreign-owner-direct-green','foreign-owner-direct-reversal')]:
 vals=[]
 for label in [red,green,rev]:
  s,ms=score(label);vals.append(f'{s}, {ms} ms ({link(label)})')
 rows.append('| '+title+' | '+' | '.join(vals)+' |')
shutdown=['ReentrantChildWriteCloseTest','OffLoopParentWriteNotificationTest','ReviewTerminalWriteConversionTest','ReviewCompletedShutdownTest','ReviewChildLoopAffinityTest']
register=[]
for cls in shutdown:
 label='final-'+cls
 if (e/label/'result.json').exists():
  s,ms=score(label);register.append(f'| {cls} | {s} | {ms} | {link(label)} |')
 else:register.append(f'| {cls} | NOT_COMPLETED | — | — |')
noise=[]
for label in ['noise-alone-1','noise-alone-2','noise-pair','noise-final-pair']:
 if not (e/label/'result.json').exists():continue
 s,ms=score(label);load=(e/label/'load-before.txt').read_text().split()[:3]
 noise.append(f'| {label} | {s} | {ms} | {" / ".join(load)} | {link(label)} |')
section='''## Round 8

The owner requested the zero-length ownership fix, diagnostic removal and ping-latency investigation. This continues the handoff above and leaves the PR open.

Termination ownership now follows retained entries independently of their byte accounting. Netty 4.2.10 `ChannelOutboundBuffer.isEmpty()` counts **flushed** entries only; it cannot alone detect unflushed entries. Retirement therefore combines that public API with an atomic count of private buffer promises, whose terminal results are forwarded directly to the caller. Caller cancellation does not retire an unflushed payload. One shutdown hook per child owns accepted entries. Owner-loop cleanup handles foreign completion; retries coalesce and cancel on retirement/close.

The zero-overhead child JVM pins `io.netty.transport.outboundBufferEntrySizeOverhead=0` before Netty loads. Both direct unflushed and current-batch refill cases fail on the original head, including cancelled promises. Red assertions: `An accepted zero-byte unflushed write must settle before owner termination: refillInBatch=false cancelled=false ownerTerminated=true acceptedDone=false payloadRefCnt=1 ==> expected: <true> but was: <false>` (6993 ms), and `A zero-byte shutdown-batch refill must settle before owner termination: refillInBatch=true cancelled=false ownerTerminated=true acceptedDone=false payloadRefCnt=1 ==> expected: <true> but was: <false>` (5954 ms). Cancelled cases fail their payload-release assertion. Fixed source was restored after reversal.

The first five-class register caught another ordering bug: a queued parent-completion task could run after shutdown began and send the next accepted write before the hook ran. It now preserves the completed parent outcome and settles remaining writes through the termination path. A dedicated ordering test forces success/failure/cancellation before releasing the shutting-down owner. An independent review also exposed foreign-thread retirement; its corrected reproducer admits the promise on the owner and completes it on another executor while the queue is full. The earlier executor-bridge probe is preserved as investigation only.

| Case | Red pass/total, XML ms | Green pass/total, XML ms | Reversal pass/total, XML ms |
| --- | --- | --- | --- |
'''+ '\n'.join(rows)+f'''

Code: [zero-byte tests]({base}/commit/385adbf24PLACEHOLDER), [entry ownership fix]({base}/commit/49cf9c4fbc68d356ddfe9be93da8a0f74aff5104), [queued-completion fix]({base}/commit/3003af0ebf4618484f5dec406e58d200a4c23bd1), [foreign retirement fix]({base}/commit/213dba593d2e17cd52b5973a23113934f4bf7323). [Buffer API evidence]({ev}reference/ChannelOutboundBuffer-javap.txt).

The original five-class first attempts were 4/5 classes and 19/20 checks; the cancellation case in ReviewTerminalWriteConversionTest failed `The public stream write must report termination failure`. That record remains preserved. Final-source attempts at `{head}`:

| Class | Pass/total | XML ms | Record |
| --- | --- | ---: | --- |
'''+ '\n'.join(register)+'''

### Ping latency

**NOT_REACHED: causal mechanism remains unknown.** The required three planned class attempts passed 83/87 checks. Solo 2 reproduced the unchanged p95 guard at 586 ms against 500 ms, with all 322 counted handshakes and 644 crypto tasks offloaded and zero crypto tasks on the event loop. Solo 1 failed the unchanged 10-second inline-child and 15-second latency-child process budgets; the paired attempt failed the 15-second process budget before reporting a p95. Load alone is not attributed as the cause. The first pair overlapped test bodies for only 0.605259 seconds before the latency phase; a separately recorded final-source attempt starts its companion during sequential handshakes, before the latency phase. No failure is erased by later attempts. No equivalent baseline runtime result or PRE_EXISTING classification is claimed; the prepared baseline overlay only compiled. The readiness helper's historical shared 30-second budget remains separately recorded as PRE_EXISTING.

| Attempt | Pass/total | XML ms | Load before (1/5/15 min) | Record |
| --- | --- | ---: | --- | --- |
'''+ '\n'.join(noise)+f'''

### Coverage review and existing-test edits

`R71tc6-final.md` was absent at the start and at the final check. Its interim findings were folded: zero-byte cancellation is covered above; occupied-queue cases now cover capacity reset, rejected write, parent success and parent failure under both muxers; captured stale-hook behavior has a permanent test, including 64 drain/refill cycles and an estimator rejection. Their red/green/reversal controls are above. The diagnostic-print finding duplicates item 2 and is closed by the source edit and the passing original class.

Every edit to a test present at the required starting head:

| File | Before | After | Reason |
| --- | --- | --- | --- |
| UnflushedWriteTerminationTest.java | Printed earlier-delivery, owner termination, promise state and payload count before assertions. | Removed println; captured the same state in assertion messages; formatting only otherwise. | Requested diagnostic removal. |
| CompletedStreamWriteNotificationTest.kt | Assumed all 2048 fillers fit an initially empty queue; printed child output. | Keeps 2048 attempts, accounts for already occupied slots, asserts the queue is full and every admitted filler runs, retains the explicit rejection and all result/payload assertions; adds an ordinary-task setup mode for new regression cases; removes the print while keeping child output in the exit assertion. | Interim review reproduced rejection at filler 2047 when another task already occupied a slot; adjacent write modes shared the assumption. |

No timeout or iteration count was raised/reduced, and no test or assertion was weakened, skipped or deleted. No reflection, mocks or production visibility changes. Verification is local; enterprise Actions policy prevents runtime CI. No merge, enqueue, approval, closure, publication or deployment.

[Full findings, complete failure traces, commands and source-state records]({base}/tree/evidence/pr71-20261024/round8). Maximum concurrent Gradle test commands: 2.
'''
sha=subprocess.check_output(['git','rev-parse','385adbf2'],cwd=repo,text=True).strip();section=section.replace('385adbf24PLACEHOLDER',sha)
old=json.load(open('/tmp/F71i-pr.json'))['body'];footer='🤖 Generated with [Claude Code](https://claude.com/claude-code)';assert footer in old
body=old.rsplit(footer,1)[0].rstrip()+'\n\n'+section.rstrip()+'\n\n'+footer
assert len(body)<65536,len(body)
(e/'pr-body.md').write_text(body);(e/'pr-payload.json').write_text(json.dumps({'body':body},ensure_ascii=False));print('DESCRIPTION_CHARS',len(body))

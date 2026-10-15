# Lane 238 — fast message ID attribution

## 2026-10-15 14:08 UTC — plan
- IN PROGRESS: Read brief/common/reference docs, fresh clone, confirm exact PR head and no-op rebase.
- TODO: Read test/router/PR diff; record mechanism and invariant table before changes.
- TODO: Identical original-test trials on PR/develop; preserve native logs and failure stacks.
- TODO: Deterministically force suspected ordering on both heads through public API.
- TODO: Fix the demonstrated cause on own branch; mutation-check fast-ID assertions; fixed 10/10 and neighbours.
- TODO: Independent test/code review, REST PR, durable evidence, process/cache/checkout cleanup.

OBSERVED: Brief/common read. Target head 7a27e4890885592f60ad402af6af30b5578da364, base develop. No nested delegation, CI monitoring, merge, publication, deployment or Handoff service action permitted. New-work cutoff 15:22 UTC (75 minutes from 14:07 launch); hard wall 15:37 UTC. Findings incrementally written here.

## 14:10 UTC — exact source and candidate mechanism (before code)
OBSERVED: Fresh clone confirmed exact requested head 7a27e4890885592f60ad402af6af30b5578da364. Fetch/rebase on origin/develop was a no-op. README/FORK, common, handoff snapshot, lane229 final comparison, test, P2PService, AbstractRouter, SeenCache, peer stub and PR production diff read. Reference documents retrieved through authenticated GitHub: Testing architecture (TDD/public API/flake/reproducer sections), Engineering Philosophy, Code Review red flags, Systemic CI failures; fix-flakey-test and status-report skills read. No repo AGENTS.md exists.

INFER / PREDECLARED MECHANISM: The mechanism I am about to test is an absent completion barrier in testFastMessageId: runOnEventThread only enqueues inbound work; the duplicate intentionally never reaches messages, so poll(100ms) is not evidence inbound ran. createdMessages can still be empty when line 75 expects size 1. The evidence that would demonstrate this is a public event-thread gate keeping the duplicate queued while the unchanged poll/size assertion runs, then releasing the gate and observing one created message with null canonical ID, on both source states. A failure after a completed inbound future would refute this mechanism and require further product diagnosis.

| Resource/state | Creator / owner / completion | Caller observation / ordering |
| --- | --- | --- |
| Router event executor | Builder owns single event thread; runOnEventThread returns after enqueue, submitOnEventThread completes after lambda | Subscribe queues before inbound; completion future is the legitimate barrier |
| createdMessages and canonicalId | Message factory and fast-ID handling write on router thread | Main-thread reads/clear require inbound completion; queue delivery only orders writes before delivery |
| Delivery queue | Handler publishes unseen message; duplicate intentionally absent | A null timed poll cannot prove a queued duplicate has run |
| Peer stub | Test fixture holds no real mux/transport, closes completed futures | PR mux activation cannot be called through this test's fixture |

Plan: initial identical batches of 20 original-test trials on exact PR/develop; all outcomes retained. Then deterministic queue gate on both, fail-first checkpoint, fix/10-trial/mutation/neighbor verification. Compilation runs with JAVA_OPTS=-Xmx2g, Gradle no-daemon/one worker/in-process Kotlin, 900-second process-group bound. No code changed yet.

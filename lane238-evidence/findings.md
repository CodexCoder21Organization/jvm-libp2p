# Lane 238 — fast message ID attribution

## 2026-10-15 14:08 UTC — plan
- DONE: Read brief/common/reference docs, fresh clone, confirm exact PR head and no-op rebase.
- DONE: Read test/router/PR diff; record mechanism and invariant table before changes.
- IN PROGRESS: Identical original-test trials on PR/develop; preserve native logs and failure stacks.
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

## 14:14 UTC — compile and source checkpoint
OBSERVED: Cold PR testClasses completed exit 0 in 254.74 seconds. First evidence checkpoint pushed: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/8bad8628b (abbreviated URL pending full SHA capture; branch is https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane238-fast-message-id-20261015/lane238-evidence). PR REST read confirms OPEN at exact head, base develop. Diff of pubsub, P2PService, original test, peer stub and versions.gradle between heads is empty. The first batch of 20 unchanged PR-head trials is now running, one fork at a time. This is a fixed comparison, not a retry-until-green gate.
OBSERVED: Gradle no-daemon starts a single-use daemon in its own process group despite the launcher JAVA_OPTS. Subsequent wrapper tracks descendants and every owned process group and explicitly sets daemon heap -Xmx2g. Initial compile finished and its daemon exited normally; no overlapping lane build was started.

## 14:16 UTC — original PR sample and prior full stack
OBSERVED: Exact PR-head original test passes 20/20 fixed native Gradle Test tasks (20 fresh test JVMs, 77.22 seconds including Gradle startup). This sample does not reproduce or erase lane229's failure. Unchanged develop 3e88aeef now runs the same 20-task invocation and options; branch switch requires source recompilation, one warm workspace retained.
OBSERVED: Retrieved both failing native XML records from lane229 evidence: broader trial 3 and full half B. Both full failure stacks are:
```
org.opentest4j.AssertionFailedError:
expected: 1
 but was: 0
    at app//io.libp2p.pubsub.GossipApiTest.testFastMessageId(GossipApiTest.java:75)
    at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
    at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
    at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
```
Saved native XML locally and will push with the comparison evidence. The original assertion is size 1 after an intentionally empty duplicate delivery poll. INFER: This matches the candidate but prior XML has no task-completion marker, so it alone does not prove ordering. The controlled public-API gate remains the deciding experiment.
CORRECTION: Full first evidence commit URL is https://github.com/CodexCoder21Organization/jvm-libp2p/commit/8bad86281f6571f4e4de22eaca31f7b37fc63ccd .

## 14:17 UTC — planned correction shape, not yet applied
INFER / REVIEW CONSTRAINT: Merely replacing the duplicate's 100 ms empty poll with a 100 ms future wait would change the failure into a timeout when the event thread is held, while retaining an arbitrary per-duplicate scheduling deadline. The correction should instead run both sets of assertions after their respective onInbound calls on the router event thread, chain those two real completion futures, and await the entire chain using the existing first-message 1-second bound. This removes the invalid 100 ms silence-as-completion assumption without increasing any timeout or total wait budget. Keep separate queued tasks so the first message's queued validation continuation can run before the duplicate, as in the original test. Preserve every payload/count/canonical-ID assertion and the real executor; shut that executor down in finally. Will only implement after the deterministic failing evidence is recorded on both heads.

## 14:21 UTC — unchanged develop naturally reproduces original assertion
OBSERVED: Identical unchanged-develop 20-task batch completes with **16 passed / 4 failed / 0 skipped** (trials 5, 6, 12, 19), exit 1 in 299.90 seconds including recompilation. Each native XML stack is identical to lane229's PR failure: expected 1, actual 0 at GossipApiTest.java:75, followed by Method.invoke:580 and two ArrayList.forEach:1596 frames. PR-head sample is separately **20 passed / 0 failed / 0 skipped**. Native XML and complete command/output are preserved; no failing trial was rerun away.
INFER / ATTRIBUTION: The premise that this assertion requires PR 48 is refuted by four natural failures of the unchanged original test on exact develop 3e88aeef. The responsible code path is identical between heads and never instantiates a mux/transport. The narrower task of identifying the completion mechanism remains in progress; native controlled ordering probe now running on develop before any fix.
PLAN: Original comparison DONE. Deterministic public-API ordering probe IN PROGRESS. Fix, mutation, fixed trials, neighbours, independent reviews and PR remain TODO.

`testRejectedDrainLoggingDoesNotHoldOutboxMonitor` timed out during host stop in the required full-suite check for https://github.com/CodexCoder21Organization/UrlProtocol/pull/635. The first failure is recorded at https://github.com/CodexCoder21Organization/UrlProtocol/actions/runs/40085745929/job/120848195951; the owner requested local reproduction and a fix at its source. This work is part of the owner's request to finish open handoffs; jvm-libp2p is consumed by UrlProtocol.

Handoff: https://www.handoff.wasmserver.com/handoffs/hf-2026-09-24-make-kompile-cli-remote-runs-reliable-resume-chunked-uploads-from-the-failed-chunk-land-the-reconnect-fix-and-deploy-the-server-cpu-fix

A completed Netty promise could lose its conversion callback when its live event-loop queue rejected listener dispatch. The returned Java future then stayed pending after the queue recovered. Both converter overloads copy terminal results directly and recheck completion after listener registration; pending operations continue to use their normal listener executor.

The notification-site sweep also covers completed connection closes, stream writes and capacity resets. Completed connection closes transfer their result directly outside an active public close-future callback; callback reentry and thread handoffs preserve earlier physical-close listener ordering. Stream writes and capacity-reset results use the corrected converter. Parent-write completion stays on the child loop, including when a custom muxer returns a promise whose listeners use another executor. Rejected delivery retains the completed result, while close and shutdown cancel stale delivery and release pending payloads. Tests check original success/failure/cancellation, exact rejection messages, later writes and payload release.

Review round record (reviewed head `8eabea8649ac5bc301615dbea6a5f06ac8c477bc`):

- [Adversarial review](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71adv-final.md) ([full findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71adv-findings.md)): completed-close finding addressed by [the close fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ecafb3ab4b321b1b24be6c26b0c39356865f87e0) and [reentrant ordering guard](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/6bdb29896ff411d1beca2c434e77b742223e557a). Its two documentation findings are answered by [the coordinate correction](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6) and this description.
- [Coverage review](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71tc-final.md) ([full findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71tc-findings.md)): [four pending failure/cancellation cases](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/5a9a8550a6c8772f2c63f7139393ddfaf7fde5a5) now fail under the exact success-only listener mutation, then pass with the original converter restored. They check terminal state and original cause after real executor barriers.
- This resumed round: [stream-write sweep fix and six guards](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d6eca64f92fc1d1cc4d44b39a188afc98ae0abec), plus registration-race, queued shutdown, nullable generic success and caller-ownership cases. Separate local test-comprehensiveness and code-review passes are recorded in [the findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-findings.md); no new external review approval is claimed.

Round-2 registration audit (historical): 23 original notification sites; 21 existing converter/loop-owned guards and two corrected stream-write sites. [Site sweep](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-site-sweep.md), [converter state sweep](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-sweep.md).

Local controls:

- Close regression: original assertion-red 9608 ms; fixed 8359 ms; fix reversal red 9865 ms. This resumed lane also confirmed the original pushed head: close 1/1 (9522 ms), converters 6/6 (2880 ms).
- `stream-red`: 0/6 pass, 6 assertion/error results, 61338 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-red.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-red.log).
- `stream-green`: 6/6 pass, 0 assertion/error results, 81915 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-green.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-green.log).
- `pending-mutation`: 0/4 pass, 4 assertion/error results, 7019 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-mutation.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-mutation.log).
- `pending-green`: 4/4 pass, 0 assertion/error results, 5961 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-green.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-green.log).
- `state-mutation`: 10/14 pass, 4 assertion/error results, 9392 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-mutation.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-mutation.log).
- `state-green`: 14/14 pass, 0 assertion/error results, 9382 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-green.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-green.log).

First runtime attempt per class at final source head `2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6` (one class per bounded Gradle command; XML counts):

| Class | Pass / total | Fail / skip | XML ms |
|---|---:|---:|---:|
| BufferedHalfCloseFramesTest | 14/14 | 0/0 | 3655 |
| CompletedConnectionCloseNotificationTest | 1/1 | 0/0 | 3921 |
| CompletedNettyFutureConversionTest | 6/6 | 0/0 | 2340 |
| CompletedStreamWriteNotificationTest | 6/6 | 0/0 | 37054 |
| NettyFutureConversionStateTest | 14/14 | 0/0 | 1881 |
| NetworkPendingConnectTest | 11/11 | 0/0 | 3337 |
| NoiseBindingReadinessTest | 3/3 | 0/0 | 50733 |
| NoiseHandshakeTest | 6/6 | 0/0 | 2759 |
| NoisePreparationConcurrencyTest | 1/1 | 0/0 | 10190 |
| NoisePreparationFailureTest | 3/3 | 0/0 | 25832 |
| NoisePreparationLifecycleTest | 5/5 | 0/0 | 62652 |
| NoisePreparationTrafficTest | 4/4 | 0/0 | 37047 |
| PendingNettyFutureConversionTest | 4/4 | 0/0 | 2645 |
| ReentrantConnectionCloseNotificationTest | 1/1 | 0/0 | 2583 |
| YamuxPrefixFrameFailureTest | 1/1 | 0/0 | 1532 |

Both initial close and converter commands hit their unchanged 600-second outer limits during test-source compilation, before any test body or XML. Those first-command failures are retained, including their full daemon traces; they are not counted as passing runtime attempts. A separate bounded offline `compileTestKotlin` then succeeded in 7m14s. No failed test was rerun unchanged into green; the deliberately failing mutation/control runs above are recorded separately from restored-head validation.

[All command, XML and full failure records](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F71b2-20261024/investigation/F71b2). [Initial close daemon trace](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-initial-close-daemon.log); [initial converter daemon trace](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-initial-converter-daemon.log).

GitHub Actions has not provided executed-test verification for this fork; the verification evidence is local. Earlier startup failures were attributed to external-action policy. No workflow was rerun, requested or watched in this round.

No existing test files were edited: all test-file changes relative to `develop` are additions. No test timeout or iteration count was changed, and no test or assertion was weakened, skipped or deleted. No reflection or mocks were added.

`build.gradle.kts` assigns `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-41`; `FORK.md` now agrees and keeps assignment separate from publication. This lane did not merge, enqueue, publish or deploy.

Current final-head workflow records: [startup-failure record](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40126973159), [startup-failure record](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40126969161). Both completed before executing tests.

## Round 3

The owner requested the two fresh-context round-2 reviews' findings be closed test-first, with bounded Gradle commands, preserved first attempts, and no merge or publication. Those reviews examined `2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6`: [adversarial findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/R71adv2-findings.md), [test-comprehensiveness findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/R71tc2-findings.md). This section supersedes the earlier close/write guard claims where the new probes found gaps.

| Finding | Test / fix | Red assertion and XML ms | Green XML ms | Reversal red XML ms |
|---|---|---|---:|---:|
| Nested listener close | [test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/37b0c7fe99c30a57655f565ead9f4b3d3d91efd2), [fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/6eb0c3790882ad27e81b062f40d71d06c84a8d2b) | “A nested-listener close must complete after the event loop recovers”; 3964 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/nested-red.xml) | 3908 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/nested-green.xml) | 8865 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/nested-reversal.xml) |
| Parent result on another executor | [test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/9a271e6da33f707eaa19e422f9835948b9817046), [fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/1165d4e67dc66bd5c7fde51c8dec0049b5863dc7) | “A separate-executor parent result must settle its child write after queue recovery”; 19712 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/off-loop-red.xml) | 38035 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/termination-unflushed-deterministic-green.xml) | 55556 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/off-loop-reversal.xml) |
| Early close handed to another thread | [test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/fdcc28310ab4130de4362e04620d5fee193a7b7f), [fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d31c98ffa399b6d4fc149d8b080bf56f174e3a86) | “A reentrant close must finish after previously registered physical-close listeners”; 1983 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/off-thread-red.xml) | 2678 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/off-thread-green.xml) | 7696 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/off-thread-reversal.xml) |
| Additional sweep finding: capacity reset | [test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/832bd0983cff3c537c49ccba9a6b5e583b1ca872), [fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/e2cc20fe63d9f6412a8fbad752e0e967f6aea1e8) | “An already-closed stream must complete capacity reset after the event loop recovers”; 19736 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/capacity-red.xml) | 17674 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/capacity-green.xml) | 19051 [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/capacity-reversal.xml) |

The close handoff red observed `[early listener, close operation, later listener]`; green requires `[early listener, later listener, close operation]`. Close notification scope is tracked explicitly across threads, rather than inferred from arbitrary promise nesting.

The parent-write test first had three result cases (all three red). [Retained delivery](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d85e2afb538ea7c29ec4e0e106e3403e32d166b2) now runs only after actual dispatch rejection; it preserves the original result on the child loop. [Lifecycle guards](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d1ededab5f166b46cbf40efffab0eb2ba23461dc) and [deterministic pending-parent setup](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/b4a6314a96951875f59ece09890b2ab016dbc903) cover explicit close, shutdown, and an application handler refusing close while an unflushed write remains. [Termination cleanup](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/1165d4e67dc66bd5c7fde51c8dec0049b5863dc7) disposes both flushed and unflushed entries and avoids shutdown-hook submission after termination. The final six-case write guard is green. Reverting to the original mux code failed four of six cases; explicit close and ordinary pending shutdown already passed on that original code.

[Exact rejection-message assertions](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/33e551fc08b1a688856e2d956c594667a7d0608e) check the original `null` message for both muxers. The final replacement-message mutation fails exactly those two cases: “The original queue rejection message must be retained ==> expected: <null> but was: <Review replacement lost the original queue rejection>”; 68118 ms, [full record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/rejection-message-final-mutation.xml).

The independent review passes also found two issues while developing this round. Their first attempts are retained, rather than replaced by later green runs:

- The first message-mutation run caught both intended assertions and an additional `RejectedExecutionException` while the existing stream test submitted its 2048 queue-fill tasks. The pre-armed periodic observer introduced ordinary queue work while the parent was pending. It was replaced with retained delivery only after actual rejection; the unchanged six-case stream test then passed. [First full failure record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/rejection-message-mutation-failures.txt).
- The first termination-cleanup red caught an unflushed child write left pending and `RejectedExecutionException: event executor terminated` during shutdown-hook removal. A first cleanup-fix attempt reached a normal successful parent completion before termination, failing “Terminated-loop writes must report the exact rejection”; that test had not forced the intended path. Holding the parent pending then produced the deterministic old-code red “Loop termination must settle accepted unflushed child writes” (32343 ms), and the fixed code passed (38035 ms). Every assertion and bound is retained. [Initial cleanup failure](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/termination-unflushed-red-failures.txt), [intermediate failure](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/termination-unflushed-green-failures.txt), [deterministic red](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/termination-unflushed-deterministic-red.xml).

- The first 21-class register at `97e506d0` found the transport-gate assertion failing in `BufferedHalfCloseFramesTest`, and the pending-write assertion failing in `YamuxPrefixFrameFailureTest`. The new observer called `terminationFuture()` on caller-driven embedded loops, where that method throws `UnsupportedOperationException`; the application write therefore failed before its held transport completed. [The lifecycle capability guard](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/cf209084f3c8ea8f3a66968d21506db029e08737) limits shutdown/termination observers to owned autonomous loops. The unchanged tests then passed 14/14 and 1/1. A fresh register uses separate verified-* records at the corrected pushed head. [Buffering first failures](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/final-BufferedHalfCloseFramesTest-failures.txt), [prefix first failure](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/final-YamuxPrefixFrameFailureTest-failures.txt).

The dedicated test-comprehensiveness and code-review passes covered close callback handoff, unrelated nested notifications, all parent-result states, stale delivery after close, retained delivery cancellation, owner shutdown, blocked close, and unflushed termination. Findings and invariant updates are preserved in the [round-3 findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71c/findings.md). No new external approval is claimed.

### Notification-site sweep

24 logical sites: 20 existing ownership/guard boundaries, three corrected original sites, and one guarded new termination observer; four new guards in total. Pending Netty sources keep their documented normal listener-executor contract.

| Notification site | Count | Guard / ownership evidence |
|---|---:|---|
| Channel and generic converters | 2 | Copy terminal result and recheck registration; pending sources retain the documented normal listener-executor contract. |
| TCP close cleanup | 2 | Installed during channel ownership registration; collection removal does not gate the caller's independent close operation. |
| QUIC close cleanup | 2 | Collection cleanup; caller bind/connect/close results use the converters and close owner independently. |
| Physical connection close | 1 | NEW: explicit public close-callback scope preserves reentry order; unrelated terminal observations transfer directly, including depth-eight calls. |
| Stream write result | 1 | Corrected converter plus original failure precedence; exact rejection message now asserted for both stock muxers. |
| Capacity reset result | 1 | NEW: terminal converter replaces bare completed-future listener registration; both muxers red/green at depth eight. |
| Mux readiness and stream setup | 2 | Owned setup/controller failure paths and ordinary pending-source notifications; setup-close coverage retained. |
| Mplex frame aggregation | 1 | Pending combiner attached before writes finish on the parent context; child delivery has its separate retained-result guard. |
| Yamux frame failure and aggregation | 3 | Pending frame/combiner ownership on parent context; failed prefix releases remainder; child delivery guarded separately. |
| Child parent-close observation | 1 | Pending listener installed during registration; already-closed parent uses guarded submission with inline fallback. |
| Mux active/pending-close observation | 1 | Owned active/setup results; channelUnregistered supplies an additional terminal lifecycle path. |
| Negotiation timeout cleanup | 1 | Close listener plus handlerRemoved cancellation and bounded timeout owner. |
| Child write accounting | 1 | Attached before the write; payload/result disposal on the owner loop, or exclusive disposal after owner termination. |
| Parent write continuation | 1 | NEW: same-loop inline completion on rejection; retained delivery for another executor, identity guard against stale completion, cancellation on child close/shutdown. |
| Existing connection-setup completion/close observers | 3 | Pending callbacks installed before activation; unchanged lifecycle/failure owners, covered by the requested setup/handshake tests. No protocol source copied into this record. |
| Retained-write termination observer | 1 | NEW: installed before parent callback for owned autonomous loops; caller-driven loops skip unsupported lifecycle APIs; removed on settlement/close; shutdown hook closes on owner loop, final termination disposes flushed and unflushed entries without new submissions to that loop. |

[Formatter commit](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/97e506d01edeeb1252f3e535b1d6ec16a2e75bdf): the configured repository formatter applied only to 14 Kotlin files and one Java file in the PR diff. The scoped check passed; actual FileCollection inputs and logs are preserved. Formatting changes imports, layout and braces, with no behavior change.

### Record correction

The specific final publish timeout in `GossipTwoHostTest.test message larger than mplex frame`, previously labelled `LOAD_SENSITIVE` in `F68c-final.md`, remains **unattributed** until matching develop evidence exists. The cited develop control failed at bind before publish; another develop record was a receive timeout, and the formatting profile came from a prior PR head. Those observations do not establish the cause of that final publish timeout. No evidence establishes that this PR caused or worsened it.

### Final-head first attempts

Pushed source head `cf209084f3c8ea8f3a66968d21506db029e08737`. One class per bounded Gradle command; maximum observed concurrent Gradle commands across the round: **2**. Each command had a 25-minute maximum.

The fresh register reached its fixed overall deadline before finishing the list. The table contains only completed attempts; remaining classes are not counted as passing. The earlier complete 21-class register, its failures, and the unchanged 14/14 plus 1/1 corrected-loop guards are preserved separately.

| Class | Pass / total | Fail / error / skip | XML ms |
|---|---:|---:|---:|
| NoisePreparationLifecycleTest | 5/5 | 0/0/0 | 37091 |
| NoisePreparationConcurrencyTest | 1/1 | 0/0/0 | 6783 |
| NoisePreparationFailureTest | 3/3 | 0/0/0 | 21923 |
| NoisePreparationTrafficTest | 4/4 | 0/0/0 | 30410 |
| NoiseBindingReadinessTest | 3/3 | 0/0/0 | 68977 |
| NoiseHandshakeTest | 6/6 | 0/0/0 | 12443 |
| NetworkPendingConnectTest | 11/11 | 0/0/0 | 20568 |
| CompletedNettyFutureConversionTest | 6/6 | 0/0/0 | 5538 |
| PendingNettyFutureConversionTest | 4/4 | 0/0/0 | 4379 |
| NettyFutureConversionStateTest | 14/14 | 0/0/0 | 6657 |

Register: 10/21 classes; 57/57 pass, 0 failures, 0 errors, 0 skips. [All native XML, exact source controls and full failure records](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/pr71-20261024/investigation/F71c). No failed unmodified test was rerun into green. No timeout or iteration count was changed, and no test/assertion was weakened, skipped or deleted.

All source and evidence are pushed. The PR remains open; this round did not merge, enqueue, publish, deploy or change a handoff. Owned Gradle daemons and compiler/test processes are stopped and a final process audit is preserved.

## Round 4

Two fresh reviews found that loop termination left a public stream-write result pending and that shutdown replaced a completed parent outcome with a closed-channel failure. This round follows the owner's request to settle those results before termination cleanup and run the full register on the final source.

| Item | Red | Green | Reversal |
|---|---|---|---|
| Public write after termination | [test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ccf483e5d92801fa41f7aedbc3a3c03e514fc379),1/1 failed,7543ms: “The public stream write must settle after loop termination” | [fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ba9c3b9c40c3cc5388e2dcc2c26bcef7df7a2261),1/1 passed,4000ms | 1/1 failed at the same assertion,9406ms |
| Completed parent outcome | [initial test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/f1d21376d9b9a84eeca6b28d973160b4bfa5d9a7),3/3 failed,15578ms: “The original successful parent result must reach the child”; [expanded test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/93c31b7f01c333289287fb1bd8f248b310f979f1),6/6 failed,14477ms | [fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/5fb2af9163379abec7b4957dc2aad03252fea7ca),6/6 passed,14928ms | 6/6 failed,20624ms |
| Completed parent plus refused close and queued writes | [test](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/9ef10d67907c23519c2626f4c2375a61b6aa8882),3/4 failed at the public-write assertion,44735ms | [fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/942b40f3c0e970f73188a808f4e3ce712cbe3b52),4/4 passed,51583ms | One-clause reversal:3/4 failed at the same assertion,68888ms |

The independent review found the combined-state gap: applying the first completed result retires its parent token, while a refused close leaves later accepted writes in the child buffer. Shutdown cleanup now checks actual buffer ownership, and settles those writes on the owner loop. Coverage includes pending/success/failure/cancel parent states, public future completion on the owner, exact causes and messages, flushed/unflushed payload release, ordinary close, shutdown and held-loop state. All reversed source was restored byte-for-byte.

Edits to tests present at the start of Round4: **none**. The six Round4 Java files are additions. No existing test, assertion, timeout or iteration count was weakened, skipped or deleted.

The prior TESTS line names19 classes; its actual register contains21 including the two buffering classes. This register adds all three review probe classes plus NoiseAlgorithmReadinessTest and NoiseHandshakeOffEventLoopTest,26 unique classes.

The source/test/build inputs stayed fixed at [the register head](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/815b5c50a01debdc2caaa5de0ce5c1b6f6d5bded). Final evidence commits change only `investigation/F71e`. First attempts only; failed classes were not rerun unchanged. The first8 classes used the normal project task; the rest use two isolated Gradle Test projects with the original task’s captured class directories,74-entry runtime classpath, JVM settings, working directory, JUnit options and single-fork setting. Each command selects one class, uses the private Gradle home, offline mode and max-workers=2. Maximum concurrent commands:2. [Captured settings and evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/pr71-20261024/investigation/F71e).

Register: **25/26 classes; 115/116 checks passed first attempt**. Incomplete: NoiseHandshakeOffEventLoopTest.

| Class | Tests | Failures | Errors | Milliseconds |
|---|---:|---:|---:|---:|
| NoisePreparationLifecycleTest | 5 | 0 | 0 | 52276 |
| NoisePreparationConcurrencyTest | 1 | 0 | 0 | 11221 |
| NoisePreparationFailureTest | 3 | 0 | 0 | 40269 |
| NoisePreparationTrafficTest | 4 | 0 | 0 | 83231 |
| NoiseBindingReadinessTest | 3 | 1 | 0 | 90129 |
| NoiseHandshakeTest | 6 | 0 | 0 | 15967 |
| NetworkPendingConnectTest | 11 | 0 | 0 | 22732 |
| CompletedNettyFutureConversionTest | 6 | 0 | 0 | 7487 |
| PendingNettyFutureConversionTest | 4 | 0 | 0 | 5233 |
| NettyFutureConversionStateTest | 14 | 0 | 0 | 4102 |
| CompletedConnectionCloseNotificationTest | 1 | 0 | 0 | 17004 |
| ReentrantConnectionCloseNotificationTest | 1 | 0 | 0 | 6171 |
| CompletedStreamWriteNotificationTest | 6 | 0 | 0 | 58896 |
| NestedConnectionCloseNotificationTest | 1 | 0 | 0 | 10258 |
| OffLoopParentWriteNotificationTest | 6 | 0 | 0 | 32746 |
| OffThreadConnectionCloseNotificationTest | 1 | 0 | 0 | 4834 |
| CompletedCapacityResetNotificationTest | 2 | 0 | 0 | 17402 |
| BufferedHalfCloseFramesTest | 14 | 0 | 0 | 7210 |
| YamuxPrefixFrameFailureTest | 1 | 0 | 0 | 4372 |
| ParentCloseOwnershipTest | 2 | 0 | 0 | 8706 |
| ConnectionCloseRetryTest | 10 | 0 | 0 | 17422 |
| ReviewTerminalWriteConversionTest | 4 | 0 | 0 | 54090 |
| ReviewCompletedShutdownTest | 6 | 0 | 0 | 48498 |
| ReviewChildLoopAffinityTest | 3 | 0 | 0 | 19849 |
| NoiseAlgorithmReadinessTest | 1 | 0 | 0 | 22214 |
| NoiseHandshakeOffEventLoopTest | — | — | — | — |

`NoiseBindingReadinessTest`: java.lang.IllegalStateException: Noise probe exceeded the 30s aggregate deadline while awaiting BODY_COMPLETE; phase durations: READY=19657ms, BODY_COMPLETE pending=10346ms:. [Full assertion and stack](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/pr71-20261024/investigation/F71e/register-NoiseBindingReadinessTest/full-failure.txt).

Formatter check ran once and passed all14 changed Kotlin and7 changed Java inputs; actual input lists and the scope are included in the evidence. Every started Gradle daemon/compiler/test JVM was stopped; final process audit is included. The PR remains open.

## Round 5

The owner requested investigation of the one round-4 first-attempt readiness failure, execution of the missed class, and removal of investigation records from the landing tree. This round preserves the original failure and all reproduction attempts; it makes no production or test-source changes.

**Item 1 — NOT_REACHED; mechanism unknown.** Unknown: the original cold child spent 19657ms before READY and had not negotiated its first stream by the 30-second aggregate deadline, but the original record and six successful reproduction attempts do not identify the cause of that delay. READY is a probe-start marker emitted before host construction, rather than handshake readiness. The old record reaches host construction at 22049ms and startup at 23758ms, then has no stream-negotiated marker before the aggregate guard fires. The settlement-before-termination change needs close/shutdown, which had not started. Retained delivery needs a rejected child-loop completion dispatch; the old record shows no such rejection. Neither observation proves the cause of the pending stream, and no claim that this is pre-existing is made. No speculative fix was applied.

Three isolated class attempts passed **9/9** checks. Three paired-command attempts with `NoiseAlgorithmReadinessTest`, which shares the cold-child probe and transport fixture, passed **9/9** readiness checks and **3/3** companion checks. Pair 1 had no test-body overlap because one Gradle daemon started later; pairs 2 and 3 had verified overlap of **12200ms** and **13832ms**. All six readiness attempts passed **18/18**, with zero failures/errors/skips. The 30-second aggregate deadline and every assertion and iteration count are unchanged. These passes do not erase or explain the [original failure and full stack](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71e/register-NoiseBindingReadinessTest/full-failure.txt).

Reproduction entry commands (each selected class has its own Gradle command):

```bash
python3 workspace/run-one.py alone-1 NoiseBindingReadinessTest
python3 workspace/run-one.py alone-2 NoiseBindingReadinessTest
python3 workspace/run-one.py alone-3 NoiseBindingReadinessTest
python3 workspace/run-pairs.py
```

The isolated attempts use `:libp2p:test --tests io.libp2p.security.noise.NoiseBindingReadinessTest`. Paired commands use two isolated standard Gradle Test projects with the original task's captured class directories, runtime classpath, JVM defaults, working directory, JUnit configuration and single-fork setting. They run the freshly compiled classes from this checkout; no compiled outputs are copied. Every command uses the private workspace Gradle home, `--offline` and `--max-workers=2`. Maximum concurrent test commands observed: **2**. [Exact commands, exit codes, XML, class settings and observations](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/pr71-20261024/investigation/F71f).

**Item 2 — first attempt PASS.** `NoiseHandshakeOffEventLoopTest` passed **29 tests, 0 failures, 0 errors, 0 skips; 50682ms XML** on `efa10e9731b174191f424d2894078cef2ed95ce0`, with no concurrent test command. Entry command: `python3 workspace/run-one.py off-loop-first NoiseHandshakeOffEventLoopTest`. It uses the original `:libp2p:test` task. [Result and exact command](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71f/off-loop-first/result.json), [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71f/off-loop-first/TEST-io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.xml).

**Item 3 — evidence moved.** [The evidence branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/pr71-20261024) retains round 4 unchanged, recovers `investigation/F71c/` from its historical branch (it was already absent from the starting PR tree), and preserves `.evidence/FURR3/`. Git tree equality verifies all three copies. [The cleanup commit](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/3e1751baddc1e37fc40c51ff1e28b9135c6acc23) removes 205 evidence files and nothing else. The PR tree is clean of evidence directories. All this round's records are on the evidence branch. [Preservation audit](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71f/evidence-preservation.json), [PR tree/source audit](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71f/pr-tree-audit.json).

Source, tests, fixtures and build inputs at the cleanup head are identical to the tested head. Existing-test edit list (file, before, after, justification): **none**. No test, assertion, timeout or iteration count was changed, skipped, weakened or deleted. No reflection or mocks were added. The round-4 formatter result still applies because no formatter input changed.

Combined register: **26/26 classes attempted; 144/145 checks passed on their recorded first attempt**. The single original readiness failure remains visible below. Round-5 diagnostic attempts are separate columns and are not substituted for that failure. The missed class's first attempt is now folded into the register. This is not a claim of a clean register or a known readiness mechanism.

| Class | First-attempt tests | Failures | Errors | XML ms | Round 5 reproduction attempts (tests/failures/errors; XML ms) |
|---|---:|---:|---:|---:|---|
| NoisePreparationLifecycleTest | 5 | 0 | 0 | 52276 | — |
| NoisePreparationConcurrencyTest | 1 | 0 | 0 | 11221 | — |
| NoisePreparationFailureTest | 3 | 0 | 0 | 40269 | — |
| NoisePreparationTrafficTest | 4 | 0 | 0 | 83231 | — |
| NoiseBindingReadinessTest | 3 | 1 | 0 | 90129 | 6 attempts, each 3/0/0; 24047, 30327, 24108, 16147, 26774, 22407 ms |
| NoiseHandshakeTest | 6 | 0 | 0 | 15967 | — |
| NetworkPendingConnectTest | 11 | 0 | 0 | 22732 | — |
| CompletedNettyFutureConversionTest | 6 | 0 | 0 | 7487 | — |
| PendingNettyFutureConversionTest | 4 | 0 | 0 | 5233 | — |
| NettyFutureConversionStateTest | 14 | 0 | 0 | 4102 | — |
| CompletedConnectionCloseNotificationTest | 1 | 0 | 0 | 17004 | — |
| ReentrantConnectionCloseNotificationTest | 1 | 0 | 0 | 6171 | — |
| CompletedStreamWriteNotificationTest | 6 | 0 | 0 | 58896 | — |
| NestedConnectionCloseNotificationTest | 1 | 0 | 0 | 10258 | — |
| OffLoopParentWriteNotificationTest | 6 | 0 | 0 | 32746 | — |
| OffThreadConnectionCloseNotificationTest | 1 | 0 | 0 | 4834 | — |
| CompletedCapacityResetNotificationTest | 2 | 0 | 0 | 17402 | — |
| BufferedHalfCloseFramesTest | 14 | 0 | 0 | 7210 | — |
| YamuxPrefixFrameFailureTest | 1 | 0 | 0 | 4372 | — |
| ParentCloseOwnershipTest | 2 | 0 | 0 | 8706 | — |
| ConnectionCloseRetryTest | 10 | 0 | 0 | 17422 | — |
| ReviewTerminalWriteConversionTest | 4 | 0 | 0 | 54090 | — |
| ReviewCompletedShutdownTest | 6 | 0 | 0 | 48498 | — |
| ReviewChildLoopAffinityTest | 3 | 0 | 0 | 19849 | — |
| NoiseAlgorithmReadinessTest | 1 | 0 | 0 | 22214 | 3 attempts, each 1/0/0; 8479, 12200, 13832 ms |
| NoiseHandshakeOffEventLoopTest | 29 | 0 | 0 | 50682 | First attempt completed in round 5 |

The private-home `./gradlew --stop` completed, and the owned-process audit found no remaining Gradle/compiler/test/probe JVM. This round did not merge, approve, close, enqueue, publish or deploy. The PR remains open for the orchestrator's re-reviews and merge decision. [Full round-5 findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/investigation/F71f/findings.md).

## Round 6

The owner requested: “fix the re-entrant hook retention, narrow the README guarantee, root-cause the two child-JVM deadline failures.” This round addresses the two review findings and preserves the requested first-attempt deadline experiments.

### Re-entry retention

A custom mux callback can synchronously close its child before returning a pending parent promise. Close cleanup then finishes before a completion token exists; registering the token afterwards leaves a hook and callback retaining the closed child. The new guard rechecks child openness and outbound-buffer ownership after the callback returns, before registering either resource.

Test [fc3587a1](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/fc3587a1825adfebe341266ff77922d5a6e8bb1f); fix [d6ad370b](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d6ad370b740f28c6d382535c673499b94db019c5). The public-extension test uses three children, releases every payload, closes child/parent/server and barriers the live loop; the fixed path leaves zero hooks and zero parent completion-listener registrations. It uses real local channels and no private access or GC timing.

| Control | Checks / failures | XML ms | Evidence |
|---|---:|---:|---|
| Original source | 1 / 1 | 3733 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item1-red/result.json) |
| Fixed source | 1 / 0 | 4098 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item1-green/result.json) |
| Guard removed once | 1 / 1 | 4822 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item1-reversal/result.json) |
| Fix restored; new test formatted | 1 / 0 | 903 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item1-restored/result.json) |

Both reds report exactly: “Synchronous child close must leave zero completion shutdown hooks ==> expected: <0> but was: <3>”. [Original full stack and command log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item1-red/gradle.log), [reversal full stack and command log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item1-reversal/gradle.log). The initial command took 1,476,559 ms including a fresh compile; 3,733 ms above is the test time. Java formatting and Kotlin/Java formatting checks passed; formatting changed only the newly added test.

The four required shutdown classes each passed their first attempt, with no errors or skips:

| Class | Checks / failures | XML ms | Evidence |
|---|---:|---:|---|
| OffLoopParentWriteNotificationTest | 6 / 0 | 12671 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/shutdown-OffLoopParentWriteNotificationTest/result.json) |
| ReviewTerminalWriteConversionTest | 4 / 0 | 16794 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/shutdown-ReviewTerminalWriteConversionTest/result.json) |
| ReviewCompletedShutdownTest | 6 / 0 | 13041 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/shutdown-ReviewCompletedShutdownTest/result.json) |
| ReviewChildLoopAffinityTest | 3 / 0 | 6214 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/shutdown-ReviewChildLoopAffinityTest/result.json) |

### README scope

[85b42978](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/85b4297856d09df89816bc571137bd392dc498ff) conditions shutdown settlement on an active parent-write completion hook. One sentence states the pre-existing limitation: an accepted unflushed write without a pending parent write can remain pending and retain its payload after termination when an application handler refuses child closure. This item changes documentation only.

### Child-JVM deadlines

The reviewer observed two empty-output whole-child deadline failures: the inline case at 10 seconds and the responsiveness case at 15 seconds. [The recovered emitted failures](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/reviewer-failure-transcript.txt) identify the parent’s process-exit wait, with no child stack or phase marker. The historical child wait is therefore unknown. Inline host connect creates no application child stream, but transport listen/connect/close can reach the PR’s converters and close ownership; the responsiveness path also uses Ping child writes. No source-only claim excludes every changed PR path.

**Mechanism:** “Unknown; the historical child JVMs’ active waits were not recorded, and neither deadline failure recurred in the planned solo and concurrent runs.” **Outcome: NOT_REACHED**, not a fix or a PRE_EXISTING attribution.

| First attempt | Class | Checks / failures | XML ms | Evidence |
|---|---|---:|---:|---|
| Solo 1 | NoiseHandshakeOffEventLoopTest | 29 / 0 | 36131 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item3-alone-1/result.json) |
| Solo 2 | NoiseHandshakeOffEventLoopTest | 29 / 0 | 36855 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item3-alone-2/result.json) |
| Solo 3 | NoiseHandshakeOffEventLoopTest | 29 / 0 | 38547 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/item3-alone-3/result.json) |
| Concurrent | NoiseHandshakeOffEventLoopTest | 29 / 0 | 38669 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/pair-off-loop/result.json) |
| Concurrent companion | NoiseAlgorithmReadinessTest | 1 / 0 | 4741 | [record](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/pair-companion/result.json) |

Noise total: **116/116 checks across four planned class attempts**, plus the companion’s 1/1; no errors/skips. The [Gradle task timestamps](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/pair-overlap.json) prove 4.728875 seconds of test-body overlap. The concurrent tasks use the original Gradle Test configuration and the same freshly compiled classes and 74-entry runtime classpath, with separate task output directories. **MAX_CONCURRENT_COMMANDS_OBSERVED: 2.**

The brief’s stated second class sharing these exact child harnesses does not exist at this head: [all references](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/harness-reference-search.txt) are in NoiseHandshakeOffEventLoopTest.kt. The optional clarification received no answer, so the announced adjacent NoiseAlgorithmReadinessTest was used; it has its own Noise child harness.

Exact reproduction entry commands (the [runner](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/tools/run-one.py) records every full Gradle argument):

```bash
python3 workspace/run-one.py item3-alone-1 io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest
python3 workspace/run-one.py item3-alone-2 io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest
python3 workspace/run-one.py item3-alone-3 io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest
python3 workspace/run-pair.py NoiseAlgorithmReadinessTest
```

External observation requested no thread dump because no child reached its diagnostic threshold. The three solo runs and concurrent run preserve every first attempt; their passes do not erase or explain the reviewer’s failures. No Noise implementation, child harness, deadline or iteration count changed. No sleep, spinner or artificial test workload was added.

**Edits to existing tests:** none. File / before / after / justification: not applicable. Every test present at the required starting head remains byte-identical; the only new test is ReentrantChildWriteCloseTest.java. No assertion or test was weakened, skipped or deleted. [Tree audit](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/tree-audit.json).

[Full round-six findings and separate coverage/code review passes](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/pr71-20261024/round6/findings.md), [all evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/pr71-20261024/round6). All evidence remains off the PR branch. Verification is local Gradle evidence; no CI runtime pass or external review approval is claimed. No merge, approval, closure, publication or deployment was performed.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

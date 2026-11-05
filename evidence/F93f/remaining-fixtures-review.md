# Static review of the remaining QUIC fixtures

## Scope and method

I read the full contents of `F93fQuicLifecycleSoakTest.kt` and `F93dQuicFullQueueTest.kt`. The full `DocumentationRepository/PHILOSOPHY.md`, `CODE_REVIEW.md`, and `architecture/TESTING.md` were read during the preceding independent review, as recorded in `candidate-independent-review.md`; I did not reread them for this pass, per instruction. This review made no product changes and ran or compiled nothing.

The soak diff adds only a fixed Netty event-loop count in its isolated child JVM. The full-queue fixture has no diff. Supplied run history says the unchanged full-queue fixture passed once on the candidate; the prior soak wrapper failed once at its 30-second bound, while the identical 64-cycle, 16-caller probe body passed in 41.24 seconds. The current four-loop soak edit is not yet compiled or run, so those earlier results do not verify it.

## Findings

### 1. Four event loops is a transparent fixture resource budget, but the unchanged 30-second cap is unverified

`F93fQuicLifecycleSoakProbe.main` sets `io.netty.eventLoopThreads=4` in the child process before creating either transport. This makes the fixture's dependency-owned worker groups a fixed size that matches the documented two-core runner. It does not reduce the 64 measured cycles, the 16 public dial callers released together per cycle, the concurrent close callers, or any assertion. It uses Netty's real event loops and local QUIC peers; there is no mock or artificial CPU load. This is an honest resource budget for the fixture, not a reduction in public caller concurrency or a change to the tested lifecycle.

The JUnit wrapper still has a 30-second hard process bound. The supplied 41.24-second result is for the earlier host-sized worker groups, so it does not prove the four-loop edit will exceed the cap; equally, the edit has not shown that it will finish within the cap. Keep the existing cap and treat compilation plus an execution of the edited probe through the wrapper as an open verification item. Do not infer a green result from the earlier direct-body run or increase the timeout.

The soak checks real client and server child channels and UDP parents, channel unregistration, event-loop thread exit, transport inventories, and per-cycle open-file-descriptor counts against a post-warmup baseline. Its limits are specific: the FD sample is one count after cleanup, not a heap or retained-owner measurement, and all measured closes succeed. It does not cover owner retention after failed closes or the still-open late-child close-result gap.

### 2. The full-queue fixture deterministically reaches its stated queue condition; a few terminal-state assertions remain indirect

`F93dQuicFullQueueProbe.main` sets Netty's queue capacity before creating transports, blocks the real client event loop, enqueues exactly 2,048 tasks, checks `pendingTasks()==2048`, and verifies that one more submission is rejected before shutdown. Shutdown then runs against that saturated loop. The probe uses a real QUIC connection and checks that the transport reports a `RejectedExecutionException`, that the child and UDP parent close futures complete, that both channels are no longer open, that the client group terminates, and that the independent server still reports its listener. This is a narrow, deterministic saturation fixture; it does not use sleeps or artificial load.

The test checks `closeFuture().isDone` but not `isSuccess`, and checks `group.isTerminated` but not the termination future's outcome. Its `!isOpen` assertions show physical closure, while the missing success checks leave the exact terminal future status less explicit. The server assertion is also `activeListeners==1`; it does not make a follow-up connection to show the listener can still accept traffic. Those are remaining assertion gaps if successful close-future status and server usability are part of the contract. The fixture covers one client child/parent on one saturated loop; it does not establish behavior for multiple saturated loops or multiple simultaneously closing dial owners.

Both wrappers use a separate JVM, a 30-second process limit, and force termination in `finally`. They ignore the boolean returned by the subsequent five-second `waitFor`, so the cleanup path does not assert that forced termination completed. In addition, if an assertion fails after `closeStarted` becomes true in the full-queue probe, its `finally` skips awaiting the already-started client close future. That failure path therefore does not verify client cleanup before the child exits. These are cleanup assertion gaps in tests whose isolation depends on child-process lifetime. The full-queue fixture's supplied one-of-one pass is historical evidence for its current candidate run, not evidence for untested queue shapes.

## Guidance applied

- **`CODE_REVIEW.md`**, read in full: I applied the full list, including **A Dearth of Test Coverage**, **Weakening an Existing Test**, **Tests Must Only Exercise the Public API**, **Unbounded Inputs and Variable-Size Data Without a Scale Test**, and the opening warning that the list is not exhaustive. The soak edit does not weaken assertions or reduce its public concurrency. The queue fixture uses dependency queue configuration to force the named condition, then checks the real event loop and transport; the remaining gaps concern the failure-path cleanup and terminal assertions above.
- **`PHILOSOPHY.md`**, read in full: **Reproduce a Bug Before You Fix It**, **Test End-to-End Against Real Dependencies**, **Tests MUST Be Comprehensive**, **Amplify Flakes with Real Concurrency, Never Artificial Load**, and **Never Calibrate a Reproducer to a Machine** apply here. Fixing the event-loop count is explicit and fixed, while preserving real concurrent callers; no current-run claim follows until the changed probe is compiled and exercised.
- **`architecture/TESTING.md`**, read in full: **Ultrathink: Are the Tests Comprehensive?**, **Test the Public API Only**, **Reproduce Production Conditions, Don't Fake Them**, **Side-Effect Isolation**, **Load, Saturation, and Soak**, **Failure Injection and Crash Recovery**, and **Never Calibrate a Reproducer to a Machine** apply. The tests isolate their work in child JVMs, use local real endpoints, and use a full real queue; the stated remaining gaps are the specific unmeasured failure/resource cases above.

## Status

- Both fixture files were read whole; the soak diff and unchanged full-queue fixture were checked.
- No assertion was weakened. The soak's fixed worker count preserves the workload and public concurrency.
- The current soak edit remains uncompiled and unrun; the 30-second wrapper result is unknown for it.
- The separate late-child close-result question remains open. The earlier peer-side close observation is not evidence of the local rejected child's physical close result.

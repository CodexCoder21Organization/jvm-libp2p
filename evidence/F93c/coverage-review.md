# F93c test-comprehensiveness review

## Scope and method

This is a read-only review of the `jvm-libp2p` candidate, based on `origin/develop` and product HEAD `702b3800a552da5da20abd312794ae1c2e2e0bb0`. At review time, `QuicTransport.kt` also had uncommitted changes: the pending-bind error mapping and the newer shutdown-hook path that closes live children on their event loop. The review did not run a JVM, build, or test.

I read these documents in full: `DocumentationRepository/PHILOSOPHY.md`, `DocumentationRepository/CODE_REVIEW.md`, `DocumentationRepository/architecture/TESTING.md`, `jvm-libp2p/README.md`, and `jvm-libp2p/FORK.md`. There is no repository-root `AGENTS.md` in the product checkout.

I read every product file in the `origin/develop` diff in full: `FORK.md`, `README.md`, `build.gradle`, `libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt`, `libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt`, `libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt`, and these test files: `F93bQuicDialPublicationTest.kt`, `F93bQuicRepeatedCloseOrderingTest.kt`, `F93bQuicSecondaryCloseFailureTest.kt`, `F93bQuicTerminatedLoopTest.kt`, `F93bQuicUnsuppressedCloseFailureTest.kt`, `F93bRepeatedQuicShutdownFailureTest.kt`, `F93bRepeatedTcpShutdownFailureTest.kt`, `F93cQuicRejectedLoopTest.kt`, `PlainNettyTransportFailedCloseSetTest.kt`, `PlainNettyTransportFailedCloseTest.kt`, `QuicTransportClosedChildParentTest.kt`, `QuicTransportFailedCloseTest.kt`, `QuicTransportFailedConnectionSetTest.kt`, and `QuicTransportFailedDatagramCloseTest.kt`.

I also read the four requested evidence tests in full: `F93cQuicMultipleCloseFailuresTest.kt`, `F93cQuicBindShutdownTest.kt`, `F93bQuicDialPublicationTest.kt`, and `F93cQuicDialChurnTest.kt`. The follow-up drafts `F93cQuicLiveChildRejectedLoopTest.kt`, `F93cQuicListenShutdownTest.kt`, `F93cQuicTerminationFailureRetentionTest.kt`, and `F93cPlainTransportInventoryTest.kt`, plus `gap5-inventory-draft-review.md`, were read in full.

## What the permanent tests cover

The candidate adds real local TCP and QUIC connections, uses ephemeral addresses, and injects close outcomes through Netty's channel pipeline. It covers repeated shutdown and caller-future mutation, failure of a listener or connection close, preservation of one secondary failure, a primary error that cannot hold suppressed errors, group/thread cleanup, survival of unrelated groups, a child that closed before transport shutdown, native rejection after that child close, and a visitor that re-enters shutdown before connection publication. No existing test from `develop` is modified.

The prior F93b repeated-shutdown and error-aggregation work is carried forward with the supplied 27/27 targeted-pass evidence. The original native-rejection reproducer has red/green/revert-red evidence (0/1, 1/1, 0/1). The dial-visitor reproducer has red/green evidence (0/1, 1/1). These results are inherited from the lane evidence; this review did not rerun them. The latest candidate-wide checks remain pending, so those results do not establish that the current HEAD and current uncommitted `QuicTransport.kt` are green.

## Findings

### C1 — Per-dial UDP parents remain open for the whole transport lifetime

`QuicTransport` adds each outbound UDP parent to `clientDatagramChannels` and removes it only when that parent closes. Closing the corresponding QUIC child removes the child from `channels`, but does not close its UDP parent. The transport therefore retains one socket and one set entry per dial until transport shutdown. The evidence-only `F93cQuicDialChurnTest` fails on the assertion that a closed child releases its parent (one first-attempt red run). This is a resource-retention defect in a long-lived transport, and the test is not yet in the product suite.

Required coverage is a public dial/close churn regression that proves a completed child releases its parent while the transport remains available for the next dial. A flat descriptor/retained-parent check should cover the lifetime behavior. This follows `CODE_REVIEW.md` — “Unbounded Inputs and Variable-Size Data Without a Scale Test” — and `architecture/TESTING.md` — “Extreme-Condition Testing: Load, Fuzzing, Failure Injection, and Chaos” and “Load, Saturation, and Soak.”

### C2 — Pending QUIC bind shutdown is not permanently covered or yet green

The permanent `F93bQuicDialPublicationTest` shuts the transport down from the visitor after QUIC connection setup. It does not cover shutdown while the UDP bind is queued. The evidence-only `F93cQuicBindShutdownTest` failed before the current uncommitted `handle` stage was added: the dial surfaced a lower-level bind/close failure rather than the required `Libp2pException("Transport is closed")`. The current `handle` stage maps that failure when `closed` is true, but its green result has not been recorded, and the regression test is not in the product diff.

Add the held-bind case permanently and verify it against the final source. The test should continue to check no connection publication, zero active connections, and group termination. This follows `architecture/TESTING.md` — “Test-Driven Development,” “Ultrathink: Are the Tests Comprehensive?” and “Reproduce Production Conditions, Don't Fake Them.”

### C3 — Native dispatch rejection with a live QUIC child is a confirmed gap

The permanent `F93cQuicRejectedLoopTest` closes its connection before shutting down the client event-loop group. It therefore exercises rejection for the retained UDP parent, not a child whose event loop rejects its own close. The evidence-only `F93cQuicLiveChildRejectedLoopTest` failed against the earlier hook: the live child's physical close future remained incomplete. The additional `F93cQuicTerminationFailureRetentionTest` exercises loop termination before the first transport close and checks preservation of the original failure; it has not been run. The current uncommitted hook now snapshots the live children of each parent and attempts their native closes before closing that parent, but no follow-up green result is recorded and the new cases remain evidence-only.

Keep the live-child rejection case in the product suite and do not treat parent-only rejection coverage as proof that every owned channel closes. This follows `architecture/TESTING.md` — “Failure Injection and Crash Recovery,” “Every Flake Is a Real Defect,” and “Ultrathink: Are the Tests Comprehensive?” — plus `CODE_REVIEW.md` — “A Dearth of Test Coverage.”

### C4 — The multi-failure regression is only an evidence test

The product tests cover a child-plus-parent failure and a suppression-disabled primary. They do not check that several failing child closes and several failing parent closes all remain reachable in the final shutdown error. `F93cQuicMultipleCloseFailuresTest` covers four of each, and its pre-aggregation control failed on a missing child error. It is not in the product diff, and the current-source green run is pending.

Add the regression test to the product suite and retain identity assertions for each distinct failure. This follows `PHILOSOPHY.md` — “Never Hide a Failure” — and `architecture/TESTING.md` — “Ultrathink: Are the Tests Comprehensive?” and “Bug Fix Testing Workflow.”

### C5 — The QUIC listener check/bind path has no shutdown-race regression

`QuicTransport.listen()` checks `closed`, constructs the handler, and calls `bind()` before it locks to publish the listener in `listeners`. `close()` changes `closed` and snapshots both collections under the monitor. A concurrent close can therefore pass its inventory snapshot after `listen()` passed the first check but before `bind()` is published. `registerChannel()` has its own closed check, but it does not make `listen()` and the shutdown snapshot one atomic operation or guarantee the transport-closed error for that pending listen. The physical effect has not been reproduced, so this is a source-level race finding, not an observed socket leak.

There is no permanent QUIC listen/close test in the candidate. The evidence-only `F93cQuicListenShutdownTest.kt` drafts a public fixture that pauses the supplied signing operation while the server handler is built, closes the transport, releases signing, then asserts the full transport-closed error and `activeListeners == 0`; it has not been run. This follows `architecture/TESTING.md` — “Ultrathink: Are the Tests Comprehensive?” and “Reproduce Production Conditions, Don't Fake Them” — and `CODE_REVIEW.md` — “A Dearth of Test Coverage.”

### C6 — Larger transport inventories are drafted but not validated

The permanent set-close tests exercise two TCP listeners with eight accepted roots and four QUIC client roots. `F93cPlainTransportInventoryTest.kt` drafts four listeners and 32 accepted roots for TCP and WebSocket, but it has not been run or added to the product diff. No current test establishes a flat resource curve or checks close inventory behavior at a larger number of live roots. This is secondary to C1, which already exposes an unbounded per-dial parent lifetime.

The draft can provide useful inventory coverage after it has a first-attempt result and is incorporated. It does not by itself prove a long-lived resource curve. This follows `CODE_REVIEW.md` — “Unbounded Inputs and Variable-Size Data Without a Scale Test” — and `architecture/TESTING.md` — “Load, Saturation, and Soak.”

## Disposition

The test suite is not comprehensive for the current candidate. C1 is an observed failing lifecycle contract; C2–C4 have red evidence but no final-candidate green proof or product regression coverage; C5 is a source-level race whose runtime effect remains unverified; C6 is an unrun evidence draft. The current candidate cannot receive a clean test-comprehensiveness review until the findings are resolved or positively refuted with evidence.

The full documents read for this review were `PHILOSOPHY.md`, `CODE_REVIEW.md`, and `architecture/TESTING.md`. The findings above apply their named sections as listed; no finding relies on a skim or summary in place of those documents.

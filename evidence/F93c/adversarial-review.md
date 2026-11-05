# F93c adversarial code review

## Scope and method

This is a read-only review of the `jvm-libp2p` candidate against `origin/develop`, product HEAD `702b3800a552da5da20abd312794ae1c2e2e0bb0`, and the uncommitted `QuicTransport.kt` changes visible at review time. Those changes include the native shutdown hook that tries to close live QUIC children, and pending-bind error mapping. The review did not run a JVM, build, or test.

I read these documents in full: `DocumentationRepository/PHILOSOPHY.md`, `DocumentationRepository/CODE_REVIEW.md`, `DocumentationRepository/architecture/TESTING.md`, `jvm-libp2p/README.md`, and `jvm-libp2p/FORK.md`. There is no repository-root `AGENTS.md` in the product checkout.

I read every product file in the `origin/develop` diff in full: `FORK.md`, `README.md`, `build.gradle`, `libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt`, `libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt`, `libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt`, and all 14 changed test files listed in `coverage-review.md`. I also read the evidence tests in full: `F93cQuicMultipleCloseFailuresTest.kt`, `F93cQuicBindShutdownTest.kt`, `F93bQuicDialPublicationTest.kt`, `F93cQuicDialChurnTest.kt`, `F93cQuicLiveChildRejectedLoopTest.kt`, `F93cQuicListenShutdownTest.kt`, `F93cQuicTerminationFailureRetentionTest.kt`, and `F93cPlainTransportInventoryTest.kt`, plus `gap5-inventory-draft-review.md`.

## Findings

### A1 — Native cleanup can erase the dispatch failure it was meant to report

In `QuicTransport.kt:249-261`, the termination hook saves the results returned by `closeRejectedChannelOnLoop` for each child and parent. In `QuicTransport.kt:542-551`, that helper first calls `closeNettyChannelOnce`. If dispatch is rejected, it performs `unsafe().close` on the owning loop and returns only the native close promise. When that physical close succeeds, the original failed close future is no longer part of `terminationCloses` or the returned helper future. The parent's close-future listener removes it from `clientDatagramChannels`, so a later `transport.close()` may have no parent entry from which to recover the rejection.

This risk is specifically the sequence where the event loop terminates and the native hook closes/removes the channel **before the first call to `transport.close()`**. The permanent rejection test calls `transport.close()` while the loop is still held in shutdown, so its initial close operations retain the rejection; that test does not cover the sequence above. No physical-loss or error-loss runtime result is claimed here; this is a source-level finding from the returned futures and collection lifecycle.

Preserve both the original dispatch result and native physical-close result for each channel. The evidence-only `F93cQuicTerminationFailureRetentionTest.kt` drafts this public sequence and asserts physical closure plus identity of the original failure, but has not been run. Use it to verify the finding red against the current helper and green after correction. This applies `PHILOSOPHY.md` — “Never Hide a Failure” — and `CODE_REVIEW.md` — “A Dearth of Test Coverage.”

### A2 — An exception thrown after native close promise completion can be dropped

`closeRejectedChannelOnLoop` catches `Throwable` from `channel.unsafe().close(physical)` and calls `physical.tryFailure(failure)`. If the call completes `physical` and then throws, `tryFailure` returns false, and the caught exception has no other reporting path. This is not an observed Netty execution; the code currently does not preserve or check the result of `tryFailure`.

Keep a separate failure future (or otherwise make a failed `tryFailure` observable) so an exception cannot vanish after the physical promise settles. Add a focused test if the public fixture can exercise this sequence; otherwise document a source-based refutation from the Netty contract. This follows `PHILOSOPHY.md` — “Never Hide a Failure” — and `CODE_REVIEW.md` — “This List Is Not Exhaustive.”

### A3 — QUIC dial churn retains one UDP parent per dial

`QuicTransport.kt:268-285` adds each outbound UDP parent to `clientDatagramChannels` and removes it only when the parent's close future completes. Closing a QUIC child removes that child from `channels` (`QuicTransport.kt:349-353`) but leaves the parent open. Repeated successful dial/close operations therefore grow the retained socket set with lifetime dial count on an otherwise live transport. The evidence-only `F93cQuicDialChurnTest` has a 1/1 red result for the public lifecycle assertion; this path has no product fix in the current diff.

Close the per-dial parent when its child connection closes, while preserving ownership and retry semantics, then rerun the deterministic churn reproducer. This follows `CODE_REVIEW.md` — “Unbounded Inputs and Variable-Size Data Without a Scale Test” — and `architecture/TESTING.md` — “Extreme-Condition Testing” and “Load, Saturation, and Soak.”

### A4 — QUIC listen can continue binding after transport close

`QuicTransport.listen()` checks `closed` at `QuicTransport.kt:190`, then builds the server handler at lines 192-200, calls `bind()` at 202, and only afterward registers the listener at 204-212. There is no second closed check or shutdown-snapshot coordination after handler construction. If close wins while handler construction is in progress, it can mark the transport closed and shut down the worker group; the pending listen then attempts a bind against that group. The observable result may be a low-level bind error instead of `Libp2pException("Transport is closed")`; whether a socket can remain open is unverified.

`F93cQuicListenShutdownTest.kt` drafts a deterministic public fixture by holding key signing during handler construction, closing the transport, and then releasing the signer. It has not been run or added to the product tests. Make the close/listen operation linearize before bind publication and verify the test against both old and corrected source. This applies `architecture/TESTING.md` — “Test-Driven Development,” “Ultrathink: Are the Tests Comprehensive?” and “Reproduce Production Conditions, Don't Fake Them” — and `CODE_REVIEW.md` — “A Dearth of Test Coverage.”

### A5 — The late-bind error mapping and live-child hook are not verified on the current source

The evidence-only bind test failed before the current `.handle` stage was added, because the pending dial exposed a low-level close error. The live-child rejection test failed against the earlier parent-only hook because the child's physical close future remained incomplete. The termination-failure-retention fixture is evidence-only and unrun. The current source has since changed these paths, but there is no completed compile or green run for the final source. A recent attempt captured an earlier constructor form that did not compile, so it did not execute either test; the corrected `initCause` form still needs a fresh compile and test run.

Keep these outcomes separate from proven current behavior. The final candidate needs successful red/green evidence for pending bind shutdown and live-child rejection, including error-cause assertions and child/parent physical close assertions. This follows `architecture/TESTING.md` — “Bug Fix Testing Workflow,” “Failure Injection and Crash Recovery,” and “Every Flake Is a Real Defect.”

## Disposition

The candidate is not clear for review completion. A3 is backed by a red public reproducer. A1, A2, and A4 are source findings whose remaining runtime paths need a targeted regression or an evidence-backed refutation. A5 records missing verification, not an assertion that the latest candidate still fails. The source fixes and final candidate gates remain with the owning agent.

The named `CODE_REVIEW.md`, `PHILOSOPHY.md`, and `architecture/TESTING.md` documents were read in full for this pass. Every finding above is tied to the named principles or sections; A1, A2, and A4 also rest on source-level reasoning explicitly marked as such.

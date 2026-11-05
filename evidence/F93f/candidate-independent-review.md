# Independent review of candidate parent cleanup

## Review plan

1. Read the required project and evidence files, plus the full linked review, philosophy, and testing documents.
2. Inspect the complete contents of all seven files changed between `0363c80e` and `46050423`, then compare the changes and relevant history.
3. Record evidence-based findings, coverage gaps, and the exact test edits/reproduction status without running tests or JVMs.

## Progress

- **Observed:** Review scope is the candidate commit `460504238f9f66d3f8aab3400adb8c5e45d404be` against `0363c80e`; seven files are changed. The working tree is clean at the candidate commit.
- **Inferred:** The target paths and evidence files point to QUIC ownership/close ordering, although the task label says UDP. I will use the candidate's actual diff and supplied invariant evidence as the review scope and note the mismatch if it remains relevant.
- **Observed:** The candidate concerns outbound QUIC UDP parent ownership. The implementation and all six touched test files have been read whole, as have the base version of the modified test. The only change from `bcd32209` to `46050423` is formatting and line wrapping.
- **Current state:** Static findings are being written. No tests or JVMs have been run by this reviewer, and no product files were edited.

## Scope and evidence

The candidate is two commits after `0363c80e`: `bcd32209` adds the parent owner lifecycle and five new tests, and `46050423` formats those changes. The changed files are `QuicTransport.kt`, the new `F93cQuicDialChurnTest.kt`, the modified `F93cQuicRejectedLoopTest.kt`, and the four new `F93dQuic*Test.kt` files for failed handshake, handshake shutdown, ordinary close ordering, and shutdown handoff ordering.

This was a static review. I did not run tests or a JVM, edit product code, use network/GitHub tools, or publish anything. Per the parent report, the available Actions XML showed 1,590 of 1,592 first passes, two existing skips, zero failures, and no retries; local candidate compilation also succeeded. Those are reported evidence, not runs performed by this reviewer. The earlier 41/41 count remains historical and does not verify this candidate.

## Required material read in full

- `DocumentationRepository/PHILOSOPHY.md`
- `DocumentationRepository/CODE_REVIEW.md`
- `DocumentationRepository/architecture/TESTING.md`
- Product `README.md` and `FORK.md`
- `evidence/F93e/invariants.md` and `evidence/F93e/candidate-static-review.md`
- All seven current candidate files; also the full base version of `F93cQuicRejectedLoopTest.kt` to compare its prior conditions and assertions

## Findings

### 1. Late child registration still drops the child's close result; runtime leak is not established

Shutdown snapshots registered channels and known dial children at `QuicTransport.kt:155-162`. On the dial path, `registerChannel(it)` runs before assigning `owner.child` (`:340-345`). If registration is rejected because shutdown has started, `registerChannel` starts `closeNettyChannelOnce(ch)` but discards that future (`:396-408`); dial cleanup then sees an owner with no published child (`:375-390`, `:618-625`). The rejected child's close result therefore cannot enter the transport's close-result aggregation.

This ordering remains uncovered by the candidate tests. The handshake-shutdown test holds a handshake pending and observes it fail when its UDP parent closes; it does not let a successful connect arrive after the shutdown snapshot. The ordinary-close and handoff tests cover children whose owner was already published. The supplied F93e review describes a deterministic re-entry candidate through a caller-owned `Multiaddr`, but explicitly says it was not executed and identifies no public observer for the local rejected child's close result.

**Assessment:** This is a confirmed bookkeeping omission and an unresolved test gap. The source and supplied dependency-path review do not prove that a local child remains open or that shutdown returns before physical closure. Do not report a runtime leak or premature return as established without a public, reproducible observation.

### 2. Failed ordinary closes retain dial owners after shutdown has reported them

Each owner enters `clientDatagramChannels` at `QuicTransport.kt:308-315`; the callback that removes it is `:301-306`. `closeDialParent` invokes that callback only when both the parent close succeeded and the child's original close result was successful (`:618-625`). The ordinary-close test deliberately makes the child close result fail after physical removal and confirms that the same `IOException` reaches both the connection close and transport shutdown (`F93dQuicOrdinaryCloseOrderingTest.kt:62-81`). That correctly preserves the error, but also exercises the branch that leaves the owner in the map.

After shutdown aggregates the failures and completes its cached result (`QuicTransport.kt:196-203`), it does not clear the remaining dial-owner inventory. Repeated failed ordinary closes while a transport remains usable can therefore retain one owner, parent, child, and close state per failure; owners still present at terminal shutdown remain reachable for as long as the transport is retained. The cached shutdown future already preserves the reported result, so retaining the channel owners after that point is not needed to answer a repeated `close()` call.

**Assessment:** The retained object graph is visible in the ownership code; heap exhaustion is not demonstrated. Coverage verifies reporting one close failure during shutdown, but not repeated failures while the transport remains open or release of owner references after the terminal result. Consider separating retained failure outcomes from live parent ownership and releasing the inventory after shutdown result assembly.

### 3. The modified rejected-loop test no longer exercises rejected close dispatch

The base `F93cQuicRejectedLoopTest` closed the child, left the UDP parent open, held the event loop, shut down its group, then asserted that transport shutdown reported the rejected close dispatch. The candidate now asserts that the parent close future succeeded and the parent was closed *before* it shuts down the group (`F93cQuicRejectedLoopTest.kt:54-79`), and expects a successful shutdown. That is consistent with the new normal lifecycle: a successfully closed child releases its UDP parent before native shutdown.

This is not a weakened assertion for the same scenario; the required outcome changed because the parent is now already closed. It does, however, remove coverage for the still-present `closeRejectedChannelOnLoop` fallback at `QuicTransport.kt:628-645` when a parent or child remains open and normal close dispatch is rejected. The test's current name still mentions a rejected loop, but its parent has already been released by the time the loop rejects dispatch.

### 4. Churn coverage proves parent closure, not bounded owner inventory at scale

`F93cQuicDialChurnTest` runs 8 batches of 16 successful dials, closes each batch, and checks each observed UDP parent is physically closed while the transport and workers remain usable (`F93cQuicDialChurnTest.kt:22-46`). That is useful lifecycle coverage. It does not test a large simultaneously active parent/child inventory, assert a flat resource curve, or observe that successful owners leave the private inventory. The test therefore would not catch an implementation that physically closes each parent but accidentally keeps every owner in the map. This is a remaining coverage gap under `CODE_REVIEW.md`'s **Unbounded Inputs and Variable-Size Data Without a Scale Test** and `architecture/TESTING.md`'s **Load, Saturation, and Soak**.

## Test edit review

- Five test files are new. They use local QUIC endpoints on OS-assigned ports and explicit packet/promise gates; I found no reflection, CPU spinner, or sleep-based race widening.
- One existing test file changed. Its prior rejection assertion was replaced because the parent is now expected to be closed before loop shutdown. The test was not relaxed for the same condition, but its former rejection-fallback coverage is gone as described above.
- The implementation and the five new tests first appear together in `bcd32209`; the available two-commit history contains no red-run record demonstrating that those tests failed against the old implementation. The current review did not execute them.
- Several tests inspect `ConnectionOverNetty.nettyChannel` and install Netty pipeline handlers to gate close promises. These are public Netty operations on the concrete connection implementation, but they tie the tests to that implementation rather than only to the `Connection` contract. The module should treat that as an explicit low-level transport-test boundary; it is not reflection or a mock.

## Guidance applied

- `PHILOSOPHY.md`, read in full: **Reproduce a Bug Before You Fix It** requires a red reproducer; **Test End-to-End Against Real Dependencies** supports local real QUIC peers; **Tests MUST Be Comprehensive** calls for adjacent lifecycle and scale coverage; **A Refuted Hypothesis Is a Successful Outcome** keeps the late-child claim within the evidence.
- `CODE_REVIEW.md`, read in full: **A Dearth of Test Coverage** applies to the missing late-child branch; **Weakening an Existing Test** prompted the base-versus-candidate comparison of `F93cQuicRejectedLoopTest`; **Tests Must Only Exercise the Public API** guided the note about concrete Netty inspection; **Unbounded Inputs and Variable-Size Data Without a Scale Test** applies to repeated close-failure retention and the limited churn bound; **This List Is Not Exhaustive** guided the lifecycle review beyond these named cases.
- `architecture/TESTING.md`, read in full: **Ultrathink: Are the Tests Comprehensive?** covers neighboring close/shutdown interleavings; **Test the Public API Only** and **Reproduce Production Conditions, Don't Fake Them** guide the late-child observation standard; **Side-Effect Isolation** supports loopback peers and port `0`; **Load, Saturation, and Soak** applies to owner-history growth; **Failure Injection and Crash Recovery** supports explicit close-result gates; **Never Calibrate a Reproducer to a Machine** rules out timing-tuned ordering.

## Item status

- Read all required documents and evidence: **done**.
- Read all seven candidate files whole and compare the one modified test with its base: **done**.
- Review test edits for weakening and missing ordering/scale coverage: **done**; the changed rejected-loop test drops a separate rejected-dispatch branch, but does not relax its new early-parent-close contract.
- Determine whether a late child physically leaks or shutdown returns early: **unresolved from static evidence**; no executed public reproducer was available, and this review did not run one.
- Run tests or modify product code: **not in scope for this review**.

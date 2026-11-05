# R93a cold review of final e3e5caf4

PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/93

## Reading and scope

Read PHILOSOPHY.md, CODE_REVIEW.md, architecture/TESTING.md in full from a fresh DocumentationRepository clone, plus playbooks/FIX_DID_NOT_HOLD.md. Read the complete PR description first; then the remote FLIBP7 findings/resume and FLIBP8 findings at ebcb5f2d737554c506bab6c070ba33a4c6c09b0e. Read the complete repository README.md and FORK.md; no AGENTS.md exists. The review scope is every touched file, whole, not just the diff:

| File | Whole-file review result |
|---|---|
| FORK.md | New shutdown contract is relevant and version45 is consistent. Inherited release instructions are not executed. |
| README.md | New shutdown paragraph is clear, but existing repeated-close failure promise exposes A1 below. |
| build.gradle | Only version and one release comment change; no dependency, timeout, resource allocation or retry change. |
| PlainNettyTransport.kt | Cleanup now runs on success/failure and outside the snapshot lock; shared worker group preserved. A1 repeat-result issue remains. |
| QuicTransport.kt | Dial parents separately retained; child settlement precedes parent cleanup and group termination for one close call. Concurrent/repeated-close and secondary-result checks recorded below. |
| PlainNettyTransportFailedCloseTest.kt | Seven public TCP scenarios; deliberate failure after socket release is a fault at a real Netty pipeline boundary. Caller mutation affects only its observer. |
| PlainNettyTransportFailedCloseSetTest.kt | Two real listeners/eight real accepted roots; physical futures checked before fixture cleanup; group/thread termination and shared-worker survival asserted. |
| QuicTransportFailedCloseTest.kt | Six real QUIC server-parent scenarios. Physical listener and group/thread assertions precede fixture cleanup. |
| QuicTransportFailedConnectionSetTest.kt | Four distinct client UDP parents; held final child and sibling barriers force the problematic callback order. Public physical-parent futures are explicitly checked before teardown. |
| QuicTransportClosedChildParentTest.kt | Ordinary child close removes live child; shutdown on owning loop still checks retained UDP parent's physical future, open/registered flags, group/thread termination and independent server survival. |
| QuicTransportFailedDatagramCloseTest.kt | Parent-only and child-plus-parent failures; exact primary identity, parent close-count and all physical resource states asserted. Secondary failure reporting needs the additional check below. |

No added reflection, Mockito, visibility increase, wall-clock sleep, test skip, timeout increase or weakened existing assertion. The joins in the changed close methods are reached only after their dependent stages settle, so they do not park the callback thread waiting for I/O. Thread joins in tests observe actual termination with an upper bound; they are not timing delays or replacements for a ManualClock.

## A1 — repeated shutdown forgets the original failed close

Confirmed on exact published snapshot45, with its SHA256 verified before use. Three actual first attempts fail on assertions, not preparation: TCP listener failure; QUIC parent-only failure; QUIC child-plus-parent failure. The first call asserts the exact injected error object. A second call returns success, so the expected ExecutionException is absent. Reproducers and XML are under new-tests/R93aRepeated* and repeat-red/.

Mechanism: each close invocation creates a fresh allClosed from the current live collections. Physical close removes the channel/listener (and QUIC UDP parent) from those collections even if the operation reported failure. A later call sees empty snapshots, waits for an already-terminated group, and completes successfully. There is no private transport shutdown result retained across calls. Relevant code: PlainNettyTransport.kt:165-183 and221-226; QuicTransport.kt:132-142 and160-166. This is a terminal-result ownership bug in both touched implementations, including inherited collection removal behavior. It is not a new physical socket leak.

Contract: README.md:307-311 says repeated connection/transport shutdown calls report the original failure. The brief explicitly requires close-twice coverage and preservation of the original cause. Principles: PHILOSOPHY Never Hide a Failure; CODE_REVIEW Silent Contract Changes to an Existing API and Further Lock and Deadlock Considerations (terminal ownership); TESTING Ultrathink (error/concurrency/lifecycle edge cases). Recommendation: keep one privately owned shutdown completion per transport and return independent observer futures; publish its ownership before any callback can re-enter. Cover both settled and pending repeated calls before adopting a correction.

No product fix is made or pushed in this review. No green correction is claimed for A1.

## UDP-parent finding from earlier lanes

The final source does not merely stop observing the parent. dial registers the actual bind.channel in a private set, independent of the QUIC child live list; close snapshots it and closes it after child results settle, before group termination. QuicTransportFailedConnectionSetTest and QuicTransportClosedChildParentTest still assert the exact physical parent future and physical state before fixture teardown. The independent one-line mutation removes only that set registration and fails the existing ClosedChildParent test on its parent-close-future assertion; identical unmodified source/artifact passes all18 PR-added cases. This verifies the specific FLIBP7 parent fix and refutes the premise that the final fixture merely stopped measuring it. The stronger claim of every possible close path is subject to the remaining failure/race checks below, not inferred from one successful path.

## C — existing test edits

None. git diff --name-status origin/develop...e3e5caf4 has six test additions and no test modifications/deletions. Before/after table: empty; no existing test contract changed. Both Actions skips are annotations already on develop: EchoSampleTest.connect1 and QuicServerTestJava.checkConnectionIsNotClosedByTimeout.

## D — Actions evidence

https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41914319187 is success on exact e3e5caf4, run_attempt1, required bld-build success. Independently downloaded and read234 per-suite XML files:1576 cases,1574 first-attempt passes,2 pre-existing skips,0 failures/errors. Four cases are workflow fixtures; native Gradle accounts for1572 cases,1570 passes and2 skips. Retried test list: empty. There are no flaky/rerun/retry XML elements or native Gradle/workflow retry configuration. Duplicate parameter display names correspond to distinct JUnit methods/repetitions, not retry evidence. Raw XML SHA256 hashes and sanitized XML are preserved with JVM property metadata removed. No API403 occurred. No check was re-requested.

## E — description FAIL

The opening explains why and links https://github.com/CodexCoder21Organization/UrlProtocol/pull/992. It links FLIBP7 evidence and states existing default-branch test edits are none. It names snapshot45, but still says publication is pending and no45 upload occurred, contrary to the completed FLIBP8 publication. It omits the required SHA256 a2c9e25b16c69d83bdd813648c447d74bf549ec9a5eaad83cdb51c53a3b19a2c and the final FLIBP8 evidence link. Replace that stale release paragraph with exact published provenance and link both evidence records. Source need not change for that description correction. Principle: PHILOSOPHY The Repository Is the Source of Truth; the specific provenance requirements are the lane brief.

## A1 neighbor — repeated close breaks child-before-parent order

A public Netty handler physically closes the QUIC child but holds its original close-operation promise. The physical-close listener removes that child from activeConnections. The first transport close correctly stays pending. A second close takes an empty child snapshot and invokes the UDP-parent close anyway: the barrier-observed exact parent-close count is1 when it must be0 until the first child operation settles. R93aQuicRepeatedCloseOrderingTest fails on that assertion. The held parent is released and the first injected failure is checked in finally, so this is not an unbounded wait or a fixture cleanup failure. Same root cause as A1, not a separate finding count. Principle: the brief requires ordering of child and parent closes; TESTING Ultrathink concurrency and lifecycle; reviewer judgment on single shutdown ownership.

## A2 — secondary UDP-parent close failure disappears

QuicTransport.kt:160-165 calls allClosed.join() before it examines datagramsClosed or shutdownFailure. With a child failure and a separate UDP-parent failure, the child join throws and the second failure is discarded. R93aQuicSecondaryCloseFailureTest retains the existing primary-identity assertion and adds the missing assertion that the parent error is available as suppressed information on the original child error: both-fail case fails, parent-only control passes, each one native first attempt. Relevant source: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe/libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt#L160

The brief explicitly prohibits swallowed exceptions. PHILOSOPHY Never Hide a Failure applies; the recommendation to preserve secondary failures as suppressed errors while keeping the exact original child object primary rests on reviewer judgment. The same short-circuit also hides a later group failure, and allOf only exposes one failure from multiple children. No source fix or green correction is claimed. This finding concerns missing error information, not a claim that resource cleanup was skipped: the physical-close assertions in the original fixture remain meaningful.

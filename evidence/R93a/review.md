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

## A3 — UDP parent remains open after shutdown of an already-terminated loop

R93aQuicTerminatedLoopTest starts real client/server transports and a real QUIC connection. It ordinarily closes the client QUIC child, schedules worker-group shutdown from its retained UDP parent's own event loop, awaits and asserts group termination, then invokes client.close. That close settles exceptionally, but the physical parent remains open=true, registered=true and closeFuture.isDone=false. The assertion fails1/1 on exact snapshot45; the isolated probe JVM exits and releases its slot. No fixture handler is removed to manufacture this measurement.

The parent is still retained by the new ownership set, so registration is not the missing step on this path. QuicTransport.close:146-148 calls closeNettyChannelOnce, whose NettyChannelClose.kt requestPhysicalClose reports a failed channel.close request; the already-terminal group cannot do subsequent cleanup. Completion of the operation future does not imply completion of the parent's physical close future. Relevant source: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe/libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt#L146

The brief explicitly requires an already-terminated loop and physical close completion on every close path. PHILOSOPHY Fix the Root Cause, Never Mask the Symptom and TESTING Reproduce Production Conditions / Never Calibrate a Reproducer apply. This reproduction forces the exact terminal-loop condition through public APIs, not by scheduler load, reflection or JVM flags. The final source closes the previously measured FLIBP7 callback path, but the stronger every-path claim is false at this head. A correction must settle the actual physical parent even when normal close dispatch can no longer run, preserve the rejection as the operation failure, and cover the native queue-rejection neighbor as well. No product fix, source push or green correction is claimed here.

## B — test comprehensiveness and mutation

Independent mutation: remove only `clientDatagramChannels += parent`, compile the mutated QUIC implementation plus unchanged close helper against the exact verified product45 jar and normal cached Gradle dependencies, and compile the six PR test sources from this fresh checkout. `QuicTransportClosedChildParentTest.closingAnOutboundChildDoesNotDiscardItsUdpParentsShutdownOwner` fails1/1 on physical parent-close-future completion. The same unmodified tests on snapshot45 pass18/18, one native attempt each,0 failures/skips/retries. This mutation is caught. No published jar or PR source is modified by the control. See mutation.patch, mutation-provenance.json, mutant-summary.json and green-summary.json.

Prior established evidence was also verified directly from remote XML, rather than merely restated: FLIBP7 green-register56/56; green-extra4/4; fix-removed revert-red0/4, all intended assertion failures, no errors/skips/retry tags. R93a adds its own independent control and broader counterexamples; old green counts do not prove the new cases.

Five missing dimensions in the PR at e3e5caf4 (the new R93a reproducers are evidence-only and therefore do not yet close these gaps in the PR suite):

1. **Repeated shutdown after physical removal, including pending-operation overlap.** Existing ParentCloseOwnership covers a held physical close and failed close while the channel stays live. It misses a physical close that removes the channel before the original operation result settles, and a repeated call after the original failure. R93a shows3/3 settled-repeat failures and1/1 ordering failure. Relates to TESTING Ultrathink error/concurrency/lifecycle edges and A1.
2. **Complete reporting of simultaneous cleanup failures.** Parent-only and child-plus-parent parameters assert primary identity, but do not check that the secondary error remains visible. R93a both-fail assertion fails; parent-only control passes. Also cover multiple child failures and group failure combined with a channel/parent failure. Relates to Never Hide a Failure and A2.
3. **Actual transport close when native dispatch is rejected or its loop is already terminated.** Existing completed-future/write-notification tests cover a lower-level future conversion path, not physical UDP-parent cleanup by QuicTransport after loop death. R93a terminated-loop reproduction fails on physical state twice: initial measurement and an explicitly instrumented cause-chain measurement, each one native attempt, no automatic retry. The cause chain is CompletionException -> RejectedExecutionException. Full-queue rejection during actual transport close remains another untested route. Relates to Reproduce Production Conditions and A3.
4. **Close during a QUIC dial/bind/handshake and late registration.** The new closed check and `closeImmediately` parent-registration branch need a deterministic public-path test while a dial is in flight. Current QUIC regressions finish every dial before shutdown. Ordinary accepted-root and TCP concurrent-listen/close coverage does not exercise this QUIC branch. Relates to TESTING Ultrathink concurrency/integration.
5. **Large retained inventory and long-lived dial/close churn.** Touched variable-size structures are TCP/WS listeners/channels and QUIC listeners/children/new clientDatagramChannels set; each close copies inventory and allocates per-channel futures. Multi-root correctness is tested at two listeners/eight TCP roots/four QUIC parents, but there is no large-inventory work-count/soak guard or long-lived QUIC transport churn test asserting flat descriptors/heap/thread curves and independent-group survival. In particular an already-closed child leaves its UDP parent retained until transport shutdown; the new set must not silently become history-sized in a long-lived caller. No production-scale or leak-free claim is made from four dials. Relates to CODE_REVIEW Unbounded Inputs and Variable-Size Data Without a Scale Test and TESTING Extreme-Condition Testing / Load, Saturation, and Soak.

Persisted-record restart tests do not apply: these transports own live sockets/groups, not persisted state. Input fuzzing outside transport lifecycle is not claimed by this PR; malformed addresses or failed TLS negotiation should still be included in the pending-dial inventory cleanup dimension where they allocate a parent.

## Final disposition

REQUEST CHANGES. A has3 findings: A1 repeated shutdown result/ordering; A2 lost secondary cleanup failure; A3 open physical UDP parent on an already-terminated loop. B mutation caught=yes,5 coverage gaps. C existing-main-test edits=0, contracts unchanged. D first attempts1574/1576,2 pre-existing skips, retried tests=[]; required bld-build green. E FAIL for stale release provenance.

The original FLIBP7 parent repair is real, not a fixture observation deletion. The categorical every-path assertion is nevertheless false: A3 is a fresh counterexample at the exact published head. Source remains e3e5caf4; no product fix or green correction was attempted. Correcting the findings requires a later source lane and a fresh snapshot version if source changes; snapshot45 must never be overwritten.

Review documents read in full are PHILOSOPHY.md, CODE_REVIEW.md, architecture/TESTING.md (DocumentationRepository head ad1d1c81376fbfc6a34cbb524d94e0659cc6caaf), plus FIX_DID_NOT_HOLD.md. All11 touched files were read whole. The brief overrides TESTING.md's generic remote-check requirement: this lane used required Actions, did not submit remote builds, and did not wait for informational remote checks.

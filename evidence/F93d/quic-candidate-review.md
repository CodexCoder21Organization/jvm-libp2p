# QUIC candidate review

## Scope and current status

Static review only; I did not run a JVM or tests. I read the complete first candidate at `evidence/F93d/churn-candidate-src/QuicTransport.kt` (639 lines), the current `ConnectionCloseRetryTest.kt`, all 19 product-added transport test files from `origin/develop...HEAD`, and the listed F93d/F93c fixtures. I read `DocumentationRepository/PHILOSOPHY.md`, `DocumentationRepository/architecture/TESTING.md`, and `DocumentationRepository/CODE_REVIEW.md` in full in the earlier F93d review pass.

The first candidate had a concrete close-order gap at the connect/registration boundary (below). I subsequently read the complete v2 source snapshot, the new `F93dQuicShutdownHandoffOrderingTest`, and the corrected rejected-loop fixture. The v2 barrier appears to fix the sibling-close handoff case, but the connect/registration gap remains. The orchestrator reports a combined candidate run of 37/40; I did not run tests and make no clean runtime claim.

## Test-comprehensiveness pass

### Coverage already present

- `F93cQuicDialChurnTest` makes 128 real successful dials in eight batches of 16 on one live client transport. For every child close it checks the UDP parent's physical `closeFuture`, success, closed and unregistered state, zero active connections, and continued worker-group/thread and server-listener availability.
- `F93dQuicOrdinaryCloseOrderingTest` holds an ordinary child close result after the child is physically removed from `activeConnections`. It checks shutdown does not request parent close before that original result settles and preserves the injected failure object.
- `F93dQuicHandshakeShutdownTest` holds a real handshake before a child is created, then checks shutdown settles the dial with `Transport is closed`, publishes no connection, and releases the bound UDP port.
- `F93dQuicFailedHandshakeTest` checks a wrong-peer handshake releases its UDP port before returning failure and that a later valid dial still works.
- `F93dQuicFullQueueTest` fills the actual Netty queue to 2048 in its dedicated child process, confirms rejection of the next task, and checks the physical child and UDP-parent close futures settle and the owned group terminates.
- `F93cPlainTransportInventoryTest` checks four listeners and 32 accepted roots for TCP and WebSocket, including physical close and exact `channelUnregistered` completion. This is a PlainNettyTransport fixture, not a QUIC inventory test.

These cases exercise real channels and public transport/connection close results, with Netty handlers used to hold or fail exact lifecycle operations.

### Concrete gap in the first candidate

The candidate does not retain the child-close result when shutdown wins after QUIC connect has produced a child but before that child is attached to its `DialParent`. At `QuicTransport.kt:332-335`, `registerChannel(it)` runs before `owner.child = it`. If shutdown has already set `closed`, `registerChannel` calls `closeNettyChannelOnce(ch)` at lines 398-400 but drops that future and returns false. The dial failure handler at lines 367-382 then closes the parent through `closeDialParent(owner)` while `owner.child` is still null. The close snapshot at lines 154-161 likewise cannot include this child.

That leaves no joined child outcome in either the transport shutdown aggregation or the dial cleanup. Parent close may cause the child to close, but this code does not wait for or retain the child operation that it initiated. The existing handshake-shutdown test stops before child creation; the visitor/publication test observes a child only after registration. A deterministic case at this exact boundary is needed, asserting transport close remains pending until that late child close settles and that the child is physically closed before shutdown completes.

This remains in v2. The source still assigns `owner.child` only after `registerChannel(it)` and the rejected-registration branch still drops the close future (`../f93d-build/source-snapshots/quic-candidate-v2/0/QuicTransport.kt:338-406`). V2 completes `shutdownChildrenSettled` after closing only the channel snapshot (`:155-179`); an unregistered child created after that snapshot is not in it. `closeDialParent` waits the barrier and then closes the parent, but has no future for that late child (`:615-623`). The termination hook similarly sees only registered `channels` plus `owner.child` (`:270-289`). The v2 barrier therefore does not establish that this late child is settled before transport close returns. The required coordination has to cover in-flight child publication/cleanup that races the snapshot, not only close operations already present in the snapshot.

### V2 handoff case

`F93dQuicShutdownHandoffOrderingTest` is a useful deterministic fixture for a different ordering: one ordinary child close has physically closed its child but has not settled its caller promise, while shutdown starts closing a sibling and that sibling close is held. It checks the first UDP parent is not requested until the shared child-settlement barrier releases, and then checks exactly one parent close. The v2 `beforeParentClose` callback waits on `shutdownChildrenSettled` once shutdown has started; that is a direct source-level answer to this fixture. The orchestrator reports the fixture fails on the first candidate. No v2 test result was run in this review.

The corrected `F93cQuicRejectedLoopTest` also aligns with the revised ownership contract: an ordinary successful child close must release its UDP parent before native loop shutdown; a later close rejection must not be reported for that already-settled parent. Its assertions check the close future, parent state, loop termination, and independent server lifetime.

### Scale guards for variable-size state

- **Large active inventory (`channels` and listener snapshots):** the new 32-root inventory is only for PlainNettyTransport. The QUIC failure-set test retains four children/parents, and the churn test has at most 16 simultaneous client connections. There is no comparable high-count QUIC shutdown inventory test in the reviewed fixtures.
- **Per-dial parent history (`clientDatagramChannels`):** the churn test proves each of 128 successful parents is physically closed and unregistered while the transport remains usable. It does not measure retained owner-map entries, heap growth, or a flat cumulative resource curve. The source removes a successful owner through `DialParent.released`, but the test does not directly establish that the map stays bounded across history. The explicit concern that churn is not a literal heap/FD plateau check remains open; the per-parent physical close checks do give useful socket-release evidence.
- **Termination close history (`terminationCloses`):** the reviewed cases cover individual rejected/terminated-loop cases and a small failed-close set, not a large simultaneous set of dial parents whose shutdown hooks run. The candidate stores each hook's child and parent futures and reads them after group termination; it does not clear that list after aggregation, so completed futures remain reachable for the lifetime of the transport. There is no scale or retention test for this list.

## Adversarial code-review pass

The connect/registration gap above is a code-level ownership and completion issue, not only missing test volume. It conflicts with the candidate's stated intent to make transport shutdown join every owned child and parent cleanup. `CODE_REVIEW.md`'s **A Dearth of Test Coverage** and **Unbounded Inputs and Variable-Size Data Without a Scale Test** apply to the gaps above; **Memory That Grows with Service History** applies to the unmeasured parent-history and retained termination-future collections. `architecture/TESTING.md`'s **Ultrathink: Are Tests Comprehensive?**, **Test the Public API Only**, and **Extreme-Condition Testing** apply to the missing boundary case and scale guards. The current tests otherwise use real loopback transports and assert their externally observable close/dial outcomes.

One small test hygiene issue in the reviewed product-added set: `QuicTransportFailedConnectionSetTest.kt:115-121` unconditionally prints `UDP_STATE` diagnostics for every parent. That appears to be temporary probe output; remove it once the probe is no longer needed. This is reviewer judgment under `CODE_REVIEW.md`'s **This List Is Not Exhaustive**, rather than a lifecycle correctness finding.

## Follow-up required for v2

The source-level test and review passes support the new sibling barrier but show it does not cover a child that appears after shutdown's inventory snapshot. The orchestrator reports 37/40 for the combined candidate run; the individual failures are outside this static review's evidence, and no runtime verdict is claimed. The specific connect/registration boundary remains an open correctness finding for the candidate snapshot reviewed here.

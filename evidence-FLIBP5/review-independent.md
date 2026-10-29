# Independent test and code review

## Review plan

- [x] Read the required engineering philosophy and testing architecture documents.
- [x] Read the applicable code-review guidance and the upstream README/FORK context.
- [x] Inspect the snapshot-41 close implementations, the current WIP product diff, the new TCP and QUIC tests, and the README/version edits.
- [x] Check adjacent shutdown paths and test cleanup behavior.
- [ ] Confirm the final corrected test run; the root agent is running it. This reviewer did not build, run tests, or start a JVM.

## Sources read

- `FLIBP5-DocumentationRepository-20261029/PHILOSOPHY.md` (full file).
- `FLIBP5-DocumentationRepository-20261029/architecture/TESTING.md` (full file).
- `FLIBP5-DocumentationRepository-20261029/CODE_REVIEW.md`: test coverage, test weakening, public-API-only, hermetic-test, and work-in-progress-file guidance.
- Upstream `README.md` and `FORK.md`, the current source for `PlainNettyTransport`, `QuicTransport`, `NettyChannelClose`, and `P2PChannelOverNetty`, plus `evidence/cleanup-fix.patch`, `evidence/wip-product.patch`, and the new close tests.
- `evidence/README.md` and `evidence/snapshot41-publication-record.md`; the locally preserved POM SHA-256 is `144f73fdee0cfe6a2269c758976d6a642e588f4e619e7f602e3438bfb23cf48e`, matching both records and naming source commit `25efb1996bd79e59e6eef28d2d44d9a0405fdd78`.

## Test review

**Observed:** The tests start real loopback TCP hosts using Noise and Yamux, and real QUIC transports over OS-assigned ports. They inject close failures through Netty outbound handlers, including immediate promise failure, a thrown handler exception, and a deferred promise failure. For the failure cases, the updated fixture closes the actual channel through a separate successful close promise before it fails the transport's original close promise; this keeps the listener socket closed while preserving the failed close result that should trigger cleanup. The tests cover ordinary success, a TCP accepted-child close failure, and caller cancellation/completion while a close promise is held. Both caller-mutation cases assert that `cancel(false)` or `complete(Unit)` returns `true`, so the mutation is proven to happen while the close is pending. They assert close failure identity where the caller future remains available, owned-group termination state, fixture listener closure, and that each executor's recorded thread exits. The TCP test also checks that its shared worker group remains running. No reflection, mocks, or production endpoint is used.

**Inferred:** These deterministic close-promise controls exercise the success-only `allClosed.thenCompose` failure path and the separate caller-mutation path directly. The 13-case pre-fix baseline recorded by the root agent had 11 intended termination assertions fail and both success cases pass; this reviewer did not execute that baseline.

### Findings

1. **Setup-failure cleanup was incomplete in the first test draft.** Either fixture could leave its server transport alive if startup, dial, or accept failed before `server.close()` was called. The root agent added a `closeAttempted` guard and nested `finally` blocks so early setup failure closes the server and still closes the client. The first listener-failure fixture also left the real listener socket open; cleanup then tried removing its outbound handler after the event loop had terminated, masking the intended assertion in three cases. This was a real fixture issue, and the earlier inference that group shutdown closed that listener was wrong. The root agent now closes the socket through a separate promise before reporting the original close failure. The native red and green runs against this corrected fixture are pending.
2. **Caller-mutation assertions were initially incomplete.** This is now addressed: each caller-mutation test asserts the mutation returns `true` before the close promise is released. No outstanding test-comprehensiveness finding remains from this review.

### Nearby gap checks

- QUIC does not have a separate accepted-connection close-failure case. Its listener failure traverses the same `allClosed` aggregate and cleanup continuation, while the TCP case separately injects a child-channel failure. I consider this adequate for the specific continuation defect; a QUIC child failure would be extra coverage of its distinct channel type, not a missing branch in the changed continuation.
- WebSocket is not started by a dedicated test, but `WsTransport` extends the same `PlainNettyTransport` base whose `close()` is changed. There is no second WebSocket shutdown implementation in this diff.
- The original listener-open inference was correct for the deliberately refusing close handler: that handler leaves the real socket open, and the earlier run did not prove Netty group shutdown would close it. The revised failure fixture explicitly closes the socket with a separate successful close promise, then fails the original promise. Its `acceptor.isOpen` assertion checks this fixture precondition, not a product promise that a refused close must close the listener. The required product assertion remains group termination, including when the original channel-close result fails. The negative control and final green run with this fixture are pending.

## Adversarial code review

**Observed:** In both changed transports, `allClosed.handle` schedules shutdown whether channel closes succeed or fail, and `thenCompose` waits for the group shutdown future. The returned future is a separate dependent of private `groupClosed`; canceling or completing it does not mutate that private future or cancel `allClosed`, so cleanup still runs. The new tests exercise this by successfully mutating the returned future before releasing the held close promise, then checking group termination and thread exit. Plain TCP/WebSocket only shuts down its per-transport boss group; QUIC shuts down its per-transport worker group.

The final dependent first calls `allClosed.join()` after private group cleanup has completed. By construction, `groupClosed` cannot complete until `allClosed` has completed, so this join is already satisfied and cannot block. It makes the channel-close outcome primary even if shutdown also failed; if channel close succeeded, a shutdown failure is propagated. This explicitly implements the documented original-channel-failure precedence without relying on `CompletableFuture.allOf` choosing an input's failure.

**Conclusion:** The proposed cleanup chain addresses the described mechanism without shutting down PlainNettyTransport's process-wide worker group. The tests cover failure identity when group shutdown succeeds and verify termination after listener failures and caller mutation.

### Findings and limits

The two earlier review notes are resolved in the current diff. Error precedence is explicit in both transports, and snapshot-42 now appears in `FORK.md`'s carried-fixes inventory and the `build.gradle.kts` version comment. The README documents the public close behavior.

The tests do not force a simultaneous channel-close and event-loop-shutdown failure. Static review confirms the channel failure wins that combination by design, while a shutdown failure is propagated when channel close succeeds. There is no remaining static coverage gap identified for the changed branch: failure identity is tested when shutdown succeeds; shutdown completion and thread termination are asserted for the induced close failures, caller mutation, and success. Native red/green verification against the latest fixture revision is still pending.

**Conclusion:** Static review supports the cleanup chain and its isolation from caller mutation, with explicit original channel-error precedence. The real socket must be closed by the test fixture before it publishes the injected close failure; the fixture now does that through a separate successful close operation, while leaving the original transport close result failed. The previous run's three teardown errors exposed that setup issue and did not establish that group shutdown closes a channel whose outbound close was refused. The snapshot-42 release inventory and public README text match the implementation. No reflection, mocks, new dependency, public visibility change, timeout/iteration weakening, or unrelated behavior change was found. The negative control and final green run with the revised fixture are pending with the root agent; this reviewer did not build, run tests, or start a JVM.

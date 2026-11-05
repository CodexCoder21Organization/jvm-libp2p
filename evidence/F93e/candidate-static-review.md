# F93e QUIC candidate static review

## Scope and verdict

This was a static review. I did not run a JVM or tests, edit product source, publish, push, or merge. The candidate still has one uncovered lifecycle boundary: a QUIC child can be created and its connect future can complete after shutdown takes its snapshot but before `registerChannel` and `owner.child` publication. In that branch the candidate starts child close and discards its future. The existing fixtures cover neighboring orderings but do not establish that shutdown owns and waits for this child operation.

The candidate's reported 41/41 pass count is historical evidence from the previous lane, not a runtime claim from this review and not evidence for this missing interleaving.

## Material read in full

- `DocumentationRepository/PHILOSOPHY.md`
- `DocumentationRepository/CODE_REVIEW.md`
- `DocumentationRepository/architecture/TESTING.md`
- `evidence/F93d/churn-candidate-src/QuicTransport.kt`
- `evidence/F93d/quic-candidate-review.md`
- `evidence/F93d/resume.md`
- All five `evidence/F93d/F93dQuic*Test.kt` fixtures: `FailedHandshake`, `FullQueue`, `HandshakeShutdown`, `OrdinaryCloseOrdering`, and `ShutdownHandoffOrdering`.

I also read the exact Netty 4.2.10.Final QUIC bootstrap source and the public QUIC API documentation linked below. The local dependency manifest names Netty's QUIC classes at 4.2.10.Final.

## Findings

### 1. Missing late-child registration test

The candidate's shutdown snapshot at lines 155–162 includes registered `channels` and any `DialParent.child` already assigned. The dial continuation checks `closed` at line 328, then evaluates `fromMultiaddr(addr)` while building the QUIC bootstrap at line 333. The public `Multiaddr(List<MultiaddrComponent>)` constructor retains the caller's `List`; it does not defensively copy it. A caller-owned list can therefore re-enter `client.close()` during that later traversal, after the second closed check but before `.connect()` creates the new QUIC child. To avoid triggering too early, a fixture would need to allow the earlier `addr.getPeerId()` traversal in `dial` and trigger only during the later address-to-socket conversion.

If the QUIC handshake then succeeds, `thenApply` reaches `registerChannel` at lines 338–339 with `closed == true`. `registerChannel` calls `closeNettyChannelOnce(ch)` at lines 404–406 and drops the returned future. The throw happens before `owner.child = it` at line 341. The shutdown snapshot cannot contain this child, and the dial failure cleanup's `closeDialParent(owner)` consequently sees `owner.child == null` (lines 384–386 and 615–623). This is the exact late-child close result neither the snapshot nor the dial owner retains.

The adjacent fixtures do not cover this ordering:

- `F93dQuicHandshakeShutdownTest` holds the peer's UDP handshake traffic and closes while the QUIC connect future is pending. It checks the dial fails, the port is released, and no connection is published. It does not make a connect future succeed after the shutdown snapshot.
- `F93dQuicOrdinaryCloseOrderingTest` holds a child close result only after the child is registered and its ordinary close has physically removed it from `activeConnections`.
- `F93dQuicShutdownHandoffOrderingTest` covers a registered sibling close that overlaps an ordinary close and proves the ordinary close waits for the whole shutdown child barrier.
- `F93dQuicFailedHandshakeTest` covers failed peer verification and subsequent transport reuse; `F93dQuicFullQueueTest` covers rejection of close dispatch under a genuinely full Netty task queue.

The missing test should force shutdown from the later caller-owned `Multiaddr.components` traversal, allow the real QUIC connect to finish while another snapshot close keeps shutdown in progress, and assert that the late local child is closed and its original close result is part of shutdown completion/error aggregation. The trigger is deterministic and uses a caller-owned public input; it does not need a timing sleep or a production test-mode flag.

### 2. Netty exposes child-observation callbacks, but the candidate does not expose them to a transport caller

The Netty 4.2.10.Final API has direct ways for code constructing the QUIC bootstrap to see the `QuicChannel`:

| Netty seam | What it can observe | Limit in this candidate |
|---|---|---|
| `QuicChannelBootstrap.handler(ChannelHandler)` | The handler is installed in the new QUIC channel's pipeline. The handler can retain `ctx.channel()` as the public `QuicChannel` and attach a listener to its inherited `Channel.closeFuture()`. Netty's exact-version `connect(Promise<QuicChannel>)` source creates the channel, calls `Quic.setupChannel(..., handler, ...)`, registers it, then calls `channel.connect`; the promise is completed only after that connect future settles. | `QuicTransport.dial` constructs `QuicChannel.newBootstrap(it)` inline and sets only `.streamHandler(...)`. It does not set `.handler(...)`, and callers cannot inject one through `QuicTransport`'s constructor or `dial` parameters. |
| `QuicClientCodecBuilder.sslEngineProvider(Function<QuicChannel, QuicSslEngine>)` | The provider receives the actual `QuicChannel`; code in the provider can attach a close-future observer. | The candidate already uses this callback at line 259, but its body is hardcoded to `sslContext.newEngine(q.alloc())`; there is no caller-provided provider or observer. |
| `QuicChannel.closeFuture()` / `Channel.pipeline()` | Once a reference to the public `QuicChannel` exists, Netty exposes its close future and pipeline. | The connection returned by `dial` is created only after successful registration, so it is not available in the rejected-registration branch.
| `QuicChannelBootstrap.connect(Promise<QuicChannel>)` | A caller that owns the bootstrap can observe the result future. | The result is the completed connection future, so by itself it is too late to inspect the created child before connection completion. The candidate calls parameterless `.connect()`.
| `QuicServerCodecBuilder.handler(ChannelHandler)` and `QuicConnectionCloseEvent` | The server can observe the peer-side QUIC channel and a remote close frame. | This is a remote observation. It does not establish that the local late child's close operation future settled or that its failure is reported by local transport shutdown.

Netty sources: [QuicChannelBootstrap at 4.2.10.Final](https://github.com/netty/netty/blob/netty-4.2.10.Final/codec-classes-quic/src/main/java/io/netty/handler/codec/quic/QuicChannelBootstrap.java), [QuicCodecBuilder at 4.2.10.Final](https://github.com/netty/netty/blob/netty-4.2.10.Final/codec-classes-quic/src/main/java/io/netty/handler/codec/quic/QuicCodecBuilder.java), and the [QuicChannel API](https://netty.io/4.2/api/io/netty/handler/codec/quic/QuicChannel.html). Netty documents `QuicConnectionCloseEvent` as an event for a close frame received from the remote peer, so it is not a substitute for the local child's close future: [QuicConnectionCloseEvent](https://netty.io/4.2/api/io/netty/handler/codec/quic/QuicConnectionCloseEvent.html).

Static conclusion: Netty has the needed callback API, but the current public `QuicTransport` usage does not give a test caller a reference to a child before registration. The candidate's existing `preHandler` and `connHandler` callbacks run after `registerChannel` and after `owner.child` assignment. Without changing how the product wires a Netty callback, a test can observe the peer-side close, but cannot directly hold or assert the rejected local child's `closeFuture`. Treat a peer-side signal as indirect evidence, not as proof that the exact dropped local future was joined.

### 3. Existing coverage is strong for the tested orderings; large active QUIC inventory remains untested

The fixtures use real loopback QUIC transports and explicit gates at meaningful dependency boundaries. They use UDP port `0`, gate handshakes by handling real datagrams, gate close outcomes with Netty promises, and make the full-queue condition explicit. I found no test weakening, CPU spinner, or sleep-based race widening in the five requested fixtures.

The larger candidate review and resume also identify a remaining scale dimension: the churn coverage closes 128 successful parents in batches with at most 16 simultaneous client connections, but no QUIC test closes a large simultaneously active child/parent inventory or establishes a flat resource curve across cumulative dials. The termination hook's `terminationCloses` aggregation is likewise exercised at small inventory only. These are coverage gaps for the candidate's variable-size snapshots and per-transport close collection; they are distinct from the deterministic late-child boundary above.

## Guidance applied

- `PHILOSOPHY.md`, read in full: **Reproduce a Bug Before You Fix It** requires a failing reproducer before a fix; **Amplify Flakes with Real Concurrency, Never Artificial Load** favors exact deterministic triggers once the ordering is known; **Test End-to-End Against Real Dependencies** supports the real loopback QUIC fixtures; **Tests MUST Be Comprehensive** requires adjacent and extreme lifecycle cases; **A Refuted Hypothesis Is a Successful Outcome** keeps the dependency-seam conclusion evidence-based.
- `CODE_REVIEW.md`, read in full: **A Dearth of Test Coverage** applies to the uncovered late-child ordering; **Tests Must Only Exercise the Public API** favors the caller-owned `Multiaddr` and Netty's public callback/channel APIs and rules out private `QuicheQuicChannelAddress` inspection; **Tests Must Be Hermetically Sealed** supports the loopback, port-0 fixtures; **Unbounded Inputs and Variable-Size Data Without a Scale Test** applies to the active inventory and accumulated close collection; **This List Is Not Exhaustive** guided the boundary review beyond the named cases.
- `architecture/TESTING.md`, read in full: **Ultrathink: Are the Tests Comprehensive?** requires this adjacent-ordering audit; **Reproduce Production Conditions, Don't Fake Them** supports using the real QUIC handshake and local channels; **Test the Public API Only** rejects reflecting into Netty's private QUIC address implementation; **No Production Services in Tests** and **Side-Effect Isolation** support the local server and ephemeral UDP ports; **Extreme-Condition Testing** and **Load, Saturation, and Soak** apply to the remaining large-inventory gap; **Failure Injection and Crash Recovery** and **Never Calibrate a Reproducer to a Machine** support exact promise gates and deterministic re-entry rather than timing calibration.

## Item status

- Verify existing F93d QUIC test coverage: **done**; neighboring lifecycle cases are covered, but the late-child registration ordering is not.
- Catalogue public Netty/QUIC child-observation seams: **done**; dependency callbacks exist, but no current transport-caller hook exposes the late local child's close future.
- Review scale coverage: **done**; high simultaneous QUIC shutdown inventory and a cumulative resource curve are not present in the reviewed evidence.
- Execute tests or make product changes: **not part of this static-review task**.

# F93c multiple QUIC close failures: draft review

## Reading

Read these documents in full: `workspace/DocumentationRepository/PHILOSOPHY.md`, `workspace/DocumentationRepository/CODE_REVIEW.md`, and `workspace/DocumentationRepository/architecture/TESTING.md`. In particular, the review applied `CODE_REVIEW.md` end to end, including its public-API, hermetic-testing, failure-coverage, and test-weakening sections. Also read the product `workspace/jvm-libp2p/README.md`, `QuicTransportFailedConnectionSetTest.kt`, and `F93bQuicSecondaryCloseFailureTest.kt` in full. No `AGENTS.md` was present at either checkout root.

## Findings

**Observed:** The current test `QuicTransportFailedConnectionSetTest` covers one outbound child close failure and four direct QUIC dials. `F93bQuicSecondaryCloseFailureTest` covers one child plus one UDP-parent close failure. The product implementation snapshots child channels in insertion order, waits for child close operations before starting UDP-parent closes, then shuts down the owned worker group. `transportCloseResult` walks the supplied operations in order, preserves the first distinct failure as primary, and adds later distinct failures as suppressed.

**Inferred:** Sequentially awaiting each direct dial makes its first child the first tracked client child. A focused test with four unique child failures and four unique parent failures can check the missing multi-operation failure aggregation case while using real QUIC peers and public channel operations.

## Draft review

The full draft is [F93cQuicMultipleCloseFailuresTest.kt](F93cQuicMultipleCloseFailuresTest.kt). It uses four direct loopback QUIC dials, checks that each client dial owns a distinct UDP parent, and installs outbound Netty close handlers through public pipeline APIs. Each handler closes the physical channel first, then fails the close promise with its own `IOException` instance. Event-loop submissions form barriers before shutdown; there are no sleeps, reflection, mocks, visibility changes, or production-code edits.

The assertions require the first sequentially tracked child's exact failure object as primary, check repeated shutdown exposes that same primary object, and traverse cause and suppressed links by identity for all eight injected failures without depending on suppressed order. They also check one close request per operation, successful physical child and parent `closeFuture`s, closed and unregistered sockets, client group termination, and the separate server listener and worker group remaining live. `try/finally` closes both transports and cleans any still-running groups.

**Review concern:** The test relies on the observed `QuicTransport.close()` snapshot order matching sequential dial insertion order for the primary-failure assertion. The current source uses a mutable child-channel list, snapshots it in order, and passes it to `transportCloseResult` before parent operations. This is intentional contract coverage, not a suppressed-order assumption.

**Verification limit:** No JVM, build, or test was run, per the task boundary. The red/green test runs remain for the parent agent. The draft uses the neighboring tests' 10-second operation waits; its successful runtime has not been measured here.

The evidence test file was included in the parent's concurrent evidence checkpoint. I made no product-checkout edits.

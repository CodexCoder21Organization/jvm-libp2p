# F93j public-boundary inventory (revalidated)

All repository facts in this corrected report were re-read from the fresh checkout at `/tmp/claude-501/-code/d3edc83f-d2cb-4273-97b5-de24e1a7faf5/scratchpad/workspace/F93j/jvm-libp2p`. No other checkout was consulted for this correction. The only non-checkout material used was the cached Netty jars listed below. No build, test, JVM, GitHub call, or repository source edit was made.

## OBSERVED

- `libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt:53-56` defines the constructor with key, certificate algorithm, and protocol bindings; it accepts no selector provider, select strategy factory, or event-loop factory. Its private client bootstrap is created at lines 115-121. The private worker group at lines 80-81 is a `MultiThreadIoEventLoopGroup` built with `datagramPortReleaseIoFactory(datagramPortReleases)`.
- `libp2p/src/main/kotlin/io/libp2p/transport/quic/DatagramPortRelease.kt:15-18` defines an internal release object with a privately-created `DefaultSelectStrategyFactory` delegate. Lines 34-49 implement its selection pass; lines 51-58 settle releases after destruction. The internal factory at lines 60-79 accepts only the release map, fixes `SelectorProvider.provider()`, supplies `SelectStrategyFactory { release }`, and calls `handler.destroy()` before `release.completeAfterDestroy()`.
- Exact cached dependency artifacts inspected:
  - `io.netty:netty-transport:4.2.10.1-codexcoder21-auto-read`, `/home/hostusr/.gradle/caches/modules-2/files-2.1/io.netty/netty-transport/4.2.10.1-codexcoder21-auto-read/22d0f300c1059b8b18f48b030622ff9b34be8b47/netty-transport-4.2.10.1-codexcoder21-auto-read.jar`, SHA-256 `935148afdfb90334d2b0f46337fabd5a31f6c37930a6c64f7b0ade4215032cc9`.
  - `io.netty:netty-common:4.2.10.Final`, `/home/hostusr/.gradle/caches/modules-2/files-2.1/io.netty/netty-common/4.2.10.Final/c55e97d9bd061a2d88418d2d831e1fa39cb975e7/netty-common-4.2.10.Final.jar`, SHA-256 `ad7ff0989d76be928069176754deec42c6a0e3305013df21814b4ac46b1ace9a`.
  - `libp2p/build.gradle:18` declares `netty-transport`; the pinned fork version is described in the current `README.md` build section. No source jar was cached; signatures were read from class-file metadata in these exact jars.
- Public Netty signatures present in the pinned transport jar:
  - `NioIoHandler.newFactory(SelectorProvider, SelectStrategyFactory): IoHandlerFactory`.
  - `SelectStrategyFactory.newSelectStrategy(): SelectStrategy`.
  - `SelectStrategy.calculateStrategy(IntSupplier, boolean): int`.
  - `IoHandlerFactory.newHandler(ThreadAwareExecutor): IoHandler`.
  - `IoHandler.run(IoHandlerContext): int`, `IoHandler.prepareToDestroy()`, and `IoHandler.destroy()`.
  - `MultiThreadIoEventLoopGroup(int, IoHandlerFactory)` and `NioDatagramChannel(SelectorProvider)`.
  - `Channel.eventLoop()`, `Channel.parent()`, `Channel.close()`, and `Channel.closeFuture()`; `EventLoopGroup` provides `shutdownGracefully()` through its event-executor parent API.
- The public `IoEventLoop`/`IoEventLoopGroup` methods examined do not expose the group-owned `IoHandler` or selector. `NioIoHandler` has `selectorProvider()` but no `selector()` accessor. `IoHandler.destroy()` is public on an instance, but the group does not return that instance. `NioEventLoop.rebuildSelector()` and `NioEventLoopGroup.rebuildSelectors()` are public on the older NIO event-loop classes; the product group is `MultiThreadIoEventLoopGroup`, so those methods do not apply.
- The current F93j tree contains six relevant release/lifecycle test files, all re-read:
  - `libp2p/src/test/kotlin/io/libp2p/transport/F93hQuicConnectionSetupReleaseTest.kt:23-75`: real QUIC dial plus outbound `ChannelOutboundHandlerAdapter` and manually completed `DefaultChannelPromise` to hold physical parent close completion.
  - `libp2p/src/test/kotlin/io/libp2p/transport/F93hQuicDatagramReleaseTest.kt:23-78`: same boundary with a failed close promise.
  - `libp2p/src/test/kotlin/io/libp2p/transport/F93cQuicRejectedLoopTest.kt:20-110`: uses the actual parent event-loop group, shuts it down, and checks later rejected dispatch behavior.
  - `libp2p/src/test/kotlin/io/libp2p/transport/F93cQuicLiveChildRejectedLoopTest.kt:19-111`: drives shutdown with a live child and UDP parent.
  - `libp2p/src/test/kotlin/io/libp2p/transport/F93cQuicTerminationFailureRetentionTest.kt:19-127`: checks retained close failure across a terminated loop and later transport shutdown.
  - `libp2p/src/test/kotlin/io/libp2p/transport/F93jCallbackFailedParentReleaseTest.kt:23-76`: callback-closed dial plus a manually failed physical parent-close promise.
- The pinned QUIC jar exposes public `QuicChannelBootstrap.handler(ChannelHandler)` and `connect(): Future<QuicChannel>`. The handler is documented as being added to the newly created `QuicChannel` pipeline. The product keeps its bootstrap private (`QuicTransport.kt:115-121`). The public `Channel` and `QuicChannel` APIs expose parent/pipeline access but no child enumeration method. No parent-pipeline notification for a local outbound QUIC child was found in the public API surface examined.

## INFER

- A test-owned group can use `NioIoHandler.newFactory(SelectorProvider.provider(), customSelectStrategyFactory)` with `MultiThreadIoEventLoopGroup(int, factory)` and a real `NioDatagramChannel` to count or gate select-strategy passes and throw from `calculateStrategy` on a chosen pass. That controls the Netty strategy boundary, but it does not inject into F93j's product-owned group.
- Public `shutdownGracefully()` through `parent.eventLoop().parent()` can drive the production group's destruction path through a real parent channel. The F93c tests in this checkout demonstrate that lifecycle route.
- There is no public route found to retrieve or close the selector owned by F93j's existing group, nor to force its select-strategy failure/requeue branch through `QuicTransport`. A direct internal `DatagramPortRelease` call with a throwing `IntSupplier` is possible in an in-module test, but it would not be a public-API transport test.
- `QuicChannelBootstrap.handler(...)` can capture a channel only when the caller owns that bootstrap. It is not documented as a pre-registration callback, and `QuicTransport` does not expose the bootstrap or a handler setter. It therefore does not provide the requested observation point for the local outbound child before product registration.

## Counts

8 repository files re-read (2 product files, 6 tests); 2 cached Netty jars inspected; 0 builds, tests, JVMs, GitHub calls, or repository source edits.

Official API references for method descriptions (current docs versions differ from the locally pinned jars; local method presence was checked from the exact artifacts above): [NioIoHandler](https://netty.io/4.2/api/io/netty/channel/nio/NioIoHandler.html), [IoEventLoopGroup](https://netty.io/4.2/api/io/netty/channel/IoEventLoopGroup.html), [QuicChannelBootstrap](https://netty.io/4.2/api/io/netty/handler/codec/quic/QuicChannelBootstrap.html).

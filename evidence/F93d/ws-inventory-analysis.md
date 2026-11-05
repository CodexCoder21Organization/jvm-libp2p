# F93d WebSocket inventory close analysis

## Finding

The WebSocket failure at `F93cPlainTransportInventoryTest.kt:170` is a missing completion observation in the test, not evidence that `PlainNettyTransport.close()` failed to close an accepted root. The test awaits each root's Netty close future, which proves the socket close operation settled, then immediately checks both `!isOpen` and `!isRegistered`. In Netty 4.2.10, the close future is completed before channel deregistration finishes and `channelUnregistered` is fired. The test must observe that later event before checking registration state.

This does not change the transport contract. `PlainNettyTransport.close()` waits for `Channel.close()` operations and shuts down its own boss group; it intentionally leaves the process-wide shared worker group running. The product code does not promise that all Netty `channelUnregistered` events have been delivered before its returned close future completes. The test may still require all accepted roots to become unregistered eventually, but should wait for that event explicitly.

## Evidence

- `evidence/F93c/F93cPlainTransportInventoryTest.kt` was read in full. It creates 32 accepted roots, captures each `closeFuture()`, invokes `server.close()`, expects the injected listener-close failure by identity, verifies all root close futures are done and successful at lines 165–168, then checks `!it.isOpen && !it.isRegistered` immediately at line 170. The WebSocket parameter failed on that assertion in `register-36-final/TEST-junit-jupiter.xml`; the TCP parameter passed in that run. Both parameters passed in the previously saved `register-32-green` run. `register-36-final-summary.json` records exactly `All 32 accepted roots must close and unregister ==> expected: <true> but was: <false>` for parameter `[2]`.
- `libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt` was read in full. `close()` gathers listener and tracked-channel operations with `closeNettyChannelOnce()` (lines 180–189), waits for their futures, then shuts down only `bossGroup` (lines 191–227). The source comment at lines 221–225 says `workerGroup` is a shared, process-wide singleton that must outlive any single transport. Accepted-root bookkeeping is removed from `channels` by a listener on `ch.closeFuture()` (lines 348–353); that is close-future completion observation, not an unregistration barrier.
- `libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt` was read in full. `requestPhysicalClose()` calls `channel.close().toVoidCompletableFuture()` and settles the operation from that close future (lines 70–90); it does not wait for `channelUnregistered`.
- `libp2p/src/main/kotlin/io/libp2p/transport/ws/WsTransport.kt`, both WebSocket initializers, and the WebSocket handshake paths were read. WS uses the same `PlainNettyTransport` close path; its initializers add HTTP/WebSocket codec and handshake handlers, but do not add a separate channel-close or deregistration barrier.
- The inherited fixture `libp2p/src/test/kotlin/io/libp2p/transport/TransportTests.kt` was read in full. It supplies shared transport fixture behavior; it does not establish a stronger Netty unregistration completion contract.
- Netty 4.2.10.Final `AbstractChannel.java` was not cached locally (no source JAR or source file was present beside the cached Netty transport JAR). I read the tagged upstream source: [AbstractChannel.java at Netty 4.2.10.Final](https://github.com/netty/netty/blob/netty-4.2.10.Final/transport/src/main/java/io/netty/channel/AbstractChannel.java#L615-L689). In `close()`, `doClose0()` calls `closeFuture.setClosed()` and completes the close promise (lines 615–623). Only afterward does the close path call `fireChannelInactiveAndDeregister()`. `deregister()` uses `invokeLater()` to run `doDeregister()`, set `registered = false`, and fire `channelUnregistered` (lines 626–689). Thus a close-future listener can observe `isOpen == false` while `isRegistered == true`; deregistration is later event-loop work.

## Recommended test observation

Install an inbound handler on each accepted root before `server.close()`, count each `channelUnregistered` event, and wait for all 32 events after the close futures settle and before the `isRegistered` assertion. For example, as an unapplied test-only pseudopatch:

```kotlin
val acceptedRootsUnregistered = CountDownLatch(acceptedChannels.size)
acceptedChannels.forEachIndexed { index, channel ->
    channel.pipeline().addLast(
        "inventory-unregistered-$index",
        object : ChannelInboundHandlerAdapter() {
            override fun channelUnregistered(ctx: ChannelHandlerContext) {
                acceptedRootsUnregistered.countDown()
                ctx.fireChannelUnregistered()
            }
        }
    )
}

// Keep the existing close-future done/success assertions.
assertTrue(
    acceptedRootsUnregistered.await(10, TimeUnit.SECONDS),
    "All accepted roots must emit channelUnregistered before registration state is checked"
)
assertTrue(acceptedChannels.all { !it.isRegistered }, "All 32 accepted roots must unregister")
```

The production behavior and existing close-future assertions remain unchanged. This only makes the test wait for the event that its final state assertion depends on; it does not add a promise that transport shutdown itself waits for unregistration. No product file or test was edited and no JVM was launched for this follow-up.

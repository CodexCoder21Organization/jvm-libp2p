# Netty Outbound Buffer Backpressure Reproducer

## Test Added

Added `io.libp2p.transport.NettyOutboundBufferBackpressureTest` in
`libp2p/src/test/kotlin/io/libp2p/transport/NettyOutboundBufferBackpressureTest.kt`.

The reproducer uses real libp2p hosts over real TCP sockets:

- TCP transport: `TcpTransport`
- security: `PlaintextInsecureChannel`
- muxer: Yamux via `StreamMuxerProtocol.getYamux(...)`
- protocol: local test protocol `/test/outbound-buffer-backpressure/1.0.0`

The stalled-peer test opens 256 negotiated Yamux streams from the sender to the
receiver. After the streams are negotiated, it disables `AUTO_READ` on the
receiver's accepted parent `NioSocketChannel`, which means the peer has completed
the TCP/libp2p/Yamux negotiation but no longer drains the TCP receive path. The
sender then writes one 256 KiB payload on every stream, for a total of
67,108,864 bytes. This uses the initial Yamux per-stream send window on each
stream, so the data frames are flushed down to the sender parent channel rather
than being held by Yamux's per-stream send buffer.

The test asserts the post-fix invariant: the sender parent channel's Netty
pending outbound bytes must stay at or below 16 MiB, or the connection/stream
must fail before that bound is exceeded. Today the sender keeps accepting writes
while the parent channel is already not writable, so the parent
`ChannelOutboundBuffer` grows toward the total data written.

The companion non-regression test, `normallyDrainingYamuxPeerReceivesBulkPayloadIntact`,
uses the same real TCP/Yamux/protocol stack with a normally-reading peer. It
writes 8 MiB and asserts the receiver observes exactly 8,388,608 bytes and the
expected CRC32.

## Why It Fails Today

The failing path matches the heap dump mechanism in
`/code/workspace/heapdumps/ANALYSIS.md`:

- `libp2p/src/main/kotlin/io/libp2p/mux/yamux/YamuxHandler.kt`:
  `writeAndFlushFrame()` calls `getChannelHandlerContext().writeAndFlush(yamuxFrame)`
  directly.
- `libp2p/src/main/kotlin/io/libp2p/transport/implementation/StreamOverNetty.kt`:
  `writeAndFlush(msg)` calls `nettyChannel.writeAndFlush(msg)` directly.

Neither path checks parent-channel writability, awaits the returned future, or
enforces a bounded parent-channel pending-byte budget. Yamux's per-stream receive
and send windows do not bound the sender's parent TCP channel queue when many
streams each flush their currently-allowed window into a stalled parent channel.

## Verification Commands

The Gradle daemon was killed twice under host memory pressure with the repository
default 3 GiB Gradle/Kotlin daemon heaps. Successful verification used a
single-worker, no-daemon Gradle invocation with Kotlin compilation in-process:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  -Dkotlin.compiler.execution.strategy=in-process \
  :libp2p:test \
  --tests "io.libp2p.transport.NettyOutboundBufferBackpressureTest.normallyDrainingYamuxPeerReceivesBulkPayloadIntact" \
  --stacktrace
```

Result: passed. The cold compile run took `23m 53s`; the test itself completed
inside the normal targeted Gradle test task.

The stalled reproducer was then run three times:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  -Dkotlin.compiler.execution.strategy=in-process \
  :libp2p:test \
  --tests "io.libp2p.transport.NettyOutboundBufferBackpressureTest.stalledTcpReceiverKeepsSenderParentOutboundBufferBounded"
```

## Failing Output

The failure was consistent across all three stalled runs. Verbatim assertion
failure:

```text
NettyOutboundBufferBackpressureTest > stalledTcpReceiverKeepsSenderParentOutboundBufferBounded() FAILED
    org.opentest4j.AssertionFailedError: Expected stalled TCP receiver to keep sender parent pending outbound bytes <= 16777216, or close/reset before exceeding that bound. Instead peak pending outbound bytes reached 67146140 after writing 67108864 bytes across 256 Yamux streams; channelActive=true, channelWritable=false, bytesBeforeWritable=67113373, bytesBeforeUnwritable=0. ==> expected: <true> but was: <false>
        at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
        at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
        at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
        at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
        at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
        at app//io.libp2p.transport.NettyOutboundBufferBackpressureTest.stalledTcpReceiverKeepsSenderParentOutboundBufferBounded(NettyOutboundBufferBackpressureTest.kt:85)
```

## Measurements

All three stalled runs failed with the same measured peak:

| Run | Result | Peak pending outbound bytes | Bytes written | Gradle elapsed |
|---|---|---:|---:|---:|
| 1 | failed | 67,146,140 | 67,108,864 | 126.859 s |
| 2 | failed | 67,146,140 | 67,108,864 | 127.650 s |
| 3 | failed | 67,146,140 | 67,108,864 | 138.235 s |

The sender parent channel remained active in every failure:

```text
channelActive=true, channelWritable=false, bytesBeforeWritable=67113373, bytesBeforeUnwritable=0
```

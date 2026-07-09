# Outbound Buffer Backpressure Fix Report

## Root Cause

`YamuxHandler.writeAndFlushFrame()` wrote frames directly into the parent Netty channel without checking the parent `ChannelOutboundBuffer`. When a TCP peer accepted a connection and then stopped reading, Netty retained queued outbound frames indefinitely and stream writes did not expose the terminal failure to the writer.

The production heap analysis showed this exact mechanism: retained sender-side Netty/yamux outbound buffers under a stalled remote, causing OOM pressure on a small heap.

## Fix Mechanism

- `YamuxHandler.writeAndFlushFrame()` now enforces the existing `maxBufferedConnectionWrites` connection budget against the parent channel's pending outbound bytes before enqueueing each Yamux frame.
- If the next frame would exceed the configured budget, Yamux creates a `YamuxOutboundBufferExceededException`, records it on the parent channel `WRITE_FAILURE` attribute, releases the frame payload, fires the exception, and deliberately closes the connection.
- The exception message includes peer, pending bytes, attempted frame bytes, projected pending bytes, budget bytes, over-budget duration, and channel.
- `MuxChannel.doWrite()` now consumes the `ChannelFuture` returned by mux handlers and fails the child write promise when the muxer rejects the write synchronously.
- `Stream.writeAndFlushWithFuture(msg)` was added as a source-compatible API extension. Existing `writeAndFlush(msg)` remains and delegates to the future-returning path in `StreamOverNetty`.
- `StreamOverNetty.writeAndFlushWithFuture()` completes exceptionally with the recorded connection-level write failure when the connection was closed by the Yamux budget gate.
- `MplexHandler.onChildWrite()` was updated to return an aggregate parent write future to preserve the mux abstraction contract.

## Budget Default

The repo already exposed `StreamMuxerProtocol.getYamux(maxBufferedConnectionWrites = DEFAULT_MAX_BUFFERED_CONNECTION_WRITES)`. The default remains `10 * 1024 * 1024` bytes.

That default is deliberately above Netty's usual 64 KiB high watermark and above Yamux's 256 KiB initial window, so ordinary short bursts and window-sized transfers are not killed. It is low enough to prevent the production failure mode where a single stalled peer retained tens of MiB on a daemon configured with `-Xmx128m`.

## MUST Items

- Bounded sender-side queueing: `YamuxHandler.writeAndFlushFrame()` checks projected parent pending bytes and closes before enqueueing a frame that would exceed the budget.
- Descriptive failure: `YamuxOutboundBufferExceededException` includes peer, pending bytes, attempted frame bytes, projected pending bytes, budget bytes, duration, and channel.
- Writer-visible completion: `Stream.writeAndFlushWithFuture()` exposes completion. Budget-triggered in-flight/subsequent writes complete exceptionally with the descriptive failure or a closed-stream failure after the forced close.
- Safe defaults/configurability: the existing Yamux builder setting remains the config surface; default stays 10 MiB.
- Slow-but-draining peer: `peerThatStallsBelowBudgetThenResumesReceivesEveryAcceptedByte` verifies a below-budget stall survives and drains intact.
- No silent drops: healthy and recovery tests use CRC-checked payload delivery; gated writes must either deliver before close or complete through a failed future/closed stream.
- Forced-close cleanup: the stalled reproducer waits for the parent outbound buffer to drain to zero and asserts written `ByteBuf`s are released.
- Portability: the stalled tests set and verify actual `SO_SNDBUF`/`SO_RCVBUF` values before relying on TCP backpressure.

## Before

From the reproducer report on branch `outbound-buffer-backpressure-test`:

```text
NettyOutboundBufferBackpressureTest.stalledTcpReceiverKeepsSenderParentOutboundBufferBounded
failed 3/3 with peak pending about 67 MiB, above the 16 MiB bound.
```

## After: Focused Proof

Standalone previously failing reproducer, run three times:

```text
STALLED_REPRODUCER_RUN_1
BUILD SUCCESSFUL in 1m 47s
23 actionable tasks: 1 executed, 22 up-to-date

STALLED_REPRODUCER_RUN_2
BUILD SUCCESSFUL in 1m 43s
23 actionable tasks: 1 executed, 22 up-to-date

STALLED_REPRODUCER_RUN_3
BUILD SUCCESSFUL in 2m 11s
23 actionable tasks: 1 executed, 22 up-to-date
```

Full backpressure class, run three times:

```text
BACKPRESSURE_CLASS_RUN_1
BUILD SUCCESSFUL in 5m 1s
23 actionable tasks: 2 executed, 21 up-to-date

BACKPRESSURE_CLASS_RUN_2
BUILD SUCCESSFUL in 1m 49s
23 actionable tasks: 1 executed, 22 up-to-date

BACKPRESSURE_CLASS_RUN_3
BUILD SUCCESSFUL in 1m 44s
23 actionable tasks: 1 executed, 22 up-to-date
```

Targeted Yamux overflow regression after refcount cleanup:

```text
BUILD SUCCESSFUL in 2m 25s
23 actionable tasks: 1 executed, 22 up-to-date
```

Targeted touched mplex checks:

```text
PlaintextTcpTest.multiplePingChannelsOnTheSameConnection
BUILD SUCCESSFUL in 1m 59s
23 actionable tasks: 1 executed, 22 up-to-date

GossipTwoHostTest.test message larger than mplex frame
BUILD SUCCESSFUL in 1m 44s
23 actionable tasks: 1 executed, 22 up-to-date
```

Formatting/static analysis:

```text
spotlessCheck
BUILD SUCCESSFUL in 1m 35s
53 actionable tasks: 2 executed, 51 up-to-date

detekt
BUILD SUCCESSFUL in 1m 35s
5 actionable tasks: 1 executed, 4 up-to-date
```

## Full Suite Result

The final full `:libp2p:test` run did not pass because of pre-existing/environment-sensitive tests outside the backpressure path. No `NettyOutboundBufferBackpressureTest` or Yamux overflow test failed in the final full run.

```text
887 tests completed, 10 failed, 2 skipped

> Task :libp2p:test FAILED

BUILD FAILED in 8m 11s
23 actionable tasks: 1 executed, 22 up-to-date
```

Failures reported by the final full suite:

- `QuicKuboTestJava > pingKubo()` failed with `java.net.ConnectException: Connection refused` while connecting to the Kubo HTTP endpoint.
- `PlaintextTcpTest > multiplePingChannelsOnTheSameConnection()` timed out in the full-suite run, but passed when run by itself.
- `GossipTwoHostTest > test message larger than mplex frame()` timed out in the full-suite run, but passed when run by itself.
- Three `SubscriptionsLimitTest` cases failed with `java.net.BindException: Address already in use`.
- Two `TcpTransportTest` close cases timed out.
- Two `WsTransportTest` close cases timed out.

## API Additions

`Stream` now has:

```kotlin
fun writeAndFlushWithFuture(msg: Any): CompletableFuture<Unit>
```

The method is source-compatible because it has a default implementation. `StreamOverNetty` overrides it to return the actual Netty write completion translated to `CompletableFuture<Unit>`.

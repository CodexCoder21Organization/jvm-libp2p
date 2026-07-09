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

## Rework: Adversarial Review Findings

### 1. Async parent-failure visibility

Finding: `MuxChannel.doWrite()` completed the child write promise as soon as the muxer accepted the write, so an asynchronous parent-channel write failure could still be reported to `Stream.writeAndFlushWithFuture()` as success.

Fix: `MuxChannel.doWrite()` now leaves the child write in the outbound buffer until the parent `ChannelFuture` completes. The child promise is completed from the parent future outcome, while ordering is preserved by blocking the next child write until the current parent future resolves on the child event loop.

Test: `MuxChannelWriteFutureTest.child write future waits for asynchronous parent write outcome`.

### 2. ByteBuf leaks on budget-rejection paths

Finding: the inbound DATA path leaked `msg.data` when the WINDOW_UPDATE control-frame write hit the parent budget before `childRead`, and a multi-slice outbound DATA write leaked retained slices when a later slice hit the budget.

Fix: `YamuxHandler.handleDataRead()` now releases inbound DATA if the WINDOW_UPDATE write throws before delivery. `YamuxHandler.drainBufferAndMaybeClose()` now releases every retained slice that has not been handed to Netty when a later slice is rejected.

Tests: `YamuxOutboundBufferBudgetRefCountTest.inbound data is released when window update budget rejection closes connection` and `YamuxOutboundBufferBudgetRefCountTest.budget rejection releases retained outbound slices that were not written`, both under paranoid Netty leak detection. The multi-slice test now suppresses flushes in the harness so the first accepted slice remains in the parent outbound buffer and the later slice deterministically crosses the budget.

### 3. Documentation for enforced Yamux budget

Finding: the PR made the previously ineffective `maxBufferedConnectionWrites` setting operational but did not document the behavior change.

Fix: `README.md` now documents the Yamux-only parent outbound-buffer budget, the 10 MiB default, configuration through `StreamMuxerProtocol.getYamux(maxBufferedConnectionWrites = ...)`, and the deliberate close with `YamuxOutboundBufferExceededException`. It also explicitly states that mplex has no equivalent budget gate.

### 4. `Stream.writeAndFlushWithFuture` default semantics

Finding: the default method returns an already-completed future after calling the void `writeAndFlush`, which is weak for external non-Netty `Stream` implementers.

Fix: the KDoc now states the method contract and calls out that the default implementation is compatibility-only and does not observe asynchronous transport completion.

### 5. Review test gaps

Finding: tests did not deterministically prove pending parent writes fail when the budget closes the connection, did not cover the control-frame and multi-slice rejection paths enough, and the normal-drain test used repeated 10-second waits per chunk.

Fix: `YamuxOutboundBufferBudgetRefCountTest.budget close fails undelivered in-flight parent write futures` holds one accepted DATA parent write promise open, triggers a later budget close, and asserts the held child future fails with the budget exception. The inbound WINDOW_UPDATE test covers the control-frame rejection path. The multi-slice test covers retained DATA slices. `normallyDrainingYamuxPeerReceivesBulkPayloadIntact` now waits once on `CompletableFuture.allOf(...)` for all writes instead of stacking a 10-second wait for every chunk.

### 6. Cleanup masking

Finding: test cleanup in `finally` blocks could mask the primary assertion failure.

Fix: `NettyOutboundBufferBackpressureTest` now records the primary failure and `stopHostsPreservingFailure(...)` adds cleanup failures as suppressed exceptions instead of replacing the original failure.

### Before-Fix Proof on 13ade05

Command, run from `/tmp/jvm-libp2p-rework-before` with only the new tests applied:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  -Dkotlin.compiler.execution.strategy=in-process \
  :libp2p:test \
  --tests 'io.libp2p.etc.util.netty.mux.MuxChannelWriteFutureTest' \
  --tests 'io.libp2p.mux.yamux.YamuxOutboundBufferBudgetRefCountTest' \
  --stacktrace
```

Verbatim summaries:

```text
BEFORE_BLOCKER_TESTS_RUN_1
MuxChannelWriteFutureTest > child write future waits for asynchronous parent write outcome() FAILED
    Expecting value to be false but was true
YamuxOutboundBufferBudgetRefCountTest > inbound data is released when window update budget rejection closes connection() FAILED
    expected: 0
YamuxOutboundBufferBudgetRefCountTest > budget rejection releases retained outbound slices that were not written() FAILED
    expected: 0
3 tests completed, 3 failed
> Task :libp2p:test FAILED
BUILD FAILED in 2m 10s
BEFORE_BLOCKER_TESTS_RUN_1_EXIT_1

BEFORE_BLOCKER_TESTS_RUN_2
MuxChannelWriteFutureTest > child write future waits for asynchronous parent write outcome() FAILED
    Expecting value to be false but was true
YamuxOutboundBufferBudgetRefCountTest > inbound data is released when window update budget rejection closes connection() FAILED
    expected: 0
YamuxOutboundBufferBudgetRefCountTest > budget rejection releases retained outbound slices that were not written() FAILED
    expected: 0
3 tests completed, 3 failed
> Task :libp2p:test FAILED
BUILD FAILED in 2m 51s
BEFORE_BLOCKER_TESTS_RUN_2_EXIT_1

BEFORE_BLOCKER_TESTS_RUN_3
MuxChannelWriteFutureTest > child write future waits for asynchronous parent write outcome() FAILED
    Expecting value to be false but was true
YamuxOutboundBufferBudgetRefCountTest > inbound data is released when window update budget rejection closes connection() FAILED
    expected: 0
YamuxOutboundBufferBudgetRefCountTest > budget rejection releases retained outbound slices that were not written() FAILED
    expected: 0
3 tests completed, 3 failed
> Task :libp2p:test FAILED
BUILD FAILED in 1m 27s
BEFORE_BLOCKER_TESTS_RUN_3_EXIT_1
```

### After-Fix Blocker Proof

Command, run from `/code/workspace/jvm-libp2p`:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  -Dkotlin.compiler.execution.strategy=in-process \
  :libp2p:cleanTest :libp2p:test \
  --tests 'io.libp2p.etc.util.netty.mux.MuxChannelWriteFutureTest' \
  --tests 'io.libp2p.mux.yamux.YamuxOutboundBufferBudgetRefCountTest' \
  --stacktrace
```

Verbatim summaries:

```text
AFTER_BLOCKER_TESTS_FINAL2_RUN_1
BUILD SUCCESSFUL in 3m 21s
AFTER_BLOCKER_TESTS_FINAL2_RUN_1_PASS

AFTER_BLOCKER_TESTS_FINAL2_RUN_2
BUILD SUCCESSFUL in 3m 42s
AFTER_BLOCKER_TESTS_FINAL2_RUN_2_PASS

AFTER_BLOCKER_TESTS_FINAL2_RUN_3
BUILD SUCCESSFUL in 1m 56s
AFTER_BLOCKER_TESTS_FINAL2_RUN_3_PASS
```

### After-Fix Regression Suites

Full `NettyOutboundBufferBackpressureTest` class, three executed runs:

```text
NETTY_BACKPRESSURE_CLASS_FINAL_RUN_1
BUILD SUCCESSFUL in 2m 33s
NETTY_BACKPRESSURE_CLASS_FINAL_RUN_1_PASS

NETTY_BACKPRESSURE_CLASS_FINAL_RUN_2
BUILD SUCCESSFUL in 3m 9s
NETTY_BACKPRESSURE_CLASS_FINAL_RUN_2_PASS

NETTY_BACKPRESSURE_CLASS_FINAL_RUN_3
BUILD SUCCESSFUL in 2m 46s
NETTY_BACKPRESSURE_CLASS_FINAL_RUN_3_PASS
```

Negotiation and mux regression tests:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  -Dkotlin.compiler.execution.strategy=in-process \
  :libp2p:cleanTest :libp2p:test \
  --tests 'io.libp2p.mux.yamux.YamuxHandlerTest' \
  --tests 'io.libp2p.mux.mplex.MplexHandlerTest' \
  --tests 'io.libp2p.security.PlaintextTcpTest.multiplePingChannelsOnTheSameConnection' \
  --tests 'io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame' \
  --stacktrace
```

```text
BUILD SUCCESSFUL in 2m 43s
```

Formatting and static analysis:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m' \
  -Dkotlin.compiler.execution.strategy=in-process \
  spotlessCheck detekt --stacktrace
```

```text
BUILD SUCCESSFUL in 1m 50s
58 actionable tasks: 3 executed, 55 up-to-date
```

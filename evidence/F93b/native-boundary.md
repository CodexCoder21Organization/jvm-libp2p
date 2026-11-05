# Remaining native close boundary

This is unfinished work, not a corrected snapshot.

The public API reproduction holds a task on the actual outbound UDP parent's event loop, calls the real group's deprecated `shutdown()` API to enter native dispatch rejection before termination, calls `client.close()`, then releases the task. There is no sleep, reflection, substitute event loop or JVM flag. It fails on the actual physical parent's pending close future (gap3-red, one attempt).

The independent evidence-only diagnostic installs one native shutdown hook before shutdown and calls the channel's public Netty Unsafe.close API on its owning loop. It passes physical close-future and closed-socket assertions, then fails at `isRegistered` (native-diagnostic, one attempt). This diagnostic is not a proposed library correction.

The mechanism is visible in Netty `AbstractChannel.AbstractUnsafe.deregister`: it calls `invokeLater` with the work that calls doDeregister and sets `registered=false`; invokeLater submits to the same executor and logs rejection. `SingleThreadEventExecutor.shutdown()` sets ST_SHUTDOWN, so even callbacks on that loop cannot enqueue that deregistration. `closeForcibly()` only calls doClose, leaving both physical future and registration bookkeeping untouched, so it is not a solution to the required tuple either.

Primary source inspected at the exact Netty release used by the fork:
- https://github.com/netty/netty/blob/netty-4.2.10.Final/transport/src/main/java/io/netty/channel/AbstractChannel.java
- https://github.com/netty/netty/blob/netty-4.2.10.Final/common/src/main/java/io/netty/util/concurrent/SingleThreadEventExecutor.java
- The netty-transport 4.2.10.1-codexcoder21-auto-read sources JAR was also downloaded and its NioIoHandler, AbstractNioChannel and SingleThreadIoEventLoop read, without modifying it.

The library currently preserves the original rejected operation, and its cached close owner consequently does not perform another physical attempt at the shutdown hook. Fixing this by another channel.close request alone would still leave the native deregistration defect shown by the diagnostic. No retry, reflection, private-field change, or overwritten artifact has been adopted.

A full correction needs the native close/deregistration boundary fixed and verified upstream, followed by an authorized new dependency artifact, then the remaining library coverage and publication gates. The brief permits one new jvm-libp2p artifact, not a Netty artifact. The user explicitly says to stop and report if a rule must be broken to proceed. Snapshot45 remains untouched and there is no new publication.

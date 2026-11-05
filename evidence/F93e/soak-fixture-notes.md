# F93e QUIC lifecycle soak fixture

## Plan

- [x] Read the evidence checkout README and the full `DocumentationRepository/architecture/TESTING.md` and `DocumentationRepository/CODE_REVIEW.md`.
- [x] Read the F93c dial churn fixture, F93d child-JVM fixture, and full F93d candidate `QuicTransport.kt`.
- [x] Draft one standalone JUnit scenario that runs a bounded lifecycle soak in a child JVM through public dial/close APIs.
- [x] Check the draft against the fixture requirements and record which resource dimensions it can and cannot observe. No JVM or test runs are part of this draft task.

## Progress

Observed: F93c uses a real loopback QUIC listener and successful concurrent dials, then checks each child and UDP parent close future while its client transport remains live. F93d starts a separate probe JVM with the inherited test classpath. The candidate `QuicTransport` owns a worker group per instance and tracks a dial's UDP parent separately from its QUIC child.

Inferred design: repeat identical cycles, each with one real loopback server and one client transport and 16 simultaneous public `dial` callers; close the returned connections concurrently while the client is still live, then close both transports before taking the next resource-count sample. One identical warmup cycle establishes the post-initialization descriptor baseline; 64 measured cycles must return to that count. Each cycle submits to every loop in both owned groups to capture the actual thread objects, requires both groups to terminate, joins the captured threads, and verifies they ended before sampling.

## Coverage and limits

- The parent JUnit test starts a dedicated child JVM using the current test classpath, following F93d's process fixture. The child performs **one warmup plus 64 measured cycles**, each with **16 concurrent successful public dials**, 16 concurrent `Connection.close()` callers, then full client and server shutdown.
- It requires exactly 32 distinct client channels (one QUIC child and UDP parent per dial) and 17 distinct server channels (16 QUIC children and the shared listening UDP parent). For every observed client QUIC child and client UDP parent, the fixture waits for a real `channelUnregistered` callback and asserts the physical `closeFuture` completed successfully, `isOpen` is false, and `isRegistered` is false while the client transport is still usable. It then checks both transports' remaining observed channels after shutdown, asserts each owned group is terminated, joins every actual thread captured from every event loop in those groups, and asserts those threads have ended.
- The post-warmup Unix open-descriptor count must match after each measured cycle. Thread retention is checked against the actual event-loop threads owned by the two transports; global JVM thread-count equality is intentionally omitted because runtime/JIT/common-pool threads are not transport-owned evidence.
- There is no heap or private-owner-map assertion. Without forcing collection, allocated heap is not a retained-heap measurement; `MemoryPoolMXBean.collectionUsage` is only the latest collection observation and no collection is guaranteed during a cycle. Calling `System.gc()` or treating an uncollected heap number as flat would give a misleading result. This fixture therefore does not claim to prove absence of Java-only retention that has no channel, group, thread, or descriptor effect.
- A suitable leak control for the parent to run is a product overlay that suppresses the worker group's `shutdownGracefully` call: the group-termination and captured-thread assertions should fail. A second control can make the per-dial path skip closing its UDP parent: the client parent `closeFuture`/open/registration assertions run before client shutdown and should fail. This task did not apply either mutation or run the fixture.
- The fixture keeps the existing F93d outer process bound of 30 seconds and the existing F93c/F93d 10-second operation-wait pattern. Runtime was not measured because this assignment explicitly excludes JVM/test runs; the bound must be reviewed against a measured healthy run before adoption, without weakening the specified 64-by-16 workload or silently increasing a bound.

## Static review status

Observed: after parent review, the draft uses nested `finally` cleanup, captures all event-loop threads from both groups, and joins actual threads after the executor/group termination signals. This subtask adds only `evidence/F93e/F93eQuicLifecycleSoakTest.kt` and this requested analysis note. It adds no production seam, reflection, mocks, sleeps, timeout adjustment, JVM tuning flag, or heap assertion. The draft has not been compiled or executed, as directed; compile compatibility and the mutation control remain for the parent to verify.

## References read in full

- `jvm-libp2p-evidence/README.md`
- `DocumentationRepository/architecture/TESTING.md`
- `DocumentationRepository/CODE_REVIEW.md`
- `jvm-libp2p-evidence/evidence/F93c/F93cQuicDialChurnTest.kt`
- `jvm-libp2p-evidence/evidence/F93d/F93dQuicFullQueueTest.kt`
- `jvm-libp2p-evidence/evidence/F93d/churn-candidate-src/QuicTransport.kt`

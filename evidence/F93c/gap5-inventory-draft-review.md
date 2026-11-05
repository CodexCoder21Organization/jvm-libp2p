# F93c plain transport inventory test: draft review

## Work list

- Read full guidance and named fixtures: complete.
- Draft the TCP/WebSocket inventory scenario in evidence only: complete.
- Read and review the whole draft file: complete.

## Reading and review

Read `workspace/DocumentationRepository/PHILOSOPHY.md`, `CODE_REVIEW.md`, and `architecture/TESTING.md` in full in this task thread, along with the product `README.md`, `PlainNettyTransportFailedCloseSetTest.kt`, and `PlainNettyTransportFailedCloseTest.kt`. The full-file review applied `CODE_REVIEW.md`'s public-API, hermetic-test, and test-coverage guidance.

The draft is [F93cPlainTransportInventoryTest.kt](F93cPlainTransportInventoryTest.kt). It runs one real TCP and one real WebSocket scenario using four distinct loopback addresses with port `0`, then creates 32 independent client hosts and retains one accepted root from each. Identity-based parent collection confirms every listener is represented. A per-channel outbound handler counts one actual close request; exactly one listener physically closes before its fixed `IOException` is reported, while all other handlers forward close normally. Event-loop barriers precede shutdown.

The assertions check the exact listener failure, one boundary close per listener/root, successful and completed physical close futures, closed/unregistered sockets, termination of the owning boss group and its captured threads, and survival of the shared worker group as observed from both server and client roots. The code does not add reflection, mocks, sleeps, visibility changes, or production edits. It does not weaken or alter an existing test.

**Review note:** The current `PlainNettyTransport` source uses a process-wide shared worker group. Therefore the `clientWorkerGroup` assertion currently observes the same underlying group as `sharedWorkerGroup`; it records that client roots still see workers alive after server close, without introducing an extra group fixture.

**Verification limit:** No JVM, build, or test was run, as requested. Runtime and the existing 10-second operation waits were not measured in this drafting lane. Root owns the red/green run against the pre-aggregation control and current source.

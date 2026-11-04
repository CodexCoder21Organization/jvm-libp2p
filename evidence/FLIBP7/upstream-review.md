# FLIBP7 independent upstream review

## Plan

- [x] Read required policy documents end to end and repository context.
- [x] Read every committed touched file whole and both preserved close-set tests whole.
- [in progress] Review test comprehensiveness and public ownership coverage on the final UDP-parent patch.
- [pending] Perform separate adversarial source pass on the final patch and record disposition of every finding.

## Documents read in full

- [Code Review Red Flags](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/CODE_REVIEW.md), 232 lines.
- [Engineering Philosophy](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md), 93 lines.
- [Testing Architecture](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md), 1,054 lines.
- Upstream README.md, 469 lines, and FORK.md, 123 lines. No root AGENTS.md exists.

Every committed touched file was read whole at the initial product head [23d66d4110465edb1ae8d83e099266a42ae77f41](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/23d66d4110465edb1ae8d83e099266a42ae77f41): README.md, FORK.md, build.gradle (332 lines), PlainNettyTransport.kt (396), QuicTransport.kt (464), PlainNettyTransportFailedCloseTest.kt (127), QuicTransportFailedCloseTest.kt (112). I also read the whole working-tree PlainNettyTransportFailedCloseSetTest.kt and QuicTransportFailedConnectionSetTest.kt.

## Pass 1: test comprehensiveness, initial source

OBSERVED: The Plain parameter cases cover listener, connection, thrown, deferred and success close outcomes plus caller cancellation and completion. QUIC's corresponding parameter cases cover the owned listening UDP channel, while the four-outbound-connection test covers the registered QUIC children and their distinct UDP parents. Both use real loopback transports, ephemeral ports and Netty pipeline fault handlers through public APIs; no reflection or mock library is introduced. Every intended ownership assertion is before finally cleanup, and the original failure identity is checked.

OBSERVED: The larger Plain case holds two listeners and eight distinct live roots in one close snapshot and checks every captured physical close future, the owned boss group/threads, and the shared worker group's survival. The QUIC case holds four child connections and four distinct client UDP parents, checks every physical future and group/thread death, and keeps the unrelated server live. These additions address FLIBP6's factual larger-cardinality gap for the paths they exercise.

INFER: An additional public neighbor is needed for a UDP parent whose QUIC child already closed before transport shutdown. `registerChannel` removes a child when its physical close future completes, but that fact does not establish that the separately created UDP parent closed. A final patch that merely reconstructs parents from the current child snapshot would omit that ownership path. This is a coverage finding under Testing Architecture's "Ultrathink: Are the Tests Comprehensive?", invariant-first lifecycle guidance, and Code Review Red Flags "A Dearth of Test Coverage"; it is not yet a new measured product failure.

## Variable-size path register

| State | Bound and lifetime | Test reaching the relevant dimension | Review state |
| --- | --- | --- | --- |
| Plain listener map | Current transport's live listeners, removed on physical channel close | Two listeners in PlainNettyTransportFailedCloseSetTest | Larger simultaneous set covered |
| Plain accepted/outbound channel list | Current live channels, removed on physical close | Eight distinct accepted roots with one listener failure | Larger simultaneous set covered |
| QUIC listeners | Current live listening UDP channels | QUIC failed-close parameter cases | Single listener failure/throw/defer/caller mutation covered |
| QUIC child channels | Current live outbound children and listening channels, removed on physical close | Four outbound children with one failed operation | Four-item failure set covered |
| QUIC outbound UDP parents | One parent per dial, independent physical lifetime | Four parents captured through public Channel.parent(); old source fails physical completion | Final ownership patch and already-closed-child neighbor pending |
| Aggregated close futures | Linear in captured live resources, per close operation | Above larger sets, plus adjacent close-retry/future-conversion register | No history-retained cache introduced |

No persisted transport state is involved. The review will re-evaluate any added ownership collection for release at parent physical close and test the child-removal path.

## Pass 2: adversarial source review, pending final patch

OBSERVED: The initial handle-based cleanup runs owned group shutdown after the captured close outcomes settle on either success or failure, then observes the original channel failure before any group failure. The returned caller future is derived from private cleanup, so mutating it cannot cancel that cleanup. The final UDP ownership implementation has not yet been written and no final approval is claimed.

INFER: QUIC's existing unsynchronized `closed`/snapshot/registration shape deserves attention when changing ownership: a new parent collection must be snapshotted consistently and late registration must not strand parents after the group ends. The earlier FLIBP6 review already recorded the original shape as static and unreproduced; I do not turn it into a measured finding without a public reproducer.

## Progress

2026-11-04T20:46:25Z OBSERVED: Required documents and the whole initial touched files/tests were read. OBSERVED: No build, test or JVM was started and no product source or git state was changed by this reviewer. INFER: Next checkpoint is the root's public UDP-state measurement and final parent-ownership patch, then review of that final source and its added public tests.

2026-11-04T20:47:54.542453+00:00 OBSERVED: Root requested the concrete public already-closed-child neighbor. OBSERVED: Drafted new-tests/QuicTransportClosedChildParentTest.kt in evidence only; it closes a real outbound Connection, confirms activeConnections is zero, then checks the captured UDP parent physical future/open/registered state, owner group and all captured threads, and the independent server lifetime. OBSERVED: No product test was edited and no JVM ran. INFER: Next checkpoint is root inspection/red run, then final ownership source review.

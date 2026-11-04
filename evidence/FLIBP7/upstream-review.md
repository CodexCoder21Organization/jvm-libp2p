# FLIBP7 independent upstream review

## Plan

- [x] Read required policy documents end to end and repository context.
- [x] Read every committed touched file whole and both preserved close-set tests whole.
- [x] Review test comprehensiveness and public ownership coverage on the final UDP-parent patch.
- [x] Perform separate adversarial source pass on the final patch and record disposition of every finding.

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

2026-11-04T20:49:59.004324+00:00 OBSERVED: The initial measurement-red.json records one native case, one first-attempt pass and no errors/skips. OBSERVED: Each recorded UDP parent was physically closed, unregistered and had a completed close future on its terminated child event loop. INFER: This run does not reproduce FLIBP6's saved pending-parent condition, so final implementation approval remains pending a reliable mechanism-specific red result or evidence-backed refutation. INFER: Next checkpoint is root's mechanism experiment; the already-closed-child draft is ready for it.

2026-11-04T20:51:25.930146+00:00 OBSERVED: Root reported the complete baseline register reproduces 55/56, with the failed child's parent still open and registered and its close future pending after its owning loop terminated; standalone initial measurement had passed. OBSERVED: Read the updated deterministic failing-set test whole: first child close is held until client.close returns and siblings physically close, then released on its owner loop. OBSERVED: Read the updated already-closed-child neighbor whole: it submits client.close on the parent loop and awaits the returned future outside it. INFER: These orderings target callback-initiated group shutdown without machine tuning. INFER: Recommended one event-loop barrier per sibling after physical-close await so close request/conversion callbacks have also drained before releasing the final held child. Next checkpoint is deterministic native red and final product implementation.

2026-11-04T20:54:01.436820+00:00 OBSERVED: Root's proposed lifecycle retains each raw client bind channel privately until physical parent close and snapshots those parents independently from live QUIC children; activeConnections remains child-only. INFER: Per-child immediate UDP-parent closure is not required by the current public Connection.close contract or this shutdown-scoped brief. INFER: Retaining a still-live parent until Transport.close is acceptable here, provided physical parent close removes its ownership entry and every captured parent is closed before group shutdown; this does not defer a brief item. OBSERVED: No final product patch exists yet. INFER: Next checkpoint remains deterministic red then final source review.

2026-11-04T20:56:51.647492+00:00 OBSERVED: The deterministic test now includes the recommended sibling loop barriers. OBSERVED: The full brief still has no Addendum headings, and no final production patch has been applied. INFER: Test ordering is appropriate: cleanup is installed while the first close is held; sibling close tasks then drain; only the final child's owner-loop callback can release the aggregate. INFER: Next checkpoint remains root's deterministic red followed by source implementation; no final correctness approval is claimed while that step waits for JVM slots.

## Final-patch pass 1: test comprehensiveness

OBSERVED: Read the final modified QuicTransport.kt whole, along with the complete deterministic QuicTransportFailedConnectionSetTest.kt and QuicTransportClosedChildParentTest.kt. The first now holds its first child close until the cleanup chain is installed and sibling close tasks have drained, then completes the physical close and reports the supplied failure on that child's owner loop. The second closes its child first, confirms zero activeConnections, and starts transport shutdown on the parent loop. The saved deterministic-red.json records two assertion failures, no errors or skips, and zero passes: each parent physical close future remains pending after shutdown.

OBSERVED: New reachable `datagramsClosed` failure handling initially lacked its own public test. INFER: This was a test coverage finding under Code Review Red Flags "A Dearth of Test Coverage" and Testing Architecture "Ultrathink: Are the Tests Comprehensive?", because child-close failure does not exercise a failure originating in the added parent stage.

OBSERVED: At root's request I drafted QuicTransportFailedDatagramCloseTest.kt in evidence only, with parent-only and child-plus-parent failures. It physically releases real sockets before failing close promises, asserts the expected exact primary failure and one parent close request, checks every physical future/open/registered state, and verifies owned group/loop/thread termination with an independent server left live. INFER: The parent-only old-product control should fail its expected-error assertion; the child-plus-parent control should fail its parent close-count assertion. Root owns compilation and red/green verification; no test result is claimed for the draft.

## Final-patch pass 2: adversarial source review

OBSERVED: The final implementation stores raw client UDP bind channels independently from its QUIC child list and removes a parent on its physical close future. `close()` captures listener, child and parent snapshots under the transport monitor, then invokes all close operations outside it. After all child/listener outcomes settle, `allClosed.handle` attempts all captured parent closes. `datagramsClosed.handle` starts group shutdown on either parent success or failure. The returned observation reads the already-settled child/listener result first, parent result second, and group result last. Those joins cannot block because their corresponding stages precede group completion. The returned future is a downstream observation; caller cancellation or manual completion does not cancel private cleanup stages.

OBSERVED: `activeConnections` still returns the existing child-channel collection size. A parent with an already-closed child remains tracked until parent physical close, so shutdown no longer depends on reconstructing ownership from currently live QUIC children. The new collection is a set of current physical parent resources, with a removal callback, rather than a history of closed connections. INFER: This lifecycle is consistent with the shutdown-scoped brief; immediate per-child parent closure is not promised by the current public API and is not required to establish shutdown completion.

OBSERVED: A bind's raw parent is registered in `.also` before its bind-completion continuation can create/publish the QUIC child. Registration rechecks `closed` under the same monitor as the close snapshot; if closing already won, it requests parent closure outside the monitor. INFER: A narrow window still exists between raw bind creation and ownership registration, as it did in the original in-flight bind/registration shape. The late close is not included in a close snapshot that has already returned; I do not claim this is a measured leak or a new introduced public regression, because Netty bind/register failure has its own channel cleanup. The specific measured mid-loop shutdown defect is addressed for captured parents, and no confirmed new product defect was found in this final source pass.

OBSERVED: Read baseline-product.json: it identifies unmodified source head 23d66d4110465edb1ae8d83e099266a42ae77f41 and records the whole original jar hash plus every QuicTransport class hash. INFER: A fix-removed runtime control is valid without rebuilding if that original jar is first for production classes, the fixed production class directories/jar cannot take precedence, and the same final test classes and dependency classpath are retained; root must record that actual classpath choice and results. This proves the same behavioral distinction as a source revert/rebuild while avoiding a second compilation of identical old production code.

2026-11-04T21:03:36.161760+00:00 OBSERVED: Final source/test passes found one added-stage coverage gap, now drafted as two public parameter cases at root's request. OBSERVED: No JVM/test/build/git mutation or product edit was performed by this reviewer. INFER: Next checkpoint is root's parent-stage red/green and final fixed/old-production control counts, then the review can be closed with those observed dispositions.

2026-11-04T21:09:53.777161+00:00 OBSERVED: Independently read green-register.json: 56/56 first-attempt passes, zero errors/skips. OBSERVED: Independently read revert-red.json: four assertion failures and zero passes/errors/skips; parent-only failed its expected-error assertion and child-plus-parent failed parent close count 0 versus 1, exactly as predicted. OBSERVED: revert-control.json records original baseline jar first, its expected SHA-256, identical remaining runtime entries and test class hashes with no test recompilation between runs. OBSERVED: The parent-failure draft and product test are byte-identical; README/FORK/build version updates consistently describe UDP parent cleanup before group termination. OBSERVED: Addendum scan remains empty. INFER: Old-product control is valid; fixed neighbor-case green results remain the only pending test coverage disposition in this review.

## Final disposition at tested product head

Reviewed final product [e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe), based on the existing acceptor-cleanup head [23d66d4110465edb1ae8d83e099266a42ae77f41](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/23d66d4110465edb1ae8d83e099266a42ae77f41). Root confirmed production code is unchanged since the final whole-file source pass; the later edits only update README/FORK and the build version comment to describe the same behavior. Those documentation changes were read and match the implemented public shutdown contract. No existing default-branch test is changed.

| Review item | State and proof |
| --- | --- |
| UDP ownership after child removal | Done: QuicTransportClosedChildParentTest fails its physical-parent assertion on old product and passes fixed; activeConnections remains zero after child close |
| Deterministic callback-initiated shutdown | Done: held final child close and sibling loop barriers produce an assertion red on old source; the identical test passes fixed |
| Newly added parent-stage failure branch | Done: QuicTransportFailedDatagramCloseTest covers parent-only and combined child/parent failures; old product fails the expected-error or exact close-count assertion and fixed product passes both |
| Larger simultaneous close sets | Done: two TCP listeners/eight roots and four QUIC children/four UDP parents are included in the 56-case register |
| Original failure identity and stage priority | Done: public tests retain exact child failure when present, otherwise exact parent failure; all physical resources/group/threads end before fixture cleanup |
| Caller future mutation and independent transport lifetime | Done: the original parameter cases cover mutation; the larger QUIC/neighbor tests leave an independent server group/listener alive |
| Source review of new locking and cleanup stages | Done: snapshots and registration recheck use the transport monitor; physical close and group shutdown execute after releasing it; final joins observe completed predecessors |
| In-flight bind-registration static question | Not a confirmed product finding: the raw bind precedes registration in the existing asynchronous shape, while the new path adds the closed recheck and explicit parent close. No new observed public failure or resource leak was established; it does not invalidate the measured captured-parent fix |
| Fix-removed control validity | Done: baseline-product.json and revert-control.json identify exact original production bytes first on the classpath, identical final tests/dependency remainder, and no test recompilation between fixed/control runs |

OBSERVED: Independently read the final gate records. `green-register.json` is 56/56 first-attempt passes, zero failures/skips. `green-extra.json` is 4/4 first-attempt passes, zero failures/skips. `revert-red.json` is 0/4 first-attempt passes with four intended assertion failures and zero errors/skips. Its two original cases fail on pending physical UDP close futures; parent-only fails because no expected parent failure is reported; combined child/parent fails because the parent close handler is called zero times instead of once. The test bytes and dependency remainder are unchanged across the fixed and old-product runs.

The dedicated test-comprehensiveness pass and separate adversarial source pass are complete. The added-stage coverage finding is closed by its public red/green cases. No confirmed implementation bug remains from this review; this review approves the tested source and test coverage, while required Actions and publication verification remain root-owned execution gates.

2026-11-04T21:15:13.918670+00:00 OBSERVED: Final review disposition is complete at e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe, with independently read 56/56 register, 4/4 neighbor green and 0/4 old-product assertion controls. OBSERVED: This reviewer started no JVM/build/test/background process, changed no product source and made no git mutation. INFER: Root can use this completed review for its required Actions and exact-head publication gates; nothing further is expected from this reviewer.

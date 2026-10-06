# Second production review of stream closure settlement

PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/45

Reviewed head: `337601406b88f972a9e9ad389b74a76556655918`.
Production delta: `2e0a0dce...33760140`. Whole diff: `origin/develop...33760140`; there is no `origin/main` in this repository.

OBSERVED: README.md and FORK.md read; no repository AGENTS.md exists. Required reading: [TESTING.md](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md), [PHILOSOPHY.md](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md), companion CODE_REVIEW.md, [first review report](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/review/rvLP45-production/review-notes/rvLP45-production-review.md), and the round-one brief/findings. The lane instruction making kompile-remote-build non-gating controls over TESTING.md's general remote-check rule; this phase requires local review only.

## Ownership and terminal states

| Resource | Owner and lifetime | Allowed outcomes |
| --- | --- | --- |
| Protocol future | StreamOverNetty installs the stream attribute; channel owns it | Exactly one result; selection success survives later close |
| Controller future | ProtocolSelect creates selectedFuture; MuxHandler forwards it to caller | Exactly one success/failure; closure fails pending initialization, late success cannot replace failure |
| New closure observer | Multistream init registers a dependent on the cached CHILD close future | Stays until child closes; later completion attempts are inert after success; no parent listener added by this change |
| Selector and parent-close listener | Pipeline owns selector; AbstractChildChannel owns its parent listener | Selector removed after controller settles; child teardown removes parent listener and all handlers |

No persistence/restart behavior changes. Reentrant closure and closure during pending initialization must preserve terminal outcomes.

## Five review rows

| Row | Source evidence and planned public verification |
| --- | --- |
| 1. All future outcomes, exactly once | MultistreamImpl.kt:25 observes closure independently of pipeline callbacks; :26/:30/:47 give synchronous fast paths; :57-59 completes protocol and controller with ConnectionClosedException naming closure. ProtocolSelect.kt:68 publishes selected protocol, :73 forwards controller initialization, :82-92 handles failure/unregister. MuxHandler.kt:52-58 forwards the controller to StreamPromise. CompletableFuture completion preserves prior outcomes. Existing Host tests cover immediate/deferred callbacks; lifecycle tests cover negotiation/parent death and selected-but-pending controller. Additional public experiments check one notification per future, success preserved by ordinary close, and closure failure preserved against late initialization success. |
| 2. No installation gap; ordinary close is inert after selection | Observer is attached at MultistreamImpl.kt:25 before preHandler or any pipeline mutation. Close between method entry and attachment is replayed by the already-completed cached future; close after attachment invokes it. Netty AbstractChannel.java:615-623 marks its close future successful even when doClose throws. ProtocolSelect.kt:28-30 is a supplementary guard, independent of the observer. Observer is not removed at selection, but completeExceptionally cannot replace successful protocol/controller results. |
| 3. Bounded resource lifetime | P2PChannelOverNetty.kt:20/:35 caches the child future; NettyExt.kt:12-21 creates one bridge listener on that child. Multistream adds one thenRun dependent and its result future per initialization, with no additional pipeline handler. No observer is added to the parent future. AbstractChildChannel.kt:160 removes its existing parent-close listener on child teardown. ProtocolSelect.kt:55-58 removes the selector when controller initialization settles. Public experiment will count child and parent CompletableFuture dependents and handlers over128 ordered open/negotiate/close cycles per muxer. |
| 4. Callback locking and thread behavior | No lock, blocking wait, new executor or async hop introduced in MultistreamImpl.kt:19-60. Cached future's lazy lock is released before thenRun attachment. Netty DefaultPromise.java:552-589 snapshots/clears listeners under synchronized, calls them outside it; :498-519 delivers on its executor without awaiting the event loop. Java CompletableFuture completion is atomic and invokes continuations without an application lock. ProtocolSelect's existing removal callback only removes/schedules pipeline work, never waits. Public selected-controller experiment reenters reset from the failure callback. |
| 5. Live path unchanged | Same preHandler -> same requester/responder initializer with same bindings/time limit -> same postHandler -> ProtocolSelect placement at MultistreamImpl.kt:27-48. Early selector construction does not install it early. False closure checks emit no action; pending close observer does no work before closure. No negotiation messages, delays, selection policy, timers or executors changed. Public many-stream experiment uses real Host/Ping for both muxers and asserts selected result/controller identity survive closure. |

OBSERVED: Source trace complete, no production defect concluded. Local lifecycle run and public resource/outcome experiments pending. This is a review branch; production remains unchanged and is not to be merged.

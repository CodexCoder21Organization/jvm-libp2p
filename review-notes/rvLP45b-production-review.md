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

| Row | Source evidence and public verification |
| --- | --- |
| 1. All future outcomes, exactly once | MultistreamImpl.kt:25 observes closure independently of pipeline callbacks; :26/:30/:47 give synchronous fast paths; :57-59 completes protocol and controller with ConnectionClosedException naming closure. ProtocolSelect.kt:68 publishes selected protocol, :73 forwards controller initialization, :82-92 handles failure/unregister. MuxHandler.kt:52-58 forwards the controller to StreamPromise. CompletableFuture completion preserves prior outcomes. Existing Host tests cover immediate/deferred callbacks; lifecycle tests cover negotiation/parent death and selected-but-pending controller. Passing public experiments check one notification per future, success preserved by ordinary close, and closure failure preserved against late initialization success. |
| 2. No installation gap; ordinary close is inert after selection | Observer is attached at MultistreamImpl.kt:25 before preHandler or any pipeline mutation. Close between method entry and attachment is replayed by the already-completed cached future; close after attachment invokes it. Netty AbstractChannel.java:615-623 marks its close future successful even when doClose throws. ProtocolSelect.kt:28-30 is a supplementary guard, independent of the observer. Observer is not removed at selection, but completeExceptionally cannot replace successful protocol/controller results. |
| 3. Bounded resource lifetime | P2PChannelOverNetty.kt:20/:35 caches the child future; NettyExt.kt:12-21 creates one bridge listener on that child. Multistream adds one thenRun dependent and its result future per initialization, with no additional pipeline handler. No observer is added to the parent future. AbstractChildChannel.kt:160 removes its existing parent-close listener on child teardown. ProtocolSelect.kt:55-58 removes the selector when controller initialization settles. Passing public experiment counts two child dependents while live (Host table removal + negotiation observer), zero after close, no selector/all handlers removed, unchanged parent CompletableFuture count over128 ordered Echo round trips per muxer. FINDING: synchronous visitor closure during registration leaves the pre-existing parent Netty close listener armed after child teardown; see below. |
| 4. Callback locking and thread behavior | No lock, blocking wait, new executor or async hop introduced in MultistreamImpl.kt:19-60. Cached future's lazy lock is released before thenRun attachment. Netty DefaultPromise.java:552-589 snapshots/clears listeners under synchronized, calls them outside it; :498-519 delivers on its executor without awaiting the event loop. Java CompletableFuture completion is atomic and invokes continuations without an application lock. ProtocolSelect's existing removal callback only removes/schedules pipeline work, never waits. Passing public selected-controller experiment reenters reset from the failure callback. |
| 5. Live path unchanged | Same preHandler -> same requester/responder initializer with same bindings/time limit -> same postHandler -> ProtocolSelect placement at MultistreamImpl.kt:27-48. Early selector construction does not install it early. False closure checks emit no action; pending close observer does no work before closure. No negotiation messages, delays, selection policy, timers or executors changed. Passing public many-stream experiment uses real Host/Echo for both muxers and asserts selected result/controller identity survive closure. |

## Finding: visitor-closed children remain referenced by the live parent

OBSERVED: The mechanism is `libp2p/src/main/kotlin/io/libp2p/etc/util/netty/AbstractChildChannel.kt:92` invoking initialization before the parent-close listener is registered at `:108`. A synchronous Host stream visitor resets the child during that initialization; `completeTeardown()` at `:160` removes the listener before it has been added. Registration then continues and adds it after teardown. The listener at `:39` captures the child, so every such closed child stays reachable from the parent close future until that connection closes. The evidence is the deterministic public real-Host test for each muxer: 128 closed streams leave 129 registered listeners on the still-live parent, versus the baseline of 1. Both futures' exact ConnectionClosedException/full closure messages, empty Host.streams and live-parent assertions pass before the intended count failure.

OBSERVED: This is unchanged owner code adjacent to the production delta, exercised by the PR's fixed visitor-close behavior. The new child-close observer itself drains on child closure; it does not add a per-stream observer to the parent. Ordinary post-negotiation close releases resources correctly. The finding is the remaining registration/teardown ordering gap for synchronous initialization closure, rather than normal-path accumulation introduced by `thenRun`.

OBSERVED: Reproduction: start two real loopback Hosts; client uses a real TcpTransport subclass dialing real NioSocketChannels and ordinary ConnectionBuilder upgrades. The socket's real close future is wrapped to count add/remove registrations by identity; every future operation delegates to Netty. No result, close or callback is fabricated. Connect once; record count; install a public Host visitor that resets every local child; open 128 streams; assert both full closure failures and all children removed from Host; require parent listener count unchanged. Both muxers fail `ParentCloseListenerReviewTest.kt:92` with `expected: <1> but was: <129>`. No sleeps, retries, reflection, GC-based timing or machine calibration. This forces the exact ordering each time; it is not a timing race reproducer.

## Local verification

OBSERVED: Once-only required lifecycle run at assigned production head: four classes executed9/PASS9/FAIL0/ERROR0/SKIP0; existing deferred-callback review class executed2/PASS2. Total11/11, Gradle8m10s. Runner: `./gradlew --no-daemon :libp2p:test --tests io.libp2p.core.HostStreamVisitorCloseTest --tests io.libp2p.core.HostHandlerAddedCloseReviewTest --tests io.libp2p.multistream.NegotiationControllerCompletesOnEarlyCloseTest --tests io.libp2p.mux.yamux.YamuxNegotiationControllerOnStreamCloseTest --tests io.libp2p.mux.yamux.YamuxSelectedProtocolControllerOnCloseTest`.

OBSERVED: Review experiments at `4abeedc0`, production unchanged from assigned head: `./gradlew --no-daemon :libp2p:test --tests io.libp2p.core.NormalStreamLifetimeReviewTest --tests io.libp2p.core.LateControllerCompletionReviewTest --tests io.libp2p.core.ParentCloseListenerReviewTest`. XML executed6/PASS4/FAIL2/ERROR0/SKIP0; Gradle1m10s. The two failures are precisely the parent-listener count assertions, one per muxer. The four passing cases cover256 successful Echo round trips/ordinary close cycles and both delayed-controller/reentrant-close/late-success cases. They are review verification, not claimed bug reproducers.

OBSERVED: Separate review of tests confirms real Host APIs, real TCP/Netty dependencies, delegated future behavior for instrumentation, full cause assertions, exact counts with event-loop barriers, finite128-cycle workloads and finally cleanup. All production/README files remain byte-identical to assigned head. No original PR changes, production fixes, agents, remote runs, queue operations, merge, publish, deployment or restart. No timeout/iteration/assertion weakened. No extra test batch after the prescribed gate and these review experiments.

REVIEWS-DONE head=337601406b88f972a9e9ad389b74a76556655918 verdict=FINDINGS


## Full failure stacks

### yamuxVisitorClosedChildrenReleaseParentCloseListeners()

```text
org.opentest4j.AssertionFailedError: 128 closed children must leave no parent-close listener behind on their live connection ==> expected: <1> but was: <129>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at app//org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at app//io.libp2p.core.ParentCloseListenerReviewTestKt.reviewParentCloseListeners(ParentCloseListenerReviewTest.kt:92)
	at app//io.libp2p.core.ParentCloseListenerReviewTestKt.access$reviewParentCloseListeners(ParentCloseListenerReviewTest.kt:1)
	at app//io.libp2p.core.ParentCloseListenerReviewTest.yamuxVisitorClosedChildrenReleaseParentCloseListeners(ParentCloseListenerReviewTest.kt:43)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
```

### mplexVisitorClosedChildrenReleaseParentCloseListeners()

```text
org.opentest4j.AssertionFailedError: 128 closed children must leave no parent-close listener behind on their live connection ==> expected: <1> but was: <129>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at app//org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at app//io.libp2p.core.ParentCloseListenerReviewTestKt.reviewParentCloseListeners(ParentCloseListenerReviewTest.kt:92)
	at app//io.libp2p.core.ParentCloseListenerReviewTestKt.access$reviewParentCloseListeners(ParentCloseListenerReviewTest.kt:1)
	at app//io.libp2p.core.ParentCloseListenerReviewTest.mplexVisitorClosedChildrenReleaseParentCloseListeners(ParentCloseListenerReviewTest.kt:38)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
```

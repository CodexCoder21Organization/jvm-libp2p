# Production review of stream visitor closure

Reviewed pull request: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/45
Assigned production head: 2e0a0dce75b608d91e71ffc0f43a822525249354

OBSERVED: The whole diff contains README.md, MultistreamImpl.kt, ProtocolSelect.kt, and HostStreamVisitorCloseTest.kt. README and FORK.md were read; no repository AGENTS.md exists. Required documents read: https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md and https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md; the companion CODE_REVIEW.md was also consulted. Provenance reviewed: fx1203m findings, owner-invariants.md, owner-mechanism.md, and owner-baseline.xml at https://github.com/CodexCoder21Organization/UrlResolver/tree/work/fx1203m-verification-records/handoff-artifacts/fx1203m. The saved baseline has two failures at HostStreamVisitorCloseTest.kt:58, one per muxer. No evidence links this condition to the earlier second-RPC timeout.

## Invariant trace

| Row | Evidence and result |
| --- | --- |
| 1. Every protocol/controller future settles once with the correct outcome | MultistreamImpl.kt:22,26,43 checks closure before setup and after each initialization callback; :53-55 fails the Stream protocol and selected controller with the same descriptive ConnectionClosedException. MuxHandler.kt:52-58 forwards this controller to the StreamPromise. ProtocolSelect.kt:68 completes the selected protocol on success; :73 forwards asynchronous controller initialization; :82-92 fails still-pending futures on error/unregister. CompletableFuture preserves an earlier success/failure against later completion attempts. FINDING: the registration callback ordering below leaves both futures pending in both real-host muxer cases. |
| 2. Close is ordered against installation | AbstractMuxHandler.kt:375 registers the child on the parent event loop, and :386-399 executes outbound setup there; inbound creation runs from the parent's read path. AbstractChildChannel.kt:92 and MuxChannel.kt:38-40 run initialization during doRegister. Thus checks are on-loop, but this alone does not cover deferred handlerAdded callbacks after initChannel returns. FINDING: ProtocolSelect.kt:28-30 cannot settle a context removed before handlerAdded; the public review tests demonstrate this. |
| 3. Already-installed selector uses existing lifecycle settlement | The brief names channelInactive, but ProtocolSelect has no channelInactive override. The actual path is channelUnregistered at ProtocolSelect.kt:88-92, reached from AbstractChildChannel.kt:179 after :186 fires inactive. Installed live selectors see a false handlerAdded closure check, then one unregister settlement; pipeline destruction removes the handler, so later deferred unregister traverses an empty pipeline. This wording correction is not itself a production defect. |
| 4. Live negotiation result and ordering remain unchanged | The live path still calls preHandler, adds the same requester/responder initializer with the same protocols/time limit, calls postHandler, and adds ProtocolSelect last. Creating the selector earlier does not install it earlier or copy/change bindings. The new helper returns false on a live channel, and handlerAdded has no action there. No new task, wait, retry, timeout, wire message, or selection rule was introduced for that path. |
| 5. New tests force visitor closure and assert full cause/liveness | HostStreamVisitorCloseTest.kt:16-22 runs both muxers; :27-40 creates real loopback hosts on port 0; :46-56 selects and resets the exact second local stream. :62-68 asserts parent/sibling/replacement progress. StreamPromise.stream is published after the initializer and controller forwarding return (AbstractMuxHandler.kt:389-396, MuxHandler.kt:53-57), so these immediate checks are ordered rather than timing-calibrated. No sleeps or mocks. FINDING: :58-61 only checks isCompletedExceptionally; neither future's ConnectionClosedException nor full closure message is asserted. |
| 6. README note is accurate | README.md:59 describes synchronous visitor reset, which the existing fail-first tests demonstrate. Stream.kt:reset delegates to close, so those operations share behavior. The note's broad wording about a visitor closing a child must also hold when its installed handler closes during registration; the added review sequence demonstrates a separate gap for closure from a handler installed by the visitor. The direct synchronous-reset wording itself matches the original passing cases. |

## P1: Close during deferred handler installation leaves both futures pending

OBSERVED: The mechanism is that a Host stream visitor calls Stream.pushHandler with an ordinary ChannelInboundHandlerAdapter whose handlerAdded callback resets that exact stream. The visitor itself returns while the child is still open, so MultistreamImpl's final check passes and ProtocolSelect is appended. On registration completion, the earlier handlerAdded callback closes the child before ProtocolSelect's pending handlerAdded callback runs. Netty skips inbound unregister events for an ordered ADD_PENDING context, destroys that context, and suppresses handlerAdded once it is REMOVE_COMPLETE. Neither ProtocolSelect settlement path runs and both negotiation futures remain pending on a live shared parent. The evidence is the pinned Netty lifecycle source and the two public fail-first review cases, both failing at both pending-future assertions after parent/sibling/replacement progress passed.

OBSERVED: This ordering is derived from the pinned Netty 4.2.10.Final sources: DefaultChannelPipeline.java:188-194 queues additions before registration, AbstractChannel.java:378-384 invokes pending added callbacks after doRegister, AbstractChannelHandlerContext.java:965-988 refuses added callbacks for removed contexts, and :1013-1017 skips events for ordered ADD_PENDING contexts. The real-host tests reproduce this defect on the reviewed production head without timing calibration or sleeps.

## Verification

OBSERVED: The required four-class local run completed once at exact production head 2e0a0dce75b608d91e71ffc0f43a822525249354: executed 9, passed 9, failures 0, errors 0, skipped 0. Command: `./gradlew --no-daemon :libp2p:test --tests io.libp2p.core.HostStreamVisitorCloseTest --tests io.libp2p.multistream.NegotiationControllerCompletesOnEarlyCloseTest --tests io.libp2p.mux.yamux.YamuxNegotiationControllerOnStreamCloseTest --tests io.libp2p.mux.yamux.YamuxSelectedProtocolControllerOnCloseTest`.

OBSERVED: Review test run at cb7b16c7f9ee88c282d2a143267ebd8ed28d2433, whose production tree is unchanged from the assigned head: `./gradlew --no-daemon :libp2p:test --tests io.libp2p.core.HostHandlerAddedCloseReviewTest`. XML executed 2, failures 2, errors 0, skipped 0. Both failures contain both expected `done=false` assertions. Before those assertions, the exact victim's close callback, child close future, parent's live state, first sibling ping, and same-parent replacement ping all passed. No extra verification batches were run.

OBSERVED: P1 file locations: `libp2p/src/main/kotlin/io/libp2p/multistream/MultistreamImpl.kt:43` checks closure before deferred registration callbacks; `ProtocolSelect.kt:28` relies on a callback that teardown can suppress. Review trigger: `libp2p/src/test/kotlin/io/libp2p/core/HostHandlerAddedCloseReviewTest.kt:57`; both future assertions: :81 and :95. P2 coverage gap: `libp2p/src/test/kotlin/io/libp2p/core/HostStreamVisitorCloseTest.kt:58` and :60 fail to assert cause type and full message.

OBSERVED: Separate review of the added test confirms public Host/Stream/Ping calls on real loopback transports, both muxers, an exact victim identity signal, real reset, live shared parent/sibling/replacement checks before failure, both failed futures independently reported by assertAll, full cause assertions for a corrected implementation, and host/stream cleanup in finally. The failure is neither a fixture error nor a timeout. The first registration callback forces the problematic ordering on every host; no concurrency/timeout calibration is involved.

OBSERVED: No production source changed, no original PR branch updated, no new production PR created, and no queue/merge/deployment/publication operation performed. Two setup attempts were stopped before test execution: a wrong selector in the original invocation, and a review invocation after missing Git identity prevented the required commit/rebase. The completed test counts above come from the corrected runs only.

REVIEWS-DONE head=2e0a0dce75b608d91e71ffc0f43a822525249354 verdict=FINDINGS


## Full review failure stacks

### yamuxHandlerAddedCloseSettlesNegotiation()

```text
org.gradle.internal.exceptions.DefaultMultiCauseException: Multiple Failures (2 failures)
	org.opentest4j.AssertionFailedError: The controller future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	org.opentest4j.AssertionFailedError: The selected protocol future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:80)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:44)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:38)
	at app//org.junit.jupiter.api.Assertions.assertAll(Assertions.java:2944)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:81)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.access$checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:1)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTest.yamuxHandlerAddedCloseSettlesNegotiation(HostHandlerAddedCloseReviewTest.kt:28)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	Suppressed: org.opentest4j.AssertionFailedError: The controller future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
		at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
		at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
		at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
		at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
		at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-1(HostHandlerAddedCloseReviewTest.kt:82)
		at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
		at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
		at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
		at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
		... 9 more
	Suppressed: org.opentest4j.AssertionFailedError: The selected protocol future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
		at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
		at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
		at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
		at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
		at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-2(HostHandlerAddedCloseReviewTest.kt:83)
		at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
		at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
		at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
		at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
		... 9 more
Cause 1: org.opentest4j.AssertionFailedError: The controller future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-1(HostHandlerAddedCloseReviewTest.kt:82)
	at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:44)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:38)
	at app//org.junit.jupiter.api.Assertions.assertAll(Assertions.java:2944)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:81)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.access$checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:1)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTest.yamuxHandlerAddedCloseSettlesNegotiation(HostHandlerAddedCloseReviewTest.kt:28)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
Cause 2: org.opentest4j.AssertionFailedError: The selected protocol future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-2(HostHandlerAddedCloseReviewTest.kt:83)
	at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:44)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:38)
	at app//org.junit.jupiter.api.Assertions.assertAll(Assertions.java:2944)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:81)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.access$checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:1)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTest.yamuxHandlerAddedCloseSettlesNegotiation(HostHandlerAddedCloseReviewTest.kt:28)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
```

### mplexHandlerAddedCloseSettlesNegotiation()

```text
org.gradle.internal.exceptions.DefaultMultiCauseException: Multiple Failures (2 failures)
	org.opentest4j.AssertionFailedError: The controller future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	org.opentest4j.AssertionFailedError: The selected protocol future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:80)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:44)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:38)
	at app//org.junit.jupiter.api.Assertions.assertAll(Assertions.java:2944)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:81)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.access$checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:1)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTest.mplexHandlerAddedCloseSettlesNegotiation(HostHandlerAddedCloseReviewTest.kt:23)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	Suppressed: org.opentest4j.AssertionFailedError: The controller future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
		at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
		at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
		at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
		at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
		at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-1(HostHandlerAddedCloseReviewTest.kt:82)
		at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
		at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
		at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
		at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
		... 9 more
	Suppressed: org.opentest4j.AssertionFailedError: The selected protocol future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
		at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
		at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
		at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
		at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
		at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
		at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-2(HostHandlerAddedCloseReviewTest.kt:83)
		at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
		at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
		at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
		at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
		at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
		at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
		... 9 more
Cause 1: org.opentest4j.AssertionFailedError: The controller future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-1(HostHandlerAddedCloseReviewTest.kt:82)
	at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:44)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:38)
	at app//org.junit.jupiter.api.Assertions.assertAll(Assertions.java:2944)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:81)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.access$checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:1)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTest.mplexHandlerAddedCloseSettlesNegotiation(HostHandlerAddedCloseReviewTest.kt:23)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
Cause 2: org.opentest4j.AssertionFailedError: The selected protocol future must fail when handlerAdded closes the child before negotiation; done=false ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.assertReviewClosureFailure(HostHandlerAddedCloseReviewTest.kt:95)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose$lambda-2(HostHandlerAddedCloseReviewTest.kt:83)
	at app//org.junit.jupiter.api.AssertAll.lambda$assertAll$0(AssertAll.java:68)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base@21.0.12.1/java.util.stream.ReduceOps$ReduceOp.evaluateSequential(ReduceOps.java:921)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline.collect(ReferencePipeline.java:682)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:77)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:44)
	at app//org.junit.jupiter.api.AssertAll.assertAll(AssertAll.java:38)
	at app//org.junit.jupiter.api.Assertions.assertAll(Assertions.java:2944)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:81)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTestKt.access$checkHandlerAddedClose(HostHandlerAddedCloseReviewTest.kt:1)
	at app//io.libp2p.core.HostHandlerAddedCloseReviewTest.mplexHandlerAddedCloseSettlesNegotiation(HostHandlerAddedCloseReviewTest.kt:23)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
```

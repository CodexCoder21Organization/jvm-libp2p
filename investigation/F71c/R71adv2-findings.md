# Exact-head adversarial review
Started 2026-10-24 17:49 UTC. Deadline 19:04 UTC; runtime cutoff 18:44 UTC.
OBSERVED: PR head matches 2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6, base develop, state OPEN.

Plan:
- [done] Fresh clone, exact checkout, immediate background lifecycle test.
- [done] Read required evidence, repository instructions, philosophy and complete diff.
- [done] Trace preparation, handshake, close and parent-write ownership; audit six failure classifications.
- [done] Run seven requested classes once, sequentially; record per-class XML.
- [done] Probe parent-write rejection and depth-limited close; review description/documentation/artifacts.
- [done] Stop owned processes, verify process state and deliver review.

## Final review findings

1. SHOULD_FIX — libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt:47-49. Already-closed connection close can remain pending on its own loop when eight nested real promise notifications force Netty to defer the newly attached close listener into a full live queue. Real two-host probe confirms pending after queue recovery. Preserve actual close-listener reentry ordering while making unrelated in-loop terminal observation independent of rejectable listener dispatch.

2. SHOULD_FIX — libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/MuxChannel.kt:117. Off-loop parent listener rejection is logged by Netty, leaving the child write, later writes and a payload pending until explicit close. A deterministic real local-channel/custom-public-muxer probe confirms it. Stock Mplex/Yamux use same-loop parent promises; custom onChildWrite implementations can return a future on another executor. Add loop-owned settlement/retained-outcome handling for that rejected continuation, or enforce a clear executor contract before accepting the write.

3. SHOULD_FIX — F68c-final.md:10 and :27; F68c-findings.md:553. The specific final GossipTwoHost publish timeout is labeled LOAD_SENSITIVE, but the displayed develop run failed at bind before the body, the earlier develop reference is a receive timeout, and the formatter profile is explicitly from a prior head. Those prove different inherited problems in the same method, not the stated classification of this publish failure. Mark the exact failure unattributed or add a matching develop publish-path reproduction/profile. No PR71 cause or worsening is established by these historical records.

4. NIT — libp2p/src/main/kotlin/io/libp2p/etc/types/NettyExt.kt:15 and five added test files. Real scoped repository Spotless check confirmed10 Kotlin inputs, then failed on six files. New converter transfer if/else branches need braces; new tests have import, function-signature and call-layout differences. Apply the configured formatter only to changed files, then rerun its check. No source edit or formatter apply was performed by this reviewer.

The two code findings are inherited cases still present in the changed guards; neither is claimed as a new default-muxer regression. Reproduction source, full logs and reachability analysis follow below.

OBSERVED: Fresh checkout is detached at exact head. Diff has 13 changed paths: four production files, two prose files, root version assignment, six entirely new test files. No existing tests are modified and no original test bound/iteration is changed. README describes the terminal-result fast path and explicitly retains listener-executor dependence for pending operations. PR description opens with the originating UrlProtocol failure, links handoff and review controls, states Actions does not execute tests, says existing tests unchanged, and ends with the required Claude Code footer.
OBSERVED: Read README, CLAUDE (no AGENTS), full original FL635 records and F71b/F71b2 evidence plus both previous final reviews. Current code confirms Noise preparation is inherited unchanged from develop; only future conversion, completed physical-close observation and stream-write continuation differ in production.
OBSERVED: Preparation: Builder registers one function if any binding descriptor announces or matches Noise; transport snapshots are taken after registration. Per-transport synchronized lazy starts one daemon libp2p-transport-preparation thread. Process-wide synchronized lazy serializes successful algorithm setup across hosts. Threads finish with preparation and do not own a persistent pool. Network constructor invokes initialize; outside Netty callbacks it joins and unwraps the original preparation cause. Inside Netty callbacks it returns and ready/start/listen/dial compose futures. Host stop marks terminal stopped, closes transport, and fails readiness outside its monitor; later preparation completion cannot replace that terminal result.
INFER: MuxChannel off-loop rejection can retain waitingForParentWrite and its outbound buffer indefinitely; built-in muxers create parent aggregate promises on the parent context executor, normally the same event loop as the child. Public custom muxers can return promises on other executors. Probe will distinguish behavior and reachability instead of assuming callback completion thread changes its executor.

OBSERVED: Artifact audit: no new artifacts in this PR. Three inherited investigation files remain: .evidence/FURR3/binding-class-hashes-before.txt (411 bytes), .evidence/FURR3/findings.md (10992 bytes), .evidence/FURR3/stream-descriptors-before.txt (1328 bytes). Gradle wrapper JAR (43764 bytes) is intentional build infrastructure. No AGENTS.md exists anywhere in checkout.
OBSERVED: Classification audit read F68c-final and F68c-findings, including native develop and final traces. All six methods have develop failure observations: three subscription bind errors, Kubo connection refusal, Noise starvation timing (p95 1040ms with all644 tasks off-loop), and GossipTwoHost bind failure plus prior-round develop receive timeout. Gossip's specific final publish TimeoutException at line34 is NOT reproduced by the displayed develop control, which fails before its body; the formatter profile is expressly from a prior PR-head run. Its exact publish-cause attribution remains INFER. These observations predate this PR71 future-conversion/close/write changes, so this PR cannot have caused those historical failures.
INFER: A second close gap may remain: the in-event-loop branch assumes adding a completed closeFuture listener can run or join existing notification. Netty limits nested listener depth. A terminal close observed from an unrelated deeply nested promise callback may need dispatch even on its event loop, and a full queue can reject it. A standalone real-host probe is prepared outside the checkout. No production changes made.

Probe invariants (no production implementation changes):
| Resource | Owner and terminal state | Ordering / hostile caller case |
|---|---|---|
| Parent write promise | Custom public muxer implementation owns Netty promise on a real separate executor | Promise succeeds; child's outbound write must settle or fail, including child-loop submission rejection. Caller does not mutate library state. |
| Child buffer and payload | MuxChannel owns accepted ByteBuf; parent releases its own retained reference | Release exactly once when settled or closed; queue recovery and another public write/flush must not leave old result permanently pending. |
| Event loops | Probe owns local transport loop and separate real DefaultEventExecutor; finally shuts down both | Hold exact loop turn with latch, fill bounded actual queue until rejection, release and await real drain/barriers. No load spinner or host-calibrated rate. |
| Completed physical close | Two real TCP hosts own sockets; Connection owns observation future | Peer closes physically first. An unrelated chain of eight real promise callbacks calls public close on the owning loop while its queue is full. After recovery it must settle; stop must not inherit a lost notification. |
| Probe processes | Reviewer starts bounded Java commands outside checkout | No branch changes; process exit and explicit loop/host cleanup; all waits bounded. |
OBSERVED: 18:01 UTC: lifecycle command is still in compileKotlin, not test execution. A separate classpath-only Gradle command succeeded after 3m13s; no test selected by that command. Both commands are bounded at25 minutes and this task has started no other Gradle work. Owned compiler PID2931682 and single-use Gradle2928276 recorded for cleanup. No CI requested, inspected as a gate, rerun or watched.
OBSERVED: Read all changed functions with their complete surrounding files; traced TCP and WebSocket inbound/outbound ConnectionBuilder -> ConnectionUpgrader -> negotiated Noise binding. All role/state, framing, cipher, key generation and mux setup code is unchanged from develop. Reviewed all converter call sites (plain/QUIC transport operations, stream close/write and P2P close observation) for newly synchronous terminal completion; no additional demonstrated defect there yet.
OBSERVED: Netty4.2.10 DefaultPromise bytecode confirms the proposed deep-close trigger: inEventLoop alone does not guarantee inline listener dispatch. futureListenerStackDepth>=8 calls safeExecute even on the owning loop; safeExecute catches the rejection and logs it without failing or rescheduling the listener.
OBSERVED: OffLoopParentWriteProbe FIRST ATTEMPT reproduced the requested path at exact compiled head (13,360ms, probe exits0 because it asserts the defect exists). Public AbstractMuxHandler subclass returns a real DefaultChannelPromise whose listener executor is a separate real DefaultEventExecutor; parent and child are real active local transport channels. Hold child's real loop, fill its16-slot bounded queue, succeed parent promise on its own executor, release/drain child's queue and await fresh barrier. Netty logs the rethrown RejectedExecutionException at MuxChannel.kt:113/117. Output: queueAccepted=16 parentSuccess=true childDoneAfterRecovery=false payloadRefCnt=1; childDoneAfterAnotherFlush=false laterWriteDone=false; explicit child.close changes first result to failed and releases payload to0. No close or failure is triggered automatically by the rejected continuation. waitingForParentWrite stays true and blocks subsequent doWrite. The outer doWrite catch cannot observe an exception thrown from a listener invoked later on another executor; Netty logs that listener exception.
OBSERVED: Reachability distinction: stock MplexHandler creates its aggregate with ctx.newPromise and PromiseCombiner(ctx.executor); Yamux's PendingWrite does the same, and AbstractMuxHandler registers children on parent.eventLoop. Their normal configured parent listener therefore runs on the child loop; merely completing that promise from another thread does NOT move its listener off-loop. The demonstrated off-loop branch is reachable by a public custom AbstractMuxHandler/onChildWrite implementation returning a promise on another executor; no documented same-executor precondition forbids it. This is inherited behavior retained at the newly changed catch, not a new regression in default Mplex/Yamux. Finding SHOULD_FIX, scoped to that reachability.
18:03:28.307 [defaultEventExecutor-3-1] [[33mWARN[m] io.netty.util.concurrent.DefaultPromise - An exception was thrown by io.libp2p.etc.util.netty.mux.MuxChannel$$Lambda/0x00007e4f28171550.operationComplete()
java.util.concurrent.RejectedExecutionException
	at io.netty.util.concurrent.RejectedExecutionHandlers$1.rejected(RejectedExecutionHandlers.java:31)
	at io.netty.util.concurrent.SingleThreadEventExecutor.reject(SingleThreadEventExecutor.java:1134)
	at io.netty.util.concurrent.SingleThreadEventExecutor.addTask(SingleThreadEventExecutor.java:407)
	at io.netty.util.concurrent.SingleThreadEventExecutor.execute(SingleThreadEventExecutor.java:1027)
	at io.netty.util.concurrent.SingleThreadEventExecutor.execute0(SingleThreadEventExecutor.java:993)
	at io.netty.util.concurrent.SingleThreadEventExecutor.execute(SingleThreadEventExecutor.java:983)
	at io.libp2p.etc.util.netty.mux.MuxChannel.doWrite$lambda-1(MuxChannel.kt:113)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at io.netty.channel.DefaultChannelPromise.setSuccess(DefaultChannelPromise.java:78)
	at io.netty.channel.DefaultChannelPromise.setSuccess(DefaultChannelPromise.java:73)
	at OffLoopParentWriteProbe.lambda$main$4(OffLoopParentWriteProbe.java:70)
	at io.netty.util.concurrent.PromiseTask.runTask(PromiseTask.java:98)
	at io.netty.util.concurrent.PromiseTask.run(PromiseTask.java:106)
	at io.netty.util.concurrent.AbstractEventExecutor.runTask(AbstractEventExecutor.java:148)
	at io.netty.util.concurrent.DefaultEventExecutor.run(DefaultEventExecutor.java:66)
	at io.netty.util.concurrent.SingleThreadEventExecutor$5.run(SingleThreadEventExecutor.java:1195)
	at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
	at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
	at java.base/java.lang.Thread.run(Thread.java:1583)
queueAccepted=16 parentSuccess=true childDoneAfterRecovery=false payloadRefCnt=1
childDoneAfterAnotherFlush=false laterWriteDone=false
childDoneOnlyAfterExplicitClose=true childSuccess=false payloadRefCntAfterClose=0

OBSERVED: DeepCloseNotificationProbe FIRST ATTEMPT reproduced a remaining close gap at exact compiled head (14,506ms, probe exits0 by asserting defect). Two real TCP/Noise hosts connect; peer stops; surviving physical closeFuture completes and an event-loop barrier establishes prior close listeners drained. Hold survivor loop, fill2048-slot actual live queue, release into exactly eight ordinary nested DefaultPromise callbacks, final callback invokes public Connection.close. Channel is already physically closed, so NettyChannelClose.kt:47 takes inEventLoop branch; line49 addListener reaches Netty's default depth8 deferral and safeExecute rejection. After all2048 tasks and a fresh barrier: physicalCloseDone=true closeOperationDoneAfterRecovery=false loopShuttingDown=false. The in-loop condition does not mean the caller is inside this close future's active notification or can notify inline. This uses stock hosts/muxer and normal public Netty callbacks; unlike the custom-muxer finding, production reachability does not require a custom future executor. Inherited listener-only behavior remains at the modified branch; broad completed-close/site-sweep claim is incomplete. SHOULD_FIX: preserve actual close-listener reentry ordering while ensuring unrelated in-loop terminal observations cannot rely on rejected notification; add this deterministic depth-limit/full-queue regression.
Full probe trace follows (also R71adv2-deep-close.log):
18:04:05.865 [libp2p-nio-worker-3-1] [[1;31mERROR[m] io.netty.util.concurrent.DefaultPromise.rejectedExecution - Failed to submit a listener notification task. Event loop shut down?
java.util.concurrent.RejectedExecutionException
	at io.netty.util.concurrent.RejectedExecutionHandlers$1.rejected(RejectedExecutionHandlers.java:31)
	at io.netty.util.concurrent.SingleThreadEventExecutor.reject(SingleThreadEventExecutor.java:1134)
	at io.netty.util.concurrent.SingleThreadEventExecutor.addTask(SingleThreadEventExecutor.java:407)
	at io.netty.util.concurrent.SingleThreadEventExecutor.execute(SingleThreadEventExecutor.java:1027)
	at io.netty.util.concurrent.SingleThreadEventExecutor.execute0(SingleThreadEventExecutor.java:993)
	at io.netty.util.concurrent.SingleThreadEventExecutor.execute(SingleThreadEventExecutor.java:983)
	at io.netty.util.concurrent.DefaultPromise.safeExecute(DefaultPromise.java:879)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:514)
	at io.netty.util.concurrent.DefaultPromise.addListener(DefaultPromise.java:199)
	at io.netty.channel.DefaultChannelPromise.addListener(DefaultChannelPromise.java:95)
	at io.netty.channel.DefaultChannelPromise.addListener(DefaultChannelPromise.java:30)
	at io.libp2p.transport.implementation.NettyChannelCloseKt.requestPhysicalClose(NettyChannelClose.kt:49)
	at io.libp2p.transport.implementation.NettyChannelCloseKt.closeOperation(NettyChannelClose.kt:29)
	at io.libp2p.transport.implementation.NettyChannelCloseKt.closeNettyChannelOnce(NettyChannelClose.kt:18)
	at io.libp2p.transport.implementation.ConnectionOverNetty.close(ConnectionOverNetty.kt:34)
	at DeepCloseNotificationProbe.lambda$main$2(DeepCloseNotificationProbe.java:28)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$1(DeepCloseNotificationProbe.java:27)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$1(DeepCloseNotificationProbe.java:27)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$1(DeepCloseNotificationProbe.java:27)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$1(DeepCloseNotificationProbe.java:27)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$1(DeepCloseNotificationProbe.java:27)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$1(DeepCloseNotificationProbe.java:27)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$1(DeepCloseNotificationProbe.java:27)
	at io.netty.util.concurrent.DefaultPromise.notifyListener0(DefaultPromise.java:604)
	at io.netty.util.concurrent.DefaultPromise.notifyListenersNow(DefaultPromise.java:571)
	at io.netty.util.concurrent.DefaultPromise.notifyListeners(DefaultPromise.java:506)
	at io.netty.util.concurrent.DefaultPromise.setValue0(DefaultPromise.java:650)
	at io.netty.util.concurrent.DefaultPromise.setSuccess0(DefaultPromise.java:639)
	at io.netty.util.concurrent.DefaultPromise.setSuccess(DefaultPromise.java:111)
	at DeepCloseNotificationProbe.lambda$main$3(DeepCloseNotificationProbe.java:29)
	at io.netty.util.concurrent.AbstractEventExecutor.runTask(AbstractEventExecutor.java:148)
	at io.netty.util.concurrent.AbstractEventExecutor.safeExecute(AbstractEventExecutor.java:141)
	at io.netty.util.concurrent.SingleThreadEventExecutor.runAllTasks(SingleThreadEventExecutor.java:535)
	at io.netty.channel.SingleThreadIoEventLoop.run(SingleThreadIoEventLoop.java:201)
	at io.netty.util.concurrent.SingleThreadEventExecutor$5.run(SingleThreadEventExecutor.java:1195)
	at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
	at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
	at java.base/java.lang.Thread.run(Thread.java:1583)
physicalCloseDone=true closeOperationDoneAfterRecovery=false loopShuttingDown=false

OBSERVED: No new source changes, socket protocol changes, reflected calls, mocks, raised test timeouts, reduced counts, skipped/deleted tests or committed generated artifacts were found. Close ownership remains setIfAbsent plus CAS on explicit retries, derived caller futures isolate completion/cancellation, and each requestPhysicalClose branch has only one completion source; no new double physical close or double observable Java completion was identified. The outstanding close finding is lost notification, not duplicate completion.
OBSERVED: Required documentation read: repository README.md and CLAUDE.md (AGENTS absent); current DocumentationRepository/PHILOSOPHY.md in full; TESTING.md for public API, isolation, fault injection, comprehensive testing, deterministic timing/concurrency and timeout rules; FL635-final.md/findings.md; F71b2-final.md, F71b-findings.md, F71b2-findings.md, R71adv-final.md and R71tc-final.md; F68c-final.md/findings.md in full. No skill instruction expands authorization to modify handoffs, repositories or PR metadata in this review-only brief.

CLASSIFICATION_AUDIT:
- GossipTwoHostTest.test message larger than mplex frame=unbacked for the specific remaining publish failure: F68c findings lines208-239 develop BindException before body, line18 prior develop receive timeout; neither is the final publish timeout at line34. Formatter profile is expressly prior PR-head evidence, not a develop observation of that publish failure. A failing method is backed; its exact final LOAD_SENSITIVE attribution is not. No evidence of a PR71-caused rate difference; the F68c failure itself predates PR71.
- SubscriptionsLimitTest.existing peer subscribed to many topics=backed: develop native BindException at startHosts:86, retained fixed-port ownership documented; final head repeats same failure at startHosts:88.
- SubscriptionsLimitTest.new peer subscribed to many topics=backed: same develop/final bind failure and shared fixed-port fixture.
- SubscriptionsLimitTest.new peer subscribed to few topics=backed: same develop/final bind failure and shared fixed-port fixture.
- NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes=backed: develop child process exceeds15s and prints p95=1040ms; final head's child rejects p95=995ms; every644 computation task is off-loop on both. This proves inherited failing timing behavior, not the complete reason latency is high. No PR71-induced mechanism demonstrated.
- QuicKuboTestJava.pingKubo=backed: develop and final both refuse connection in getKuboPeerId before libp2p dial; this PR's future conversion cannot produce that earlier HTTP refusal.

OBSERVED: First-attempt lifecycle Gradle result {"class": "io.libp2p.security.noise.NoisePreparationLifecycleTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "name": "io.libp2p.security.noise.NoisePreparationLifecycleTest", "tests": "5", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:09:02.182Z", "hostname": "8bf01af06d2e", "time": "29.606", "xml_ms": 29606, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", ":libp2p:test", "--tests", "io.libp2p.security.noise.NoisePreparationLifecycleTest", "--no-daemon"]}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.security.noise.NoisePreparationConcurrencyTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 134176, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.security.noise.NoisePreparationConcurrencyTest", "--stacktrace"], "name": "io.libp2p.security.noise.NoisePreparationConcurrencyTest", "tests": "1", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:11:54.978Z", "hostname": "8bf01af06d2e", "time": "4.769", "xml_ms": 4769}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.security.noise.NoisePreparationFailureTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 40474, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.security.noise.NoisePreparationFailureTest", "--stacktrace"], "name": "io.libp2p.security.noise.NoisePreparationFailureTest", "tests": "3", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:12:23.890Z", "hostname": "8bf01af06d2e", "time": "22.017", "xml_ms": 22017}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.security.noise.NoisePreparationTrafficTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 26143, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.security.noise.NoisePreparationTrafficTest", "--stacktrace"], "name": "io.libp2p.security.noise.NoisePreparationTrafficTest", "tests": "4", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:13:11.256Z", "hostname": "8bf01af06d2e", "time": "14.152", "xml_ms": 14152}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.security.noise.NoiseBindingReadinessTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 31696, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.security.noise.NoiseBindingReadinessTest", "--stacktrace"], "name": "io.libp2p.security.noise.NoiseBindingReadinessTest", "tests": "3", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:13:56.841Z", "hostname": "8bf01af06d2e", "time": "18.818", "xml_ms": 18818}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.security.noise.NoiseHandshakeTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 12155, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.security.noise.NoiseHandshakeTest", "--stacktrace"], "name": "io.libp2p.security.noise.NoiseHandshakeTest", "tests": "6", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:14:51.258Z", "hostname": "8bf01af06d2e", "time": "2.951", "xml_ms": 2951}
OBSERVED: First six requested Gradle classes are all first-attempt green with no errors/skips. Lifecycle5/5=29606ms; Concurrency1/1=4769ms; Failure3/3=22017ms; Traffic4/4=14152ms; Binding3/3=18818ms; Handshake6/6=2951ms. Each result is from its copied fresh per-class XML and exact source head. NetworkPendingConnectTest is the seventh and currently running. Early single-use Gradle/compile processes have exited; later verification uses an isolated Gradle daemon registry so final --stop will not stop the sibling review's daemon.

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.network.NetworkPendingConnectTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 17591, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.network.NetworkPendingConnectTest", "--stacktrace"], "name": "io.libp2p.network.NetworkPendingConnectTest", "tests": "11", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:15:16.578Z", "hostname": "8bf01af06d2e", "time": "5.165", "xml_ms": 5165}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.transport.CompletedConnectionCloseNotificationTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 27538, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.transport.CompletedConnectionCloseNotificationTest", "--stacktrace"], "name": "io.libp2p.transport.CompletedConnectionCloseNotificationTest", "tests": "1", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:16:23.115Z", "hostname": "8bf01af06d2e", "time": "9.251", "xml_ms": 9251}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.transport.ReentrantConnectionCloseNotificationTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 26062, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.transport.ReentrantConnectionCloseNotificationTest", "--stacktrace"], "name": "io.libp2p.transport.ReentrantConnectionCloseNotificationTest", "tests": "1", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:17:10.527Z", "hostname": "8bf01af06d2e", "time": "7.218", "xml_ms": 7218}

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.transport.CompletedStreamWriteNotificationTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 73464, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.transport.CompletedStreamWriteNotificationTest", "--stacktrace"], "name": "io.libp2p.transport.CompletedStreamWriteNotificationTest", "tests": "6", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:17:38.471Z", "hostname": "8bf01af06d2e", "time": "57.067", "xml_ms": 57067}

OBSERVED / CLASSIFICATION CORRECTION: The earlier shorthand said all six method labels were backed. On the brief's stricter requirement to classify each remaining failure, not merely a method with the same name, Gossip's LOAD_SENSITIVE label is unbacked for its final publish timeout. Develop's bind failure never enters publish; its prior receive timeout also does not show a lost publish result. The head-only formatter profile names a plausible mechanism but does not establish that mechanism on develop. This is a classification-evidence finding, separate from and not proof of a PR71 regression. The other five labels have matching base behavior or the same explicitly measured failed timing contract.

OBSERVED: First-attempt Gradle class result {"class": "io.libp2p.etc.types.CompletedNettyFutureConversionTest", "head": "2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6", "exit": 0, "wall_ms": 14347, "command": ["timeout", "--signal=TERM", "--kill-after=30s", "25m", "./gradlew", "--gradle-user-home", "/tmp/R71adv2-gradle-home", "--offline", "--max-workers=2", ":libp2p:test", "--tests", "io.libp2p.etc.types.CompletedNettyFutureConversionTest", "--stacktrace"], "name": "io.libp2p.etc.types.CompletedNettyFutureConversionTest", "tests": "6", "skipped": "0", "failures": "0", "errors": "0", "timestamp": "2026-10-24T18:19:05.621Z", "hostname": "8bf01af06d2e", "time": "1.268", "xml_ms": 1268}

OBSERVED / FORMAT CHECK CORRECTION: Initial scoped Spotless command exited0 but its output directories are empty: absolute strings were interpreted as globs and matched no files. That command is not formatting evidence. Corrected external init script passes an explicit Gradle FileCollection and records the actual Kotlin task inputs; no source or test result is changed. This justifies one real scoped formatting run after the invalid empty-target command.
OBSERVED: Current Actions permissions are selected (GitHub-owned/verified allowed; empty patterns), rather than the historical local_only setting. Exact head still has a single completed startup_failure run40115549550 at17:18:50Z. No CI command was requested/rerun; local XML remains the verification evidence. The no-executed-tests statement is true for this head; the precise policy wording should be understood against the historical startup failure.

OBSERVED: Real scoped Spotless command failed exit1 on first actual10-file input run (earlier empty-target command is explicitly invalid evidence). Violations: main NettyExt.kt; added CompletedNettyFutureConversionTest.kt, NettyFutureConversionStateTest.kt, CompletedConnectionCloseNotificationTest.kt, CompletedStreamWriteNotificationTest.kt and ReentrantConnectionCloseNotificationTest.kt. Full native Gradle TaskExecutionException/GradleException trace retained in R71adv2-spotless-files.log. Check-only task left all source untouched. Actual target list and external init script copied beside this file for reproduction.
OBSERVED: Final test register:11 selected classes,47 tests passed,0 failed/errors/skips; seven required classes account for33 tests. Every class ran once, one command at a time, at exact head2a3b9e2f. No module-wide result is claimed. All test XML and command records are copied outside Gradle report replacement paths. Two standalone probes affirmatively reproduced the two pending-operation defects; their process exit0 means their defect assertions succeeded, not that the library met the intended contract.

OBSERVED: Final cleanup complete18:25 UTC. Isolated Gradle --stop reported one daemon stopped after the actual formatting check. Earlier single-use daemon2928276, compiler2931682 and isolated daemons2948094/2964059 are gone; pidfd checks bounded their termination. Exact ps -eo pid,args -ww output saved as R71adv2-ps-final.txt; audit reports OWNED_RUNNING_JVMS=[]. Every tracked command is terminal, and no nested agent, timer or condition-poll loop was started. Source checkout is clean at exact requested head. No push, PR comment, approval, merge, enqueue, publication, deployment or handoff mutation was made.

Brief completion audit:
- DONE: Fresh requested clone, exact-head check before review, lifecycle build launched before repository/evidence reading; unchanged develop base7f9f06c2.
- DONE: Complete diff and surrounding changed functions read; host/preparation/inbound/outbound/close/write traces completed; no wire-level change or new preparation/event-loop wait found.
- DONE: Six classifications audited; five backed and one exact publish attribution unbacked, with evidence distinguished from inference.
- DONE: PR why/handoff/review/CI/no-existing-test-edit/footer facts checked; README mechanism and source coordinate checked; current head still OPEN and exact. Current selected Actions policy differs from historical local_only, but the head workflow remains startup_failure and supplies no executed-test gate.
- DONE: Artifacts checked and sizes recorded; actual10-file configured formatting check failed with six changed-file violations, retained as NIT. The first empty-target command is explicitly invalid evidence, not a green check.
- DONE: Seven required classes once each, plus four focused changed-path classes once each; native XML47/47,0 errors/failures/skips. No whole-module run.
- DONE: Both suspected code defects demonstrated with bounded probes; full exact-head source/log/artifact evidence preserved. No production fix was authorized or attempted.
- DONE: Owned process shutdown, exact ps audit, clean checkout and final review report.

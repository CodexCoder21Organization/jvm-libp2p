REVIEW: ADVERSARIAL
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71
HEAD: 8eabea8649ac5bc301615dbea6a5f06ac8c477bc
Started: 2026-10-24 14:19 UTC; minute-55 runtime cutoff 15:14 UTC; hard finish 15:34 UTC.

Plan:
- [in progress] Verify exact head; fresh clone; launch lifecycle test before source reads; read required records and guidance.
- [pending] Read complete diff and changed functions; trace host/transport/Noise readiness and live traffic paths.
- [pending] Audit F68c six-failure classification, PR description, README, conventions and tracked artifacts.
- [pending] Demonstrate suspected defects without changing the PR branch; run seven classes once each sequentially and preserve XML.
- [pending] Finish findings and stop all owned processes, including Gradle daemons, with ps confirmation.

OBSERVED: gh pr view confirms exact requested head and base develop. Fresh clone detached at that commit. First lifecycle test command started before repository/source reading, with output file and 3300-second hard deadline. No rebase because review is restricted to exact head.
OBSERVED: Full FL635-final.md and FL635-findings.md read. PR diff against origin/develop is four files: README, version, NettyExt conversion and CompletedNettyFutureConversionTest. Noise preparation is inherited from develop, not changed by this PR. User's explicitly requested preparation audit will trace it but distinguish inherited behavior from this change.

OBSERVED: Read complete four-file diff, converter/test file and surrounding helpers, README, CLAUDE.md, FORK.md (no AGENTS.md), PHILOSOPHY.md, complete F68c-final.md/F68c-findings.md. Read relevant TESTING.md sections on TDD, public API, real dependencies, resource faults and concurrency before designing a review probe.
OBSERVED: No existing test file is edited in this PR. Its only added test file has six parameterized cases. No source/test timeout or iteration changes. Changed production code adds no thread, blocking operation, wire format or dependency. Full tracked-artifact scan pending; four changed paths are ordinary text source/docs.
OBSERVED: Preparation trace: Builders.kt constructs secure bindings, matches /noise via any(), adds exactly one preparer to ConnectionUpgrader, then NetworkImpl init calls each transport.initialize(). PlainNettyTransport's synchronized lazy launches one short-lived daemon thread per transport. Global preparedNoiseHandshakeAlgorithms uses Kotlin's synchronized lazy, serializing two hosts; successful preparation is process-wide. The temporary HandshakeStates/cipher are destroyed; the preparation thread returns naturally (no persistent executor to shut down). initialize joins only off a Netty executor and unwraps the original CompletionException cause; event-loop construction returns and Host.start/listen/dial compose readiness. close fails readiness outside its monitor; HostImpl checks stopped before/after startup. Readiness observers receive derived futures. No preparation-path syncUninterruptibly/get on network workers found.
OBSERVED: Traffic trace: TCP/WS initialization -> ConnectionBuilder.initChannel -> ConnectionUpgrader secure multistream -> NoiseXXSecureChannel.initChannel -> fresh NoiseIoHandshake/HandshakeState and copied prepared identity -> secure session -> mux negotiation -> activated mux session -> connection handler. Initiator/responder selection follows ch.isInitiator. No changed handshake bytes or key lifecycle in this PR.

Review invariant table:
| Resource | Owner / terminal result | Checks |
| Netty source future | Netty owns; immutable success/failure/cancellation | Conversion must preserve result without mutating source; duplicate transfers are CompletableFuture no-ops. |
| Converted future | Converter creates; caller owns only returned observation | Completion may occur on converting caller for terminal sources; no blocking get; cancellation must not cancel source. |
| Algorithm preparation | Process lazy; one transport readiness per owner | Concurrent hosts serialize, close settles per-host observers while shared work continues. |
| Physical close | ChannelCloseState owns initial/retry operation | Already-closed, busy-executor path must not lose its terminal result. This adjacent inherited path is being probed. |

OBSERVED: F68c audit: GossipTwoHostTest final failure is publish get timeout at line34 (plus host stop timeout); develop control instead failed bind at startHosts:86 before its body. The findings cite a prior-head full-buffer logging profile, but record no develop publish timeout/profile. Thus its specific LOAD_SENSITIVE publish-failure classification is not established by the supplied develop evidence. Three SubscriptionsLimit methods have matching develop BindExceptions and concrete retained-port evidence. Kubo has matching develop Connection refused. OffEventLoop starvation has a different outer timeout on develop, but printed p95=1040ms already exceeds the same MAX_P95_MILLIS threshold that failed with p95=995ms on head, so the behavioral failure has base evidence.
INFER: No evidence so far ties any of those six inherited failures to this converter PR. Gossip's cause remains unproven; absence of matching base evidence is not evidence that this PR caused it.
OBSERVED: PR description opens with the original symptom and closes with the required Claude Code footer. It has no handoff URL or review-round record, and says only local checks ran without explaining the blocked Actions/no CI gate. No existing-test edit list is needed beyond an explicit 'none' because none are changed. FORK.md still claims snapshot-37 is the current source coordinate while this PR selects snapshot-41.
Plan: [done] head/clone/build start/readings; [in progress] static audit and demonstration; [pending] seven first-attempt class results, artifact scan and process cleanup.

OBSERVED: Tracked artifact scan found no added/modified build or evidence artifact in this PR. Inherited support records (identical blobs on origin/develop): .evidence/FURR3/binding-class-hashes-before.txt 411 bytes; .evidence/FURR3/findings.md 10,992 bytes; .evidence/FURR3/stream-descriptors-before.txt 1,328 bytes. These are investigation support artifacts under the guidance, but are not introduced by this PR. gradle/wrapper/gradle-wrapper.jar is the required build wrapper, not a stray output. Changed-path blob sizes: README.md 24,312 bytes, build.gradle.kts 11,790 bytes, NettyExt.kt 2,081 bytes, CompletedNettyFutureConversionTest.kt 6,654 bytes. git diff --check passes; fresh checkout remains clean.
OBSERVED: NettyChannelClose.kt:46-48 deliberately adds a new listener even if closeFuture.isDone, to preserve observer ordering. That path bypasses both corrected converters. INFER pending a public-API probe: a peer-closed connection's first close() may still remain pending if that notification is rejected by a full live queue, despite loop recovery. This is inherited on develop, not a new regression. Probe source is outside the checkout and uses two real TCP/Noise hosts, normal peer close, bounded actual queue and normal Connection.close(). No implementation mutation planned.

OBSERVED: TESTS: NoisePreparationLifecycleTest=5 pass, 0 fail, 0 skipped, 30369 ms first attempt
  stop reentered from readiness prevents empty listener startup(): 4.273s
  direct network listen and connect await preparation without start(): 9.617s
  dial and start after stop fail while shared preparation is held(): 5.585s
  stop fails pending readiness start listen and connect without listen addresses(): 6.168s
  direct network listen and connect preserve pending and completed preparation failure(): 4.706s

OBSERVED: TESTS: NoisePreparationConcurrencyTest=1 pass, 0 fail, 0 skipped, 8706 ms first attempt
  two first builders await one serialized initialization(): 8.706s

OBSERVED: TESTS: NoisePreparationFailureTest=3 pass, 0 fail, 0 skipped, 30440 ms first attempt
  failing cold preparation reaches event loop start caller and leaves worker responsive(): 10.787s
  failing cold preparation reaches external builder caller and leaves worker responsive(): 9.846s
  completed role digest is destroyed before the next role preparation fails(): 9.777s

OBSERVED: Classification audit refinement: followed F68c:17's cited earlier develop control into F68b-findings:85-94/128. It records the same GossipTwoHostTest failing after actual publication, waiting for receipt at test:36, on develop, 19,771ms. That is additional base evidence beyond round4's bind failure. All six remaining failing methods therefore have develop observations. The exact final publish-future timeout at test:34 still is not reproduced on develop in these records, and attributing it specifically to formatting remains INFER; however it would overstate the evidence gap to call the method's inherited/load-sensitive classification wholly unbacked. Prior provisional classification concern is narrowed accordingly, not treated as proof of PR causation.
OBSERVED: OffEventLoop MAX_P95_MILLIS is 500; develop control's printed 1040ms exceeds it, supporting the same behavioral failure even though its parent-process timeout won the race. No failure-rate or performance-worsening claim against this PR is established for any of the six.

OBSERVED: TESTS: NoisePreparationTrafficTest=4 pass, 0 fail, 0 skipped, 27177 ms first attempt
  construction failure preserves its cause and closes live plaintext fixtures(): 8.027s
  close during cold preparation prevents late listener publication(): 7.746s
  cold preparation from another thread leaves plaintext traffic running(): 6.363s
  cold preparation requested on live event loop awaits readiness asynchronously(): 5.013s

OBSERVED: Seven-class verification continues sequentially: Lifecycle5/5, Concurrency1/1, Failure3/3, Traffic4/4 all first-attempt green so far. Each class's native Gradle XML is copied outside checkout before the next task. No retries, widened test budget, reduced iteration, source edits, PR comments or pushes. Binding readiness currently running. Preparation paths checked against lifecycle and concurrency invariants; no new preparation defect found.
OBSERVED: Remote metadata recheck at 14:33 UTC remains OPEN at exact requested head; mergeability field CLEAN is not CI or review evidence. PR body saved locally with line numbers for final metadata findings.
OBSERVED: Converter review covers success including null generic values, original failure and CancellationException, terminal/pending/racing registration, duplicate completion and caller mutation. Terminal Netty state is immutable and transfer uses nonblocking getNow. No new converter code defect demonstrated by static review. The remaining diagnostic targets a separate inherited terminal-close listener bypass.

OBSERVED: TESTS: NoiseBindingReadinessTest=3 pass, 0 fail, 0 skipped, 43826 ms first attempt
  sole cold WebSocket host with delegating Noise binding prepares before first stream(): 20.618s
  sole cold WebSocket host prepares before first stream(): 12.711s
  sole cold host with delegating Noise binding prepares before first stream(): 10.479s

OBSERVED: TESTS: NoiseHandshakeTest=6 pass, 0 fail, 0 skipped, 10793 ms first attempt
  setUpKeys(): 0.56s
  startHandshake(): 0.084s
  completeHandshake(): 0.176s
  create peer identity(): 4.324s
  testAnnounceAndMatch(): 5.497s
  testStaticNoiseKeyPerProcess(): 0.091s

CLASSIFICATION_AUDIT (method-level base evidence; not a claim of full root-cause resolution):
- GossipTwoHostTest.test message larger than mplex frame=backed: round4 develop bind failure plus explicitly cited round3 develop receive timeout. Exact final publish timeout/formatting attribution remains INFER, no measured PR effect.
- SubscriptionsLimitTest.existing peer subscribed to many topics=backed: same BindException on develop, concrete leaked fixed-port control.
- SubscriptionsLimitTest.new peer subscribed to many topics=backed: same BindException on develop, concrete leaked fixed-port control.
- SubscriptionsLimitTest.new peer subscribed to few topics=backed: same BindException on develop, concrete leaked fixed-port control.
- NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes=backed: develop storm p95=1040ms exceeds unchanged500ms, even though outer child timeout reported first; all644 crypto tasks off-loop. No inference that crypto scheduling is correct in all conditions.
- QuicKuboTestJava.pingKubo=backed: same Connection refused at local Kubo identity HTTP request on develop, before libp2p handshake.
INFER: None has an established causal route from the four-file converter/version/doc diff; no comparative failure-rate evidence demonstrates worsening by this PR. Classifying the methods as inherited is supported; explaining every original mechanism would require additional work beyond this review and is not claimed.

OBSERVED: TESTS: NetworkPendingConnectTest=11 pass, 0 fail, 0 skipped, 15929 ms first attempt
  failed dial is removed before a failure callback retries(): 11.974s
  failed shared dial is removed before a later connect(): 0.239s
  cancelling one caller does not cancel subscribers or start a duplicate dial(): 0.391s
  different preHandlers do not share a transport dial(): 0.411s
  connects to different peers start independent transport dials(): 0.472s
  sixteen callers with the same address share one transport dial(): 0.322s
  caller with a live address is not blocked by another caller's dead address(): 0.212s
  concurrent connect and newStream calls share one pending transport dial(): 0.803s
  connect called from close completion dials instead of returning the closed connection(): 0.474s
  one connect still dials all supplied addresses in parallel(): 0.222s
  ipfs and p2p spellings share one pending transport dial(): 0.29s

OBSERVED: TESTS: CompletedNettyFutureConversionTest=6 pass, 0 fail, 0 skipped, 4465 ms first attempt
  [1] false: 3.278s
  [2] true: 0.028s
  [1] false: 0.6s
  [2] true: 0.024s
  [1] false: 0.263s
  [2] true: 0.118s

OBSERVED: All seven requested classes first-attempt green (33 methods total), plus CompletedNettyFutureConversionTest6/6 first-attempt green. No whole module run. Added converter tests independently execute at this exact head.
OBSERVED: First external closed-connection probe failed its setup assertion 'Need a full live loop queue', before invoking Connection.close(); it is not evidence of the suspected library defect. Netty4.2 bytecode names the actual property io.netty.eventexecutor.maxPendingTasks; the probe launch used the older io.netty.eventLoop.maxPendingTasks property, leaving an unbounded queue. Retaining this failed-setup log, then correcting only the property to the library's actual resource configuration. No iteration/time bound/source assertion changed and no selected repository test is rerun.

OBSERVED: Second probe also failed before Connection.close at the same queue setup check. The prior assertion about the property was incomplete: SingleThreadEventLoop does read io.netty.eventLoop.maxPendingTasks; its SingleThreadIoEventLoop overrides task-queue creation to use PlatformDependent.Mpsc.newMpscQueue, which clamps its capacity to at least2048. Bytecode shows max(2048,min(configured,1073741824)). Thus the first launch had a bounded2048 queue, while the second used the wrong parent-class property. Correcting setup from16 queued tasks to exactly2048, derived from library code rather than machine timing, and restoring the correct eventLoop property. All original waits/behavior assertions remain. Both prior setup failures are retained and do not count as a library verdict.

OBSERVED: Public-API closed-connection probe reaches the actual defect after correcting queue setup. Two real TCP/Noise hosts; peer stops normally; client observes physical close; loop is held and its known2048-capacity real queue is filled; first client Connection.close() logs rejected notification at NettyChannelClose.kt:48. Physical close is true while returned Java future is pending. After releasing loop, all2048 tasks drain and a fresh loop barrier completes; close future remains pending and get(1s) throws TimeoutException. Probe exits1 after11,436ms; both hosts stopped in finally. Exact source/log retained outside checkout.

```text
14:40:49.583 [main] [[1;31mERROR[m] io.netty.util.concurrent.DefaultPromise.rejectedExecution - Failed to submit a listener notification task. Event loop shut down?
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
	at io.libp2p.transport.implementation.NettyChannelCloseKt.requestPhysicalClose(NettyChannelClose.kt:48)
	at io.libp2p.transport.implementation.NettyChannelCloseKt.closeOperation(NettyChannelClose.kt:29)
	at io.libp2p.transport.implementation.NettyChannelCloseKt.closeNettyChannelOnce(NettyChannelClose.kt:18)
	at io.libp2p.transport.implementation.ConnectionOverNetty.close(ConnectionOverNetty.kt:34)
	at R71advClosedConnectionProbe.main(R71advClosedConnectionProbe.java:41)
PHYSICAL_CLOSED=true CONVERTED_CLOSE_DONE=false
LOOP_RECOVERED=true CLOSE_DONE_AFTER_RECOVERY=false
Exception in thread "main" java.util.concurrent.TimeoutException
	at java.base/java.util.concurrent.CompletableFuture.timedGet(CompletableFuture.java:1960)
	at java.base/java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2095)
	at R71advClosedConnectionProbe.main(R71advClosedConnectionProbe.java:48)

```

Finding1 SHOULD_FIX: NettyChannelClose.kt:48 retains the same lost-completed-promise notification pattern outside the converter. This file is byte-identical on develop (SHA256706b8547f77361b13cf2280d54aea2923076f3f623321ee6b38c4b34ec1a4d02). It is an inherited omission in end-to-end close handling, not a regression introduced by this PR. Fix the already-closed branch without making completion depend solely on accepted listener dispatch, retain the reentrant observer-ordering contract, and add this real peer-close/full-queue/recovery guard. No source change is made by this review.

Finding2 NIT: FORK.md:41-45 states the current source coordinate is snapshot-37 while build.gradle.kts:87 now assigns snapshot-41. Update the source-coordinate statement alongside the version bump, continuing to distinguish source assignment from publication.

Finding3 NIT: PR description lines1-9 has the correct why-first opening, precise local targeted evidence and footer, but lacks the required handoff URL, applicable review-round record and explicit note that Actions does not execute and therefore provides no CI gate. Add those facts, and state no existing test files were edited. It does not falsely claim a green full suite.

Plan: [done] exact-head/readings/full diff/static traces/classification/artifact scan/seven sequential first-attempt runs and converter run; [done] demonstrate suspected close defect; [in progress] stop owned processes and final audit.

OBSERVED: Cleanup complete: ./gradlew --stop returned0, reported2 daemons stopped; owned Gradle2723111 and KotlinCompileDaemon2724612 have exited. All eight Gradle test commands and all diagnostic/javac/javap processes are terminal; no polling loop, timer, nested agent or background test remains. Exact ps -eo pid,args -ww saved to R71adv-ps-final.txt, followed by /proc Java ownership scan: OWNED_RUNNING_JVMS=[]. No source/branch modification, push, PR comment, merge, enqueue, approval, publication or deployment occurred.

Final brief audit (2026-10-24 14:42 UTC;23 minutes elapsed, within75-minute wall limit):
- DONE: live head check before work and at finish; exact requested commit throughout, base develop; fresh requested checkout; initial bounded Gradle launch before source reads; README/instructions/required records/philosophy read.
- DONE: full four-file diff and surrounding functions; builder -> transport preparation and inbound/outbound connection -> Noise/mux traces; explicit ownership/lifecycle/concurrency checks. Noise preparation is inherited; no new preparation bug found.
- DONE: adversarial converter review; no newly introduced converter bug demonstrated. Related inherited terminal-close defect demonstrated through real public Connection.close with full live queue and recovered loop, complete source/log preserved. Both earlier probe setup failures retained and not misreported as code verdicts.
- DONE: six-failure classification audit. All six methods have develop observations; exact gossip publish-timeout mechanism remains INFER, not asserted proven or PR-caused. No unsupported label finding retained after tracing the earlier develop receipt-timeout reference.
- DONE: PR description/README/FORK/conventions/artifacts audit. Three findings total: inherited close handling SHOULD_FIX; stale source-coordinate NIT; required PR provenance/CI/review metadata NIT. No new committed artifacts; three inherited support records listed with sizes.
- DONE: once each, sequential first attempts: Lifecycle5/5, Concurrency1/1, Failure3/3, Traffic4/4, Binding3/3, Handshake6/6, PendingConnect11/11. Extra changed-behavior class CompletedNettyFutureConversionTest6/6. Zero skips/retries in these39 methods; no whole module.
- DONE: no branch changes/push/comments/merge/queue/approval/deploy/publish; final git status clean. Final remote OPEN/head8eabea8649ac5bc301615dbea6a5f06ac8c477bc. All owned processes stopped, Gradle --stop complete, ps and /proc ownership audit clean.
Plan: [done] all review items and process cleanup. RESULT FINDINGS; FINDINGS_COUNT3.

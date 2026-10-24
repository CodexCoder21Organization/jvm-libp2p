The [direct-RPC and relay-stream handoff](https://www.handoff.wasmserver.com/handoffs/hf-2026-10-19-resolve-the-direct-rpc-timeout-and-complete-relay-stream-validation) leaves first RPC opens between fresh resolvers missing their unchanged 1000 ms budget. The requested investigation uses 16 workers × 16 fresh peer pairs and asks what takes 540–610 ms inside first-wave Noise setup, then removes that setup from live handshakes at its owning layer.

This branch rebases the existing algorithm-preparation implementation and tests from https://github.com/CodexCoder21Organization/jvm-libp2p/pull/57 onto current develop. The original authorship is retained. The new measurements and resolver runs below are the reason for carrying that work forward; this is not a claim of a newly invented implementation.

On the resolver's pinned snapshot-32, `NoiseIoHandshake` constructs `HandshakeState` on a shared network I/O worker. The first four state constructors took 525.8–535.2 ms. The interval includes first-use algorithm verification and serialized native digest initialization, rather than key generation or a blocking random-byte read:

| Measured part | First use | Repetition |
| --- | --- | --- |
| `Noise` class verification | 182.6 ms | Once per JVM |
| Cipher factory call including first-use linkage | 224.5–232.7 ms across the first four workers | Later warm calls about 0.02 ms |
| Native digest provider load | 235.4 ms; first scope monitor hold 256.2 ms | Once per JVM |
| Sibling waits on that digest scope | 254.6, 262.1, 271.1 ms | First-use contention |
| Later `HandshakeState` constructor | About 0.18–0.23 ms | Fresh state per connection |
| I/O-worker `/dev/urandom` reads over the recording | 77 reads, 4.0 ms total, 1.8 ms maximum | Not the constructor delay; no `/dev/random` read was recorded there |

Class-initialization logs and JFR monitor-enter events separate the two waits: siblings first wait at `SymmetricState.java:59`, then on `NativeSha256Scope.newDigest` at line 60. The default `SecureRandom` constructor inside the first `Noise` initializer took 0.189 ms. Key-pair-generator lookup and key generation do not occur in the measured state constructor; per-connection curve derivation and fresh ephemeral-byte generation happen later. These are diagnostic elapsed intervals, not additive CPU figures or qualification rates; transformer costs were measured separately.

A final diagnostic using the prepared artifact measured the first four live `HandshakeState` constructors at **0.24–0.56 ms**, and `initChannel` at **23.0–26.5 ms**. Native provider initialization finished **388.3 ms before the earliest public open**. That diagnostic also completed all 256 pairs, but its count is not added to the uninstrumented qualification below. The one-time work moved before readiness; it was not charged to a larger connection budget.

The host builder registers preparation once when any descriptor announces or matches `/noise`, including delegating bindings. Cold algorithm work runs on short-lived daemon preparation threads, separate from network I/O workers. The existing synchronized lazy still shares successful preparation across the JVM and allows another attempt after a failure. Construction outside a Netty executor waits for the future and reports the original cause. Construction in a Netty callback returns a pending host; `Host.start()`, transport listening and dialing compose readiness before opening sockets. Closing a pending host still prevents a late listener. Each live connection keeps its fresh handshake state, ephemeral key and cipher state.

The retained resolver measurements above and below describe the earlier preparation implementation. Current-head validation is recorded in the review-round section.

Earlier resolver validation used first executions, one build/test command at a time, each with a 2400-second outer command bound. Remote resolver preparation connected to buildtest but produced no runtime result, so consumer runs used `--local`; this Gradle project was tested locally.

| Check | Result |
| --- | --- |
| Public cold-child `NoiseAlgorithmReadinessTest`, before | **FAIL**, 11 post-start algorithm class-load events, after a real first stream and Ping |
| Same test, after | **PASS**, 0 post-start algorithm class-load events |
| Starting-main resolver, pinned snapshot-32 | **82/256 first opens failed**; automatic later attempts also failed and are not substituted |
| Same retained resolver, unprepared current-develop library | **0/256 failed**, maximum 933 ms |
| Same retained resolver, prepared library, two separate native runs | **0/512 failed**, maxima 402 ms and 438 ms, no retries |
| Existing `NoiseHandshakeTest` | **6/6 passed** |
| Existing `NetworkPendingConnectTest` | **11/11 passed** |
| `NoisePreparationReentryTest` | **1/1 passed** |
| Existing `NoiseEphemeralKeyTest` | **1/1 passed** |
| Existing `NoiseObserverCloseTest` | **1/1 passed** |
| Existing `NoiseLoopPendingHandshakeTest` | **1/1 passed** |

The passing unprepared current-develop control matters: the rate change from snapshot-32 cannot be attributed solely to this patch. Existing identity/scheduling changes are also present on develop. The deterministic 11→0 transition proves the remaining readiness gap and its removal; the 0/512 run meets the requested qualification against the starting-main before-rate. Against the earlier lowest 12/256 rate, the probability of 0/512 is about 2.1×10⁻¹¹. This is not a claim of a rate difference against the separately passing current-develop control.

The consumer test body keeps its original 16×16 workload, 1000 ms public-open budget, 180-second test bound, and every assertion. Its original source is checkpointed on https://github.com/CodexCoder21Organization/UrlResolver/tree/fix/first-open-handshake-setup-20261022 . A source-free local test workspace selected retained artifacts because main advanced during this investigation. Both library arms used the identical starting-main resolver JAR (SHA256 `a4ccb5be7c9bae9731fa9f44848f8876d13ce10a96841eaec7eab6f47eee6942`). Unprepared library SHA256: `96b10ed45b063a29e863bda2a8e6f9fec47320e6ee4a84290fc1619c92b3a70f`; prepared library SHA256: `26af403bfbd0b58fcd97257bb8f9ccd602faa8dad7dfdd039503318b580ce901`. Local fixture coordinates are test-only; no artifact was published.

After a release containing this change, UrlResolver's `build.kts` pin should move from `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-32` to that released version. This PR makes no consumer dependency change. Existing connection budgets and iteration counts remain unchanged.

## Review round 2

The fresh-context reviews found live traffic sharing the preparation worker, missed registration behind wrappers, and gaps in cold concurrency, failure, provider and cleanup coverage. This round addresses those findings at the library layer.

| Finding | Commit | Baseline and fixed evidence |
| --- | --- | --- |
| 1. Keep preparation off live I/O workers | [d3317538](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d33175385a227d985116357947241a0d8dafccc7) | Retained traffic test: **2/2 failed before**, **2/2 passed on first fixed run** (15,626 ms). Final coverage also closes a pending host and calls `start()` on the live event loop while real plaintext Pings complete under a held JCA initialization. |
| 2. Register Noise by announced protocol | [a310de98](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/a310de98922bceae0f479c20b182b64469c53ddc) | Wrapped TCP and wrapped WS each failed with first-stream algorithm class loads; direct WS already passed and its missing-registration premise was refuted. **3/3 passed on first fixed run** (53,397 ms). Each cold host uses a peer in another JVM. |
| 3. Concurrent first builders and provider failure | [ad7350bc](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ad7350bcff8bb28f7a18251e04a31eb7cdf8aea0) | Cold baseline concurrency test failed the Netty-executor check; **1/1 passed first fixed run** (9,054 ms): one initialization, two role allocations, max active one, both builders waiting. Both first-use provider-failure variants failed the old-worker check; **2/2 passed first fixed run** (14,736 ms), preserving original cause identity/full message, no ready listener, live Ping progress and recovery. |
| 4. Real provider readiness and cleanup observations | [2e30c168](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/2e30c168e53ddc1172c2a232fe4702467954e129) | Strengthened readiness failed on the original production JAR at its real JCA boundary, independently of class loads. Wrapped bindings failed before provider first use. Final tests retain the JFR zero-load assertion, count first use before host return, and count public `Destroyable` calls for both temporary role digests. A later-role failure observes the earlier role digest already disposed; close during held preparation prevents late publication. |

Baseline runs used the actual rebased pre-round production JAR, from the retained Gradle test scenarios through an external classpath selector. Full failure text and stack traces were read from the native Gradle XML. This round raises no timeout, reduces no iteration count, weakens no assertion, adds no skipped or disabled test, and adds no mock or reflection. Separate test and code review passes checked readiness, failure, closure, registration and temporary ownership.

The real digest boundary observes role-state cleanup on success and before a later role fails. `noise-java` directly constructs its standalone ChaChaPoly cipher and zero-filled temporary key; those objects have no public observation hook. Their existing destroy/clear calls remain intact. No test claims to inspect those bytes or partial constructor state.

### Final first-attempt class runs

Each class below ran once after all changes, in a separate sequential Gradle command. Counts and durations come from the per-test XML.

| Class | Passed / total | Failed | Time (ms) |
| --- | --- | --- | --- |
| `NoiseAlgorithmReadinessTest` | 1/1 | 0 | 25451 |
| `NoisePreparationReentryTest` | 1/1 | 0 | 11126 |
| `NoisePreparationTrafficTest` | 3/3 | 0 | 33372 |
| `NoiseBindingReadinessTest` | 3/3 | 0 | 62064 |
| `NoisePreparationConcurrencyTest` | 1/1 | 0 | 5409 |
| `NoisePreparationFailureTest` | 3/3 | 0 | 30182 |
| `NoiseHandshakeTest` | 6/6 | 0 | 5687 |
| `NetworkPendingConnectTest` | 11/11 | 0 | 11788 |
| `NoiseEphemeralKeyTest` | 1/1 | 0 | 5509 |
| `NoiseObserverCloseTest` | 1/1 | 0 | 8994 |
| `NoiseLoopPendingHandshakeTest` | 1/1 | 0 | 13145 |

Full `:libp2p:test`, first attempt: **1259/1271 passed, 10 failed, 2 skipped**, Gradle exit 1, 1022.344 seconds. The command used a 60-minute outer bound.

Failing full-module classes:

- `io.libp2p.pubsub.GossipApiTest`: 1/2 failed.
- `io.libp2p.pubsub.gossip.GossipTwoHostTest`: 1/1 failed.
- `io.libp2p.transport.tcp.TcpTransportTest`: 2/24 failed.
- `io.libp2p.pubsub.gossip.SubscriptionsLimitTest`: 3/3 failed.
- `io.libp2p.transport.ws.WsTransportTest`: 2/28 failed.
- `io.libp2p.transport.quic.QuicKuboTestJava`: 1/2 failed.

The full-module failures include a refused Kubo API connection before Host construction, gossip/publish waits, fixed-port bind failures, and TCP/WS dial waits. A socket snapshot showed port 40001 still listening in the module test worker after the fixed-port tests; the lifecycle cause and the remaining wait failures are unresolved. All 11 requested classes also passed within this full-module attempt. No full-suite retry was run.

## Review round 3

The owner requested the second-round findings be handled in this order: module-test failures, shutdown during preparation, and the remaining test gaps, followed by a stop. This round keeps the original connection budgets, iteration counts and assertions.

This round is partial: the shutdown and test-ordering changes are committed, but full-module validation remains red. The new direct-network lifecycle scenario also fails at the separate peer-startup endpoint wait in the final module, before its readiness assertions. The remaining mechanisms need reliable reproducers and fixes; no all-green or complete-resolution claim is made.

| Finding | Commit / result | Evidence |
| --- | --- | --- |
| 1. Module failures | **Validation remains unresolved** | All five requested classes passed first attempt individually on `2e30c168`, then all nine disputed methods also passed in that head’s full module. A full develop comparison and a later changed-head full run exposed differing failures; the exact conditions are below. No unexplained failure is labelled unrelated. |
| 2. Shutdown during preparation | [20dc5d13](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/20dc5d13872f9931942c2390ae58f9155980d265) | Five public-API lifecycle scenarios failed on unfixed production, then passed 5/5 on the first fixed execution. Transport-local readiness fails on close without cancelling the shared initialization. Closed checks precede waits; host start rejects stop during its readiness/listener continuations, including an empty listen list and callback re-entry. |
| 3. Test gaps | [86ed5706](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/86ed57065d2ea428f107d6d85e9c156fe78df5df) | Deterministically delaying the second real transport made the old binding-latch release point fail. The corrected test waits for both transport initializations to observe pending readiness, then releases shared preparation. Original allocation, cleanup, overlap, publication and Netty-location assertions remain. Direct network tests also assert zero transport connections while preparation is held. |

The README now states that stop is terminal for readiness, startup, listening and dialing while shared preparation continues for other hosts. There is no production change in the second commit. The existing reentry test has two formatting-only edits required by Spotless.

### Before / after checks

Each lifecycle scenario ran in its own cold child JVM inside the five-test class. The first launch stopped at compilation before executing any test (an unused Unit expression under `-Werror`); after that expression was corrected, the first actual unfixed execution failed 5/5. No timeout or assertion was changed to obtain green.

| Scenario | First unfixed result | First fixed result |
| --- | --- | --- |
| Stop with no configured listen address; ready/start/direct listen/connect pending | `Stopped transport left an operation pending on preparation` | PASS |
| Dial and start after stop while preparation is held; another host still needs preparation | `Stopped transport left an operation pending on preparation` | PASS |
| Stop re-entered from a readiness callback with an empty listener list | `Stopped transport completed an operation successfully` | PASS |
| Direct network listen/connect without start; actual Noise stream and Ping after release; ready after stop | `Stopped transport completed an operation successfully` | PASS |
| Direct calls during pending and already-failed preparation, then after stop | `Wrong closed error: java.security.ProviderException: Noise preparation failure requested by lifecycle test` | PASS |

First fixed lifecycle class: **5/5, 16,411 ms**. After adding the explicit raw-transport connection-count assertion, its first fixed execution passed **5/5, 33,800 ms**. Cancelling one readiness observer does not settle another, original preparation failures retain identity and full message, and a surviving host completes the shared initialization.

Concurrency before: **1/1 failed, 9,215 ms**, `Both builders had not reached transport initialization before preparation release`. After correcting the gate order: **1/1 passed, 5,370 ms**, first execution.

A separate direct-dial guard negative control was inconclusive: its peer startup exceeded the unchanged 10-second endpoint wait before the new assertion was reached. The guard was restored byte-for-byte; no assertion-specific red proof is claimed from that run. The strengthened class then passed first attempt on restored production. No unchanged negative-control retry was used.

### Initial five classes on the reviewed head

One class per sequential Gradle command, first attempt, at `2e30c168`:

| Class | Result |
| --- | --- |
| `GossipApiTest` | 2/2 passed; 0 failed; 0 skipped; 481 ms test time (497891 ms command) |
| `GossipTwoHostTest` | 1/1 passed; 0 failed; 0 skipped; 4127 ms test time (11940 ms command) |
| `SubscriptionsLimitTest` | 3/3 passed; 0 failed; 0 skipped; 4823 ms test time (11764 ms command) |
| `TcpTransportTest` | 24/24 passed; 0 failed; 0 skipped; 19580 ms test time (27312 ms command) |
| `WsTransportTest` | 28/28 passed; 0 failed; 0 skipped; 16474 ms test time (22565 ms command) |

### Whole-module comparisons

| Code / conditions | First-attempt result |
| --- | --- |
| Original reviewed head, after the five separate classes | 1267/1271 passed; 2 failed; 2 skipped; 237127 ms test time (244473 ms command) |
| Detached origin/develop `0c3c86ad`, unchanged tests | 1255/1259 passed; 2 failed; 2 skipped; 203194 ms test time (278272 ms command) |
| Shutdown change and five new lifecycle scenarios | 1267/1276 passed; 7 failed; 2 skipped; 613779 ms test time (644099 ms command) |
| Final committed head `86ed57065d2ea428f107d6d85e9c156fe78df5df` after the requested class sequence | 1260/1276 passed; 14 failed; 2 skipped; 892518 ms test time (931447 ms command) |

All module commands had an outer limit of 60 minutes, further bounded by this task’s wall-clock deadline. The two skips are pre-existing; no test was disabled, skipped or weakened by these changes.

The original-head full run failed Kubo and `YamuxResumeAtFrameBoundCloseTcpTest.callbackCloseWhileDrainingReleasesTheArrivingFrame` (sender write at line 118, `ExecutionException: StacklessClosedChannelException`); all nine disputed methods passed. The plaintext Yamux scenario passed in develop’s full run, so the cause of that head-only occurrence remains unproved.

Develop’s full run failed Kubo and `GossipTwoHostTest` at its receive wait (`Timeout waiting for condition`, test line 36). The later shutdown-change full run failed Kubo, the gossip publish future (test line 34), all three subscription binds, and two `NoiseHandshakeOffEventLoopTest` scenarios (inline child did not finish within 10 seconds; 160-peer storm p95 693 ms with all 644 crypto tasks off the event loop). These are retained failures, not green runs or resolved regressions.

The subscription bind cascade has a concrete immediate mechanism: gossip’s native failure XML has a suppressed timeout at `TwoGossipHostTestBase.stopHosts:94`, the first `host1.stop().get(5 seconds)`. That fixture calls `host2.stop()` only afterward, with no finally, so it skipped the second host’s shutdown. `/proc` socket ownership confirmed port 40001 was still listening in that same Gradle test worker; subsequent subscription binds then failed at `startHosts:88`. This does not explain why the original publish and first shutdown missed their budgets, and no cleanup-only change is presented as that fix.

A late transport-test thread dump showed six of sixteen NIO workers generating RSA primes through `NullConnectionUpgrader.establishSecureChannel:27` during the 50 genuine dials, with no preparation thread present. That fixture registers no Noise preparation. This identifies the work on the path, but does not establish the cause of the timing differences across full runs. No key algorithm, dial count, timeout or fixture assertion was changed.

Kubo’s `pingKubo` failure is a refused local API connection at `getKuboPeerId:65`, before host construction; it also failed on develop. Remaining full-suite timing/closure causes are unresolved. The PR is not represented as module-green.

### Final requested class sequence

Each class below ran once on the final committed head, one sequential Gradle command per class, in the requested order. Durations are from native test XML.

| Class | Result |
| --- | --- |
| `GossipApiTest` | 2/2 passed; 0 failed; 0 skipped; 1438 ms test time (19798 ms command) |
| `GossipTwoHostTest` | 1/1 passed; 0 failed; 0 skipped; 31961 ms test time (61086 ms command) |
| `SubscriptionsLimitTest` | 3/3 passed; 0 failed; 0 skipped; 14130 ms test time (83780 ms command) |
| `TcpTransportTest` | 24/24 passed; 0 failed; 0 skipped; 40638 ms test time (67316 ms command) |
| `WsTransportTest` | 28/28 passed; 0 failed; 0 skipped; 31799 ms test time (55033 ms command) |
| `NoisePreparationTrafficTest` | 3/3 passed; 0 failed; 0 skipped; 26512 ms test time (39687 ms command) |
| `NoisePreparationConcurrencyTest` | 1/1 passed; 0 failed; 0 skipped; 8360 ms test time (29380 ms command) |
| `NoisePreparationFailureTest` | 3/3 passed; 0 failed; 0 skipped; 43335 ms test time (62920 ms command) |
| `NoiseBindingReadinessTest` | 2/3 passed; 1 failed; 0 skipped; 68514 ms test time (91812 ms command) |
| `NoiseAlgorithmReadinessTest` | 1/1 passed; 0 failed; 0 skipped; 22777 ms test time (44031 ms command) |
| `NoiseHandshakeTest` | 6/6 passed; 0 failed; 0 skipped; 6067 ms test time (31321 ms command) |
| `NetworkPendingConnectTest` | 11/11 passed; 0 failed; 0 skipped; 11560 ms test time (29022 ms command) |

### Captured gossip mechanism

The final-run snapshot taken immediately after the gossip failure catches the I/O worker in `Throwable.fillInStackTrace -> ResourceLeakDetector.TraceRecord -> AdvancedLeakAwareByteBuf.getUnsignedByte -> ByteBufUtil.appendPrettyHexDump -> LoggingHandler.formatByteBufHolder -> LoggingHandlerShort.format:32 -> MplexHandler.onChildWrite:58`. No preparation thread exists in that snapshot. The formatter builds the entire hexadecimal dump before shortening it, so accesses for discarded bytes still create leak-tracker stack records.

`PubsubRouterTest` and `SecureChannelTestBase` set the process-wide detector to `PARANOID` in their initializers without restoring it. These test bases and `LoggingHandlerShort` are identical to develop. This gives the full module a different buffer-observation cost than a fresh isolated class, and identifies a concrete existing I/O-worker path behind the gossip delay. The captured worker still owns port 40001, matching the failed first-host cleanup and skipped second-host cleanup described above.

A separate public-API counter probe corroborated the work without timing the machine: a real 65,536-byte heap buffer, holder, EmbeddedChannel and LoggingHandlerShort performed **131,072 unsigned-byte reads** before the output was shortened. Its display-derived budget was 3,072. The diagnostic assertion failed with `Short logging read 131072 bytes for a 65536-byte payload before shortening its output; display-derived budget is 3072`. The counter delegates all actual reads to Netty; it supplies no fake results, uses no reflection and creates no background load. The formatter fix and output-preservation tests are not included in this round, so module validation remains unresolved.

<details><summary>Public byte-read probe used for the diagnosis</summary>

Compile against the current libp2p classes, Kotlin stdlib 1.6.21 and Netty 4.2.10.Final common/buffer/transport/resolver/handler jars, then run LoggingReadProbe. Its first execution exits 1 at the assertion shown above.

The complete `LoggingReadProbe` source remains in the archived F68c/F68d runtime records noted below. Its first public-API byte-read execution exits 1 at the assertion above.

</details>


### Failures from final selected-class runs

Every failed first attempt is retained below, even if the same class later passes in the full module. The binding-readiness endpoint timeout is at the separate peer startup boundary before preparation assertions; its cause remains unresolved and no timeout change was made.

<details><summary>io.libp2p.security.noise.NoiseBindingReadinessTest.sole cold host with delegating Noise binding prepares before first stream()</summary>

```text
org.opentest4j.AssertionFailedError: Exception in thread "main" java.util.concurrent.TimeoutException
	at java.base/java.util.concurrent.CompletableFuture.timedGet(CompletableFuture.java:1960)
	at java.base/java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2095)
	at io.libp2p.security.noise.NoiseAlgorithmReadinessProbe.main(NoiseAlgorithmReadinessTest.kt:68)
 ==> expected: <0> but was: <1>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at app//org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at app//io.libp2p.security.noise.NoiseBindingReadinessTestKt.bindingProbe(NoiseBindingReadinessTest.kt:36)
	at app//io.libp2p.security.noise.NoiseBindingReadinessTestKt.access$bindingProbe(NoiseBindingReadinessTest.kt:1)
	at app//io.libp2p.security.noise.NoiseBindingReadinessTest.sole cold host with delegating Noise binding prepares before first stream(NoiseBindingReadinessTest.kt:12)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)

```

</details>


### Prior final-module failure messages

Repeated historical stack frames are condensed here to leave room for the current review round within GitHub's description limit. Failure names/messages, cause messages, classifications and round tables are retained. The full prior description and native records are archived in the F68d runtime record, alongside the earlier F68b/F68c records already cited below.

The final module retains every failure below: plaintext Yamux callback close and pre-resume delivery; gossip publish and its failed cleanup; three resulting subscription bind errors; Noise identity-count, inline-child and starvation-child checks; the new lifecycle peer endpoint wait; queued-ready child termination; Kubo connection refusal; and TCP/WS dial waits. The captured formatter/leak-tracker work and skipped second-host close explain concrete parts of the gossip/bind chain. Remaining peer, mux and harness mechanisms are unresolved within the task wall-clock limit. Nothing is waived or labelled unrelated without evidence.

<details><summary>io.libp2p.mux.yamux.YamuxResumeAtFrameBoundCloseTcpTest.callbackCloseWhileDrainingReleasesTheArrivingFrame()</summary>

```text
java.util.concurrent.ExecutionException: io.netty.channel.StacklessClosedChannelException
Caused by: io.netty.channel.StacklessClosedChannelException
```

</details>

<details><summary>io.libp2p.mux.yamux.YamuxResumeAtFrameBoundTcpTest.resumedChildDrainsItsFullQueueBeforeAdmittingNextFrame()</summary>

```text
org.opentest4j.AssertionFailedError: All preceding frames must still be queued ==> expected: <0> but was: <1>
```

</details>

<details><summary>io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame()</summary>

```text
java.util.concurrent.TimeoutException
	Suppressed: java.util.concurrent.TimeoutException
```

</details>

<details><summary>io.libp2p.pubsub.gossip.SubscriptionsLimitTest.existing peer subscribed to many topics()</summary>

```text
java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
Caused by: java.net.BindException: Address already in use
```

</details>

<details><summary>io.libp2p.pubsub.gossip.SubscriptionsLimitTest.new peer subscribed to many topics()</summary>

```text
java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
Caused by: java.net.BindException: Address already in use
```

</details>

<details><summary>io.libp2p.pubsub.gossip.SubscriptionsLimitTest.new peer subscribed to few topics()</summary>

```text
java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
Caused by: java.net.BindException: Address already in use
```

</details>

<details><summary>io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.concurrent handshakes preserve both peer identities and immediate mux negotiation()</summary>

```text
java.lang.AssertionError: 
```

</details>

<details><summary>io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.single Noise handshake runs crypto inline on event loops()</summary>

```text
org.opentest4j.AssertionFailedError: Noise handshake inline child JVM did not finish within 10 seconds:
```

</details>

<details><summary>io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes()</summary>

```text
org.opentest4j.AssertionFailedError: Noise handshake starvation child JVM did not finish within 15 seconds:
```

</details>

<details><summary>io.libp2p.security.noise.NoisePreparationLifecycleTest.direct network listen and connect await preparation without start()</summary>

```text
org.opentest4j.AssertionFailedError: Exception in thread "main" java.util.concurrent.TimeoutException
```

</details>

<details><summary>io.libp2p.security.noise.NoiseQueuedReadyCallbackTest.queued ready callback runs before another connection computation finishes()</summary>

```text
org.opentest4j.AssertionFailedError: The queued-ready child must terminate ==> expected: <true> but was: <false>
```

</details>

<details><summary>io.libp2p.transport.quic.QuicKuboTestJava.pingKubo()</summary>

```text
java.net.ConnectException: Connection refused
```

</details>

<details><summary>io.libp2p.transport.tcp.TcpTransportTest.dial then close client transport()</summary>

```text
java.util.concurrent.TimeoutException
```

</details>

<details><summary>io.libp2p.transport.ws.WsTransportTest.dial then close server transport()</summary>

```text
java.util.concurrent.TimeoutException
```

</details>

No merge, queue entry, build-watchman run, deployment, publication, other PR or handoff change was made.

### Round 4: full-module control and fixture ownership

The owner requested a matching whole-module develop control, a cause-based review of all fourteen prior head failures, fixes for tests added or changed by this PR, and an exact account of what CI runs. This round does not merge, enqueue, deploy, publish, change workflows/settings, or modify the handoff.

CI does **not** execute the Gradle workflow for this PR. [Run 40034838300](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40034838300) concluded `startup_failure` with zero jobs and zero check-runs. Every listed push/PR run for the branch has the same conclusion. The repository Actions permissions endpoint returns `enabled=true, allowed_actions=local_only`; the selected-actions endpoint returns HTTP 409, “Only local actions and workflows are allowed on this repository.” `build.yml` references `actions/checkout@v3`, `actions/setup-java@v3`, `gradle/gradle-build-action@v2`, and `android-actions/setup-android@v3`, so repository policy rejects it before a job exists. The smallest correction is to permit those four action repositories under the repository/org Actions allow-list. There is no workflow-only correction while the repository permits only local actions; no setting or workflow was changed here. The org currently allows GitHub-owned/verified actions with no extra patterns.

Both `kotlin.build` successes explicitly report “no build.kts found in repository”; they ran no build or tests. The `develop` branch protection endpoint returns HTTP 404 “Branch not protected.” These green informational checks cannot substantiate a merge decision.

The brief calls the OffEventLoop and QueuedReady tests PR-added, but both files are byte-for-byte identical to develop and absent from this PR's 17-file diff. Their failures still need explaining; their provenance is recorded correctly below.

The same failure persists after this round's push: [run 40041280804](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40041280804) and [run 40041271437](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40041271437) both ended `startup_failure` on `1a27fda8`; the former has zero jobs. The visible new-head `kotlin.build (remote)` success again explicitly skipped because there is no `build.kts`.

**Develop control, first attempt.** Fresh detached worktree at `0c3c86ad9ceb1c9d3834da68f239108e14b7f780`, unchanged `./gradlew :libp2p:test`, one heavy run at a time, 50-minute outer bound: **1244/1259 passed, 13 failed, 2 existing skips**, Gradle elapsed 25m20s including cold compilation. Failing methods:

- `core.HostTestJava.largePing()`: java.util.concurrent.CompletionException: io.libp2p.core.ConnectionClosedException: Connection is closed
- `core.RpcHandlerTest.test1()`: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
- `pubsub.gossip.GossipBackwardCompatibilityTest.testConnect_1_2_to_1_1()`: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
- `pubsub.gossip.GossipBackwardCompatibilityTest.testConnect_1_1_to_1_2()`: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
- `pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame()`: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
- `pubsub.gossip.SubscriptionsLimitTest.existing peer subscribed to many topics()`: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
- `pubsub.gossip.SubscriptionsLimitTest.new peer subscribed to many topics()`: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
- `pubsub.gossip.SubscriptionsLimitTest.new peer subscribed to few topics()`: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
- `security.noise.NoiseHandshakeOffEventLoopTest.single Noise handshake runs crypto inline on event loops()`: org.opentest4j.AssertionFailedError: Noise handshake inline child JVM did not finish within 10 seconds:
- `security.noise.NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes()`: org.opentest4j.AssertionFailedError: Noise handshake starvation child JVM did not finish within 15 seconds:
- `transport.quic.QuicKuboTestJava.pingKubo()`: java.net.ConnectException: Connection refused
- `transport.ws.WsTransportTest.dial then close server transport()`: java.util.concurrent.TimeoutException
- `transport.ws.WsTransportTest.dial then close client transport()`: java.util.concurrent.TimeoutException

This base run also exposed a separate cleanup cascade: `HostTestJava.largePing` failed during TLS/Yamux delivery and skipped its later `stop()` calls, which are outside a `finally`. `/proc` confirmed the same worker retained listener port 40002. Later RPC/gossip/subscription tests reuse that fixed port and failed binding. The primary TLS ping closure is not diagnosed by this round and is not called fixed.

**Classification of all fourteen prior head failures.** A passing method in the single base module is not treated as proof of a regression. Supplemental base controls below force the relevant condition; those are explicitly distinguished from module outcomes.

| Prior failed method | Classification | Evidence and mechanism |
| --- | --- | --- |
| `YamuxResumeAtFrameBoundCloseTcpTest.callbackCloseWhileDrainingReleasesTheArrivingFrame` | `PRE_EXISTING` | Base forced automatic-read ordering; exact closed-channel sender error. |
| `YamuxResumeAtFrameBoundTcpTest.resumedChildDrainsItsFullQueueBeforeAdmittingNextFrame` | `PRE_EXISTING` | Base forced automatic-read ordering; exact one-byte delivery before resume. |
| `GossipTwoHostTest.test message larger than mplex frame` | `LOAD_SENSITIVE` | Base module bind failure; fixed ports/cleanup, plus prior head full-buffer formatting profile. |
| `SubscriptionsLimitTest.existing peer subscribed to many topics` | `LOAD_SENSITIVE` | Base module bind failure; fixed ports and skipped cleanup; isolated head passes. |
| `SubscriptionsLimitTest.new peer subscribed to many topics` | `LOAD_SENSITIVE` | Base module bind failure; fixed ports and skipped cleanup; isolated head passes. |
| `SubscriptionsLimitTest.new peer subscribed to few topics` | `LOAD_SENSITIVE` | Base module bind failure; fixed ports and skipped cleanup; isolated head passes. |
| `NoiseHandshakeOffEventLoopTest.concurrent handshakes preserve both peer identities and immediate mux negotiation` | `PRE_EXISTING` | Base public mux gate: local connect completion precedes remote table publication. |
| `NoiseHandshakeOffEventLoopTest.single Noise handshake runs crypto inline on event loops` | `PRE_EXISTING` | Base module same 10-second whole-child failure. |
| `NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes` | `PRE_EXISTING` | Base module same 15-second whole-child failure; all crypto off-loop. |
| `NoisePreparationLifecycleTest.direct network listen and connect await preparation without start` | `PR_CAUSED` | PR-owned nested peer startup inside 10-second endpoint wait; fixed. |
| `NoiseQueuedReadyCallbackTest.queued ready callback runs before another connection computation finishes` | `PRE_EXISTING` | Supplemental base children 2/4 exceed 20 seconds after correct ordering; single base module passes. |
| `QuicKuboTestJava.pingKubo` | `PRE_EXISTING` | Base module same absent local Kubo connection refusal. |
| `TcpTransportTest.dial then close client transport` | `PRE_EXISTING` | Supplemental base 200 real TCP dials exceed 20 seconds; single base module TCP 24/24 pass. |
| `WsTransportTest.dial then close server transport` | `LOAD_SENSITIVE` | Base module same 20-second dial bound; real RSA fixture on I/O workers. |

The pre-existing cases remain unchanged, as requested. No preparation/handshake production race was demonstrated by these controls:

- **Remote table publication:** 8 natural batches of 256 concurrent real TCP/Noise/Mplex handshakes passed (2048 total, 51411ms). Holding the real responder mux-completion future through its public binding interface gives 256 completed outbound connects and zero remotely published connections; the old immediate-table assertion fails exactly `Expected size: 256 but was: 0` (20862ms). Releasing that future and awaiting the real inbound connection callbacks produces all 256 exact local/remote identities (21106ms). The local connect future does not synchronize the remote table; this is evidence for a missing inbound-publication barrier in the unchanged test, not lost registrations.
- **Yamux read ordering:** 32 natural concurrent scenario instances passed on base (18115ms), so those passes alone were not accepted as a diagnosis. A public outbound-read gate then held a genuine automatic read after Netty selected it with autoRead=true. Pausing off-loop before forwarding it makes `MuxChannel.doBeginRead -> childReadRequested` grant one manual allowance. Base then reports `AUTO_READ=false DELIVERED_BEFORE_RESUME=1` and fails the original zero-delivery contract (12488ms). Closing from that first delivery reproduces the exact sender `StacklessClosedChannelException` while attempting all 65 real writes (9973ms). The captured automatic-read stack goes through Netty `HeadContext.readIfIsAutoRead`/`channelReadComplete`; these mux files are unchanged by this PR.
- **Whole-child timing:** four genuine concurrent base queued-ready children all reported `pending=1, onLoop=false, readyBeforeComputationFinished=true`; two exceeded their unchanged 20-second process bounds at 20128/20192ms. One of those exited zero while timeout cleanup raced; it remains a failed deadline, not a pass. The other two completed in 19816/19482ms. This demonstrates that startup/fixture/shutdown work can exhaust the process-wide bound after the ordering result succeeded.
- **Transport fixture work:** four real pairs using OS-assigned ports and the existing `NullConnectionUpgrader` started four groups of 50 dials on develop. All 200 were pending at the same 20-second completion bound; cleanup also exceeded its five-second bound (33258ms overall). Prior full-worker stacks show real RSA generation in this fixture on shared I/O workers. This supplements the actual base WS module failures; the base TCP module's 24/24 pass is retained as such.

**Fixture fix and red-before / first-green evidence.** [Commit 1a27fda8](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/1a27fda81589ba69b3f7f2e03928c0f4290644ce) changes only three PR-added test files. The parent starts and owns the real peer before it launches the still-cold subject JVM, passing its already-bound ephemeral address explicitly. This removes the second cold JVM's startup and independent lifetime from the subject's readiness scenario. It does not warm the subject's Noise state. Peer shutdown, complete process output/exit checks, provider counts/destruction/thread checks, JFR zero algorithm-load checks, real Ping/stream closure, and all lifecycle/re-entry/cancellation/failure assertions remain.

| Changed test file | Red before this change | First green after change |
| --- | --- | --- |
| `NoisePreparationLifecycleTest.kt` | Prior final module: `TimeoutException` at nested peer endpoint wait, probe line99, before target assertions (19815ms method) | 5/5, 36934ms class |
| `NoiseBindingReadinessTest.kt` | Prior first wrapped-TCP attempt: `TimeoutException` at shared AlgorithmReadinessProbe endpoint wait, line68 (17196ms method) | 3/3, 35471ms class |
| `NoiseAlgorithmReadinessTest.kt` | Shared nested-peer launcher is the one exercised by that Binding red; no independent Algorithm-test red is claimed | 1/1, 18738ms class |

The three classes passed **9/9, zero skips, first attempt** on the corrected source; full Spotless check passed. Dedicated test-comprehensiveness and implementation review passes checked every existing assertion/scenario and each process/peer cleanup path. No timeout or heap limit was raised, no iteration count was reduced, and no test or behavioral assertion was weakened, skipped, disabled or deleted. Production code and README contracts are unchanged in round 4.

**Final pushed-head module:** **1268/1276 passed, 6 failed, 2 existing skips, first attempt**, exact pushed `1a27fda81589ba69b3f7f2e03928c0f4290644ce`, unchanged `./gradlew :libp2p:test`, Gradle elapsed 14m33s (875669ms command wall). **All 17 PR-added tests passed**, including all nine in the three corrected files. Remaining failures:

- `GossipTwoHostTest.test message larger than mplex frame` = `LOAD_SENSITIVE`.
- `SubscriptionsLimitTest.existing peer subscribed to many topics` = `LOAD_SENSITIVE`.
- `SubscriptionsLimitTest.new peer subscribed to many topics` = `LOAD_SENSITIVE`.
- `SubscriptionsLimitTest.new peer subscribed to few topics` = `LOAD_SENSITIVE`.
- `NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes` = `PRE_EXISTING`.
- `QuicKuboTestJava.pingKubo` = `PRE_EXISTING`.

The gossip failure again includes suppressed first-host cleanup timeout at `stopHosts:94`, and the same worker retained port40001; this explains the three bind failures. The inherited storm probe reported p95=995ms with all 644 crypto tasks off-loop. Kubo is absent locally. These six cases remain unfixed under the instruction to leave pre-existing tests unchanged; the module remains failing. No PR-added failure remains.


All first-attempt native failure stacks, exact XML, console logs, diagnostic sources and classpaths are preserved in this lane's requested `F68c-findings.md` and `F68c-runtime/` output records. No other PR, workflow/settings mutation, handoff edit, merge, enqueue, build-watchman run, deployment or publication occurred.

## Review round 5

The owner requested the fourth-round findings be fixed red-first, with a separate pushed commit for each finding, followed by a stop. This round covers matcher-only registration, two real waiting callers, post-completion stop checks, construction-failure cleanup, phased process observations, and the callback wait. No merge, queue entry, build-watchman run, whole-module run, workflow/settings change, handoff edit, deployment or publication was performed.

| Finding | Commit | Red evidence / first fixed green |
| --- | --- | --- |
| 1 | [f65ed6ca](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/f65ed6cae31d0b6dcf86c1e15276a310d8a29336) | Matcher-only descriptor: `Matcher-only Noise host returned before preparing both roles: allocations=0` (14,181 ms). Register once when any descriptor announces **or matches** `/noise`. Matcher **1/1, 22,137 ms**; Traffic **3/3, 48,448 ms**; Binding **3/3, 54,690 ms**, first fixed command. The cold receiver then accepts a real inbound Noise/Ping stream from its parent-owned peer. |
| 2 | [a1457a34](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/a1457a340fa0c159448d467ad911d9cea1df46c0) | A permanent real TCP scenario holds public transport readiness until a marker queued on the callback event loop runs. The blocking callback fails with `TimeoutException` / `Noise probe exited before BODY_COMPLETE` (**15,412 ms**); composing `start().whenComplete` passes **1/1, 6,722 ms**, first fixed attempt. The review observed no natural stall; this is a deterministic pending-readiness control. Existing connection/callback waits and assertions remain, with an additional queued-release check. |
| 3 | [a0313914](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/a03139145cf37cc8e80fb9f2ea5c540de95496bc) | The reviewer's exact mutation (only initializer one waits) now fails `An initializer exited before both callers awaited preparation` (**11,214 ms**). The two-caller proof observes fixture-owned waiting threads and rejects any early initialization exit. An unexpected first restored run exposed a separate fixture-startup deadline error, retained in full below; its actual correction passes **1/1, 12,073 ms**, first attempt on corrected fixture source. No unchanged failed test was rerun into green. |
| 4 | [af233ca5](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/af233ca5f7d8393ffc3d8d4617a1f33aeca0e910) | Phased launchers reject an otherwise successful child without phase reports: `Noise probe exited before READY` (**7,135 ms**). Instrumented probes pass **19/19**, eight classes, first command (**248,466 ms wall**). Startup/body/cleanup maxima were **11,121 / 7,874 / 344 ms**. A subsequent matcher observer-ownership correction passes **1/1, 7,821 ms**, first attempt. Each phase and process exit is observed explicitly; original behavior and exit-code assertions remain. |
| 5 | [e448a94f](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/e448a94f7cc82a67b2d03a54f8877995dfb58742) | A control that incorrectly makes stopped readiness succeed only after shared preparation completes fails the NEW post-completion assertion: `Stopped transport completed an operation successfully` (**7,660 ms**). Restored production passes **5/5, 47,781 ms**, first attempt. Closed start/ready/network listen/connect/raw `Transport.listen`/`dial` are checked before release and after survivor readiness confirms completion; late socket/publication counters stay zero. |
| 6 | [8710b4f8](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/8710b4f8fe2f56498c3363f41f50faaecbbbbee0) | With two real connected plaintext hosts, the construction-failure case fails `Provider remained registered after construction failure` (**16,775 ms**), retaining the exact original provider cause. Cleanup now attempts provider removal, executor termination and every host close even when reading the construction future fails; the original failure remains primary and unrelated secondary failures are rejected. Traffic passes **4/4, 32,236 ms**, first fixed attempt. Driver rescue runs only after cleanup observations so the negative control terminates instead of becoming a process timeout. |

### Unexpected first-attempt failure and fixture correction

The first restored concurrency execution failed **1/1, 16,048 ms** at `Cold builders did not initialize the SHA-256 provider`, before the new waiting-caller assertions. A separate instrumented diagnostic also failed **1/1, 15,148 ms**: the controller began its 10-second provider wait at **753 ms**, both identity/binding factories finished at **11,039/11,045 ms**, the first transport requested readiness at **11,143 ms**, and the provider entered at **11,289 ms**, after the **10,753 ms** deadline. The diagnostic's interruption was cleanup interrupting the second builder after that failure, not its cause.

The fixture now prepares identity signing/linkage through a standalone real Noise binding before that timed wait. It asserts **zero role-digest allocations and zero initializations** at that boundary. Both actual concurrent host builders still create independent fresh Noise bindings in their original factories and compete for the first real role preparation. In the first corrected run, fixture preparation finished at 7,647 ms; the controller began waiting at 7,835 ms and the provider entered at 8,975 ms. No timeout, workload count or assertion was relaxed. Full builder-future errors are now recorded.

<details><summary>Full unexpected first restored concurrency failure</summary>

```text
org.opentest4j.AssertionFailedError: Exception in thread "main" java.lang.IllegalStateException: Cold builders did not initialize the SHA-256 provider
	at io.libp2p.security.noise.NoisePreparationConcurrencyProbe.main(NoisePreparationConcurrencyTest.kt:107)
 ==> expected: <0> but was: <1>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at app//org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at app//io.libp2p.security.noise.NoisePreparationConcurrencyTest.two first builders await one serialized initialization(NoisePreparationConcurrencyTest.kt:38)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)

```

</details>

### Bound scope and changes to existing tests

Round 5 replaced the former **30-second aggregate exit guard** with separate **30/30/10/5-second startup, behavior, cleanup and exit guards**. That widened acceptance to as much as 75 seconds, contrary to the owner's rule. The earlier description's no-bound-increase claim was incorrect. Both fifth-round reviews demonstrated the difference with controlled real children: 31,540 ms and 31,858 ms totals passed the phased helper while the former aggregate guard rejected them.

**Round 6 restores the original aggregate bound.** The helper creates one deadline at entry. Every phase and the final exit wait is limited by its remaining time; completed phase durations and the currently pending interval remain available in timeout diagnostics. The separate phase caps remain upper bounds inside the same 30 seconds. No phase or exit wait grants additional aggregate time, and individual operation bounds and iteration counts remain unchanged.

Every edit to a test that already existed at the start of this round:

| File | Before → after | Reason / preserved checks |
| --- | --- | --- |
| `NoisePreparationConcurrencyTest.kt` | Entry latch and one publication snapshot → actual two-caller blocking/exit proof; cold identity linkage inside provider wait → separate identity fixture with zero-role-allocation assertion; silent failed futures → complete stage/error records; aggregate launcher → phased launcher. | Detect the exact one-waiter mutation and remove the demonstrated fixture-startup charge. Both real builders/factories and every allocation, destruction, overlap, publication and thread-location assertion remain. |
| `NoisePreparationLifecycleTest.kt` | Stop checks only while held → also retry every stopped public entry after survivor readiness; add raw listen/dial checks and socket counts; aggregate → phased launcher/probe reports. | Prove stop remains terminal after shared completion. Existing five scenarios, observer cancellation, error identity/full text, traffic, reentry and no-publication checks remain. |
| `NoisePreparationTrafficTest.kt` | Three modes → retain all three plus construction-failure cleanup; aggregate → phased launcher. | Add the live-resource failure case without changing any original behavior assertion. |
| `NoisePreparationTrafficProbe.java` | Unprotected construction-future read at start of finally → independent cleanup attempts with original/secondary failure preservation; add fault-driver observations/rescue; add phase reports. | A failed future must not skip provider/executor/host cleanup. Existing marker/Ping counts, loop checks, startup/close/publication checks and all operation bounds remain. |
| `NoisePreparationFailureTest.kt` | Aggregate launcher → phased launcher. | Separate startup/body/cleanup without changing the three failure scenarios or exit-code checks. |
| `NoisePreparationFailureProbe.java` | Implicit lifetime → reports after plaintext fixture startup, all behavior checks, and host cleanup. | Original cause/full message, no-ready-host, temporary destruction, responsiveness and recovery checks remain. |
| `NoiseAlgorithmReadinessTest.kt` | Aggregate launcher → phased launcher; JFR construction inside the measured work → startup before READY, owned inside the existing cleanup scope. | First-stream recording boundaries, zero algorithm loads, provider counts/destruction/location, real Ping and stream/host cleanup remain. A constructor failure still deletes its temporary file. |
| `NoiseBindingReadinessTest.kt` | Aggregate launcher → phased launcher. | Same three modes, parent peer ownership, real streams, shared algorithm/provider/JFR assertions and exit-code checks. |
| `NoisePreparationReentryTest.kt` | Callback waits with `start().get` → composes startup into its callback future; add public ready gate released by a queued same-loop marker; aggregate → phased launcher. | Deterministically expose the blocking wait while retaining real connection/callback observations, no preparation connection assertion and host cleanup. The test releases the gate before stopping hosts on every path. |

`NoiseMatcherReadinessTest.kt` is new in this round. Its later phased-launcher edit retains all receiver/provider/inbound-Ping assertions, observes startup/body/cleanup, and awaits its observer before deleting the output it reads; secondary observer errors are retained on the original failure. `NoiseProbePhases.kt` is new test support only. The README now describes announce-or-match registration. No test was skipped or disabled and no iteration count was reduced. Round 5 widened the aggregate process guard despite retaining the individual wait values; round 6 corrects that change as described above.

### Final pushed-head validation

One final Gradle command ran Spotless check and **every PR-added test class once** on the pushed head. Counts/times below come from its native per-test XML, with **19/19 passed, zero failures/errors/skips**. Separate test-comprehensiveness and implementation review passes were completed; the observer ownership issue identified during review was fixed before this final command.

| Class | Passed / total | Failed / skipped | Time (ms), first attempt |
| --- | --- | --- | --- |
| `NoiseAlgorithmReadinessTest` | 1/1 | 0 / 0 | 14827 |
| `NoiseBindingReadinessTest` | 3/3 | 0 / 0 | 28186 |
| `NoiseMatcherReadinessTest` | 1/1 | 0 / 0 | 5726 |
| `NoisePreparationConcurrencyTest` | 1/1 | 0 / 0 | 4289 |
| `NoisePreparationFailureTest` | 3/3 | 0 / 0 | 24601 |
| `NoisePreparationLifecycleTest` | 5/5 | 0 / 0 | 32024 |
| `NoisePreparationReentryTest` | 1/1 | 0 / 0 | 5829 |
| `NoisePreparationTrafficTest` | 4/4 | 0 / 0 | 30739 |

The previous whole-module classification stands: **1268/1276 passed, six failures and two existing skips** at the round-4 head. No whole-module rerun was performed. The retained CI statement is unchanged: GitHub's Gradle workflow is rejected before jobs start by the local-actions-only policy, and the informational kotlin.build success skips because this Gradle repository has no build.kts. These local checks are not a claim of green effective CI. Round 5 increased the effective aggregate process bound without approval. Round 6 restores the former 30-second aggregate deadline; it requests no increase.

All six findings have separate pushed commits. No unproven checkout work remains, and no other PR was opened.

## Review round 6

The owner requested the remaining aggregate-bound finding be fixed test-first, both reviews addressed, and this description corrected, then a stop. Both reviews reported this same finding, with no other issue.

- Initial regression [65b81389](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/65b813895f72e0f8bca2884bb8dfc72480eafc15): unfixed **4/7 passed, three intended failures, 102,251 ms**. Each real child took at least 31,540 ms while meeting every phase cap. Red: `Noise probe accepted a child taking at least 31540ms within its individual phase bounds ==> Expected java.lang.IllegalStateException to be thrown, but nothing was thrown.`
- Corrected fixture [fc486eef](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/fc486eefbac3a0ec9012777f37a7c245567e9120): unfixed **4/7 passed, three intended failures, 108,338 ms**, first attempt. This test checkpoint was pushed before the helper fix. All assertions, delays and case counts remain unchanged.
- Fix [3a83f880](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/3a83f88048d3e4f4c71af7e993501083ead6e80e): one deadline from helper entry caps every phase and the final exit wait, with checks before marker acceptance and success. Errors retain completed/pending durations and child output. Phase/reap caps remain unchanged. The controlled 31,540 ms totals are now rejected during body, cleanup and exit.

### First fixed attempt and fixture correction

The original fixture's first fixed attempt was **6/7 passed, one failure, 95,666 ms**, retained as failed. JVM startup added 1,459 ms to the EXIT case's 16+12+1-second pre-exit schedule, so the helper correctly expired during cleanup. The EXIT-specific diagnostic assertion failed; the message is below.

An explicit child-initialization barrier now precedes the unchanged schedule. Real probe startup still counts toward its guard. Assertions, delays, counts and bounds are unchanged. The corrected fixture was red before fixing the helper. No unchanged retry. Full stacks: `F68e-findings.md` and `F68e-runtime/` native XML.

<details><summary>First fixed-attempt failure message</summary>

```text
org.opentest4j.AssertionFailedError: Noise probe exceeded the 30s aggregate deadline while awaiting CLEANUP_COMPLETE; phase durations: READY=17459ms, BODY_COMPLETE=12000ms, CLEANUP_COMPLETE pending=539ms:
NOISE_PROBE_PHASE=READY
NOISE_PROBE_PHASE=BODY_COMPLETE
 ==> expected: <true> but was: <false>
```

</details>

### Final local validation

Each class ran separately, sequentially; native XML was archived after each. The corrected regression ran before the fix push; ten existing classes ran in one command set afterward. Final source results: **42/43 passed**. The earlier fixture failure remains recorded above.

| Class | Passed / total | Failed / skipped | Native XML time (ms) | Attempt |
| --- | --- | --- | --- | --- |
| `NoiseProbePhasesTest` | 7/7 | 0 / 0 | 102941 | first corrected-fixture attempt |
| `NetworkPendingConnectTest` | 11/11 | 0 / 0 | 13955 | first attempt |
| `NoiseAlgorithmReadinessTest` | 1/1 | 0 / 0 | 24160 | first attempt |
| `NoiseBindingReadinessTest` | 2/3 | 1 / 0 | 87299 | first attempt |
| `NoiseHandshakeTest` | 6/6 | 0 / 0 | 4714 | first attempt |
| `NoiseMatcherReadinessTest` | 1/1 | 0 / 0 | 16352 | first attempt |
| `NoisePreparationConcurrencyTest` | 1/1 | 0 / 0 | 14261 | first attempt |
| `NoisePreparationFailureTest` | 3/3 | 0 / 0 | 54250 | first attempt |
| `NoisePreparationLifecycleTest` | 5/5 | 0 / 0 | 77991 | first attempt |
| `NoisePreparationReentryTest` | 1/1 | 0 / 0 | 10279 | first attempt |
| `NoisePreparationTrafficTest` | 4/4 | 0 / 0 | 95045 | first attempt |

### First-attempt existing-class failure

Wrapped WebSocket readiness failed: READY at 13,298 ms, body incomplete 16,704 ms later; the restored 30s guard rejected it. The unfinished operation is unknown; no production cause or pre-existing classification is claimed. No retry or test change followed. Full native record:

Failed first-attempt case: `io.libp2p.security.noise.NoiseBindingReadinessTest.sole cold WebSocket host with delegating Noise binding prepares before first stream()`. Its native time is 41620 ms. No unchanged retry or replacement result was used.

```text
java.lang.IllegalStateException: Noise probe exceeded the 30s aggregate deadline while awaiting BODY_COMPLETE; phase durations: READY=13298ms, BODY_COMPLETE pending=16704ms:
NOISE_PROBE_PHASE=READY

	at io.libp2p.security.noise.NoiseProbePhasesKt.awaitNoiseProbe$remaining(NoiseProbePhases.kt:23)
	at io.libp2p.security.noise.NoiseProbePhasesKt.awaitNoiseProbe(NoiseProbePhases.kt:35)
	at io.libp2p.security.noise.NoiseBindingReadinessTestKt.bindingProbe(NoiseBindingReadinessTest.kt:66)
	at io.libp2p.security.noise.NoiseBindingReadinessTestKt.access$bindingProbe(NoiseBindingReadinessTest.kt:1)
	at io.libp2p.security.noise.NoiseBindingReadinessTest.sole cold WebSocket host with delegating Noise binding prepares before first stream(NoiseBindingReadinessTest.kt:24)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
```

Independent coverage/code review passes found no further change needed. The parallel review's only finding was this same bound issue; it is addressed. Spotless passed. No whole-module rerun or effective-CI-green claim is made; earlier module failures and the Actions policy limitation remain.

All source work is pushed. No assertion proposal, other PR, merge, enqueue, build-watchman, handoff edit, deploy or publication.

## Review round 7

The earlier wrapped-WebSocket failure remains unresolved. Four concurrent readiness classes passed 12/12; eight passed 24/24, with every operation completed. No unfinished step was reproduced, no readiness redesign is claimed, and these passes do not replace the retained failure.

[Instrumentation pushed first](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/091eeb261d98d7da943883cb1eb577c942e3b4d1) prints elapsed milliseconds at host construction/start, JFR construction/start/dump, negotiation, Ping, closure and event parsing.

The coverage review's separate EXIT fixture issue was reproduced: 29 seconds of real sleeps before cleanup leave only 1 second before the deadline. A 2.5-second hold after BODY_COMPLETE made the correct helper expire in CLEANUP_COMPLETE and fail the exact EXIT assertion.

[The correction](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/32bc3cdaf77f1646e5baa30ff7aa094906565a32) coordinates real-child phase commands with the existing manual clock. The 31,540 ms schedule, seven scenarios, exact assertions, real/logical 30-second aggregate guard and 30/30/10/5-second caps remain. The same hold now passes the EXIT assertion. Deliberately removing the logical aggregate check made all three over-total assertions fail (4/7 passed; 8,748 ms); the restored fixture passed 7/7 first attempt, 3,634 ms. The mutation was never committed.

On the pushed source, readiness passed alone 3/3, 23,837 ms; eight-way concurrency passed 24/24 (native class 79,970 ms; longest external class 83,089 ms). The eleven required classes passed 43/43 first attempt, zero errors/skips, from separate native Gradle XML:

| Class | Passed | ms |
| --- | --- | --- |
| NetworkPendingConnectTest | 11/11 | 5935 |
| NoiseAlgorithmReadinessTest | 1/1 | 13186 |
| NoiseHandshakeTest | 6/6 | 2216 |
| NoiseMatcherReadinessTest | 1/1 | 11128 |
| NoisePreparationConcurrencyTest | 1/1 | 7220 |
| NoisePreparationFailureTest | 3/3 | 19100 |
| NoisePreparationLifecycleTest | 5/5 | 26106 |
| NoisePreparationReentryTest | 1/1 | 6589 |
| NoisePreparationTrafficTest | 4/4 | 27050 |
| NoiseProbePhasesTest | 7/7 | 3634 |
| NoiseBindingReadinessTest | 3/3 | 23837 |

Final coverage re-read: readiness finding unresolved; EXIT finding fixed. No assertion, count or bound relaxed; no proposal, merge, queue, other PR, handoff edit, deployment or publication. Full records: `out/F68f-findings.md`, `ws/F68f/runtime/`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

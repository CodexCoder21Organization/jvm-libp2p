```text
REVIEW: ADVERSARIAL
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71
HEAD: 2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6
RESULT: FINDINGS
FINDINGS_COUNT: 4
CLASSIFICATION_AUDIT: GossipTwoHostTest.test message larger than mplex frame=unbacked(develop bind/receive failures do not establish the final publish-timeout classification)
CLASSIFICATION_AUDIT: SubscriptionsLimitTest.existing peer subscribed to many topics=backed
CLASSIFICATION_AUDIT: SubscriptionsLimitTest.new peer subscribed to many topics=backed
CLASSIFICATION_AUDIT: SubscriptionsLimitTest.new peer subscribed to few topics=backed
CLASSIFICATION_AUDIT: NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes=backed
CLASSIFICATION_AUDIT: QuicKuboTestJava.pingKubo=backed
TESTS: NoisePreparationLifecycleTest=5 pass, 0 fail, 29606 ms first attempt
TESTS: NoisePreparationConcurrencyTest=1 pass, 0 fail, 4769 ms first attempt
TESTS: NoisePreparationFailureTest=3 pass, 0 fail, 22017 ms first attempt
TESTS: NoisePreparationTrafficTest=4 pass, 0 fail, 14152 ms first attempt
TESTS: NoiseBindingReadinessTest=3 pass, 0 fail, 18818 ms first attempt
TESTS: NoiseHandshakeTest=6 pass, 0 fail, 2951 ms first attempt
TESTS: NetworkPendingConnectTest=11 pass, 0 fail, 5165 ms first attempt
TESTS: CompletedConnectionCloseNotificationTest=1 pass, 0 fail, 9251 ms first attempt
TESTS: ReentrantConnectionCloseNotificationTest=1 pass, 0 fail, 7218 ms first attempt
TESTS: CompletedStreamWriteNotificationTest=6 pass, 0 fail, 57067 ms first attempt
TESTS: CompletedNettyFutureConversionTest=6 pass, 0 fail, 1268 ms first attempt
```

1. **SHOULD_FIX — [NettyChannelClose.kt:49](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6/libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt#L49): completed close can still remain pending.** A real two-host probe called close from eight nested promise callbacks on the owning loop. Netty’s listener-depth limit forced dispatch into the full queue. The future remained pending after drain and a fresh barrier. Preserve actual reentrant ordering while making other terminal observations independent of rejected dispatch.

2. **SHOULD_FIX — [MuxChannel.kt:117](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6/libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/MuxChannel.kt#L117): off-loop rejection leaves writes pending.** The probe demonstrated a successful parent result, two pending child writes and a retained payload after queue recovery. Explicit child close finally released them. Stock Mplex/Yamux use the same loop; this path is reachable through custom public muxers returning promises on another executor. Add settlement on the owning loop instead of only rethrowing. Both code findings are inherited cases retained by the new guards.

3. **SHOULD_FIX — [F68c-final.md:10](/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/F68c-final.md:10): gossip classification exceeds its evidence.** The develop control failed before publish, and the formatting profile came from a prior PR head. Mark the specific publish timeout unattributed until matching develop evidence exists. No PR71 cause or worsening was established.

4. **NIT — [NettyExt.kt:15](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6/libp2p/src/main/kotlin/io/libp2p/etc/types/NettyExt.kt#L15): formatting check fails.** The corrected check confirmed ten inputs and reported violations in this file and five added test files. Apply the repository formatter to the changed files.

No existing tests or bounds were changed. No new artifacts were committed. Inherited `.evidence/FURR3/` artifacts remain: `binding-class-hashes-before.txt` 411 bytes, `findings.md` 10,992 bytes, and `stream-descriptors-before.txt` 1,328 bytes.

Checkout clean; owned processes stopped and `ps` audit completed. [Full findings, probes and logs](/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/R71adv2-findings.md).
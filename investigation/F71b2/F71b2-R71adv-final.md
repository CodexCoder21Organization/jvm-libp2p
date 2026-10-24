```text
REVIEW: ADVERSARIAL
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71
HEAD: 8eabea8649ac5bc301615dbea6a5f06ac8c477bc
RESULT: FINDINGS
FINDINGS_COUNT: 3
CLASSIFICATION_AUDIT: GossipTwoHostTest.test message larger than mplex frame=backed (develop bind/receive failures; exact publish-timeout cause remains INFER)
CLASSIFICATION_AUDIT: SubscriptionsLimitTest.existing peer subscribed to many topics=backed
CLASSIFICATION_AUDIT: SubscriptionsLimitTest.new peer subscribed to many topics=backed
CLASSIFICATION_AUDIT: SubscriptionsLimitTest.new peer subscribed to few topics=backed
CLASSIFICATION_AUDIT: NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes=backed
CLASSIFICATION_AUDIT: QuicKuboTestJava.pingKubo=backed
TESTS: NoisePreparationLifecycleTest=5 pass, 0 fail, 30369 ms first attempt
TESTS: NoisePreparationConcurrencyTest=1 pass, 0 fail, 8706 ms first attempt
TESTS: NoisePreparationFailureTest=3 pass, 0 fail, 30440 ms first attempt
TESTS: NoisePreparationTrafficTest=4 pass, 0 fail, 27177 ms first attempt
TESTS: NoiseBindingReadinessTest=3 pass, 0 fail, 43826 ms first attempt
TESTS: NoiseHandshakeTest=6 pass, 0 fail, 10793 ms first attempt
TESTS: NetworkPendingConnectTest=11 pass, 0 fail, 15929 ms first attempt
TESTS: CompletedNettyFutureConversionTest=6 pass, 0 fail, 4465 ms first attempt
```

1. **SHOULD_FIX — `NettyChannelClose.kt:48`: completed close notification can still be lost.** This is inherited, not introduced by this PR. With two real hosts, I closed the peer, filled the surviving event loop’s bounded queue, then called `Connection.close()`. Listener submission was rejected; the returned future remained pending after the queue drained and a fresh loop barrier completed. This branch bypasses the corrected converters. Make terminal close completion independent of accepted listener dispatch while preserving reentrant listener ordering, and add this regression case.

2. **NIT — `FORK.md:45`: stale source coordinate.** It identifies snapshot-37 as the current source version; `build.gradle.kts` now assigns snapshot-41. Update the documented coordinate while retaining the distinction between assignment and publication.

3. **NIT — PR description:9: required review context is missing.** The opening explains why, and the footer is correct, but the description lacks the handoff link, applicable review-round record, and explicit explanation that Actions does not execute tests. Add those facts and state that no existing tests were edited.

No new artifacts were committed. Inherited support artifacts remain: `.evidence/FURR3/binding-class-hashes-before.txt` **411 bytes**, `findings.md` **10,992 bytes**, and `stream-descriptors-before.txt` **1,328 bytes**.

All owned processes stopped; Gradle shutdown and process audit completed. Checkout remains clean. [Full findings and reproduction evidence](/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/R71adv-findings.md).
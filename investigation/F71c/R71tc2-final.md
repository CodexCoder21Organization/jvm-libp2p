```text
REVIEW: TEST_COMPREHENSIVENESS
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71
HEAD: 2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6
RESULT: FINDINGS
FINDINGS_COUNT: 2
RED_WITH_FIX_REVERTED: NoisePreparationTrafficTest=passed 4/4; preparation unchanged from develop
RED_WITH_FIX_REVERTED: NoiseBindingReadinessTest=passed 3/3; preparation unchanged from develop
RED_WITH_FIX_REVERTED: CompletedNettyFutureConversionTest=6 failures; “A settled operation must remain observable when listener submission is rejected”
RED_WITH_FIX_REVERTED: CompletedConnectionCloseNotificationTest=1 failure; “A peer-closed connection must complete close after the event loop recovers”
RED_WITH_FIX_REVERTED: CompletedStreamWriteNotificationTest=6 failures; “A completed parent write must complete its child after the event loop recovers”
TESTS: NoisePreparationConcurrencyTest=1 pass, 0 fail, 7333 ms first attempt
TESTS: NoisePreparationTrafficTest=4 pass, 0 fail, 28161 ms first attempt
TESTS: NoisePreparationFailureTest=3 pass, 0 fail, 18806 ms first attempt
TESTS: NoisePreparationLifecycleTest=5 pass, 0 fail, 16660 ms first attempt
TESTS: NoiseBindingReadinessTest=3 pass, 0 fail, 16019 ms first attempt
TESTS: NoiseAlgorithmReadinessTest=1 pass, 0 fail, 7030 ms first attempt
TESTS: NoiseQueuedReadyCallbackTest=1 pass, 0 fail, 3416 ms first attempt
TESTS: CompletedNettyFutureConversionTest=6 pass, 0 fail, 1470 ms first attempt
TESTS: PendingNettyFutureConversionTest=4 pass, 0 fail, 875 ms first attempt
TESTS: NettyFutureConversionStateTest=14 pass, 0 fail, 1570 ms first attempt
TESTS: CompletedConnectionCloseNotificationTest=1 pass, 0 fail, 3610 ms first attempt
TESTS: ReentrantConnectionCloseNotificationTest=1 pass, 0 fail, 2839 ms first attempt
TESTS: CompletedStreamWriteNotificationTest=6 pass, 0 fail, 31175 ms first attempt
TESTS: OffThreadConnectionCloseNotificationReviewTest=0 pass, 1 fail, 6557 ms first attempt (head probe)
TESTS: OffThreadConnectionCloseNotificationReviewTest=1 pass, 0 fail, 3029 ms first attempt (close-only reversal)
TESTS: NoisePreparationTrafficTest=4 pass, 0 fail, 21378 ms first attempt (production reverted)
TESTS: NoiseBindingReadinessTest=3 pass, 0 fail, 27364 ms first attempt (production reverted)
TESTS: CompletedNettyFutureConversionTest=0 pass, 6 fail, 2915 ms first attempt (production reverted)
TESTS: CompletedConnectionCloseNotificationTest=0 pass, 1 fail, 7829 ms first attempt (production reverted)
TESTS: CompletedStreamWriteNotificationTest=0 pass, 6 fail, 48408 ms first attempt (production reverted)
TESTS: CompletedStreamWriteNotificationTest=6 pass, 0 fail, 51296 ms first attempt (replacement-cause mutation)
```

1. **SHOULD_FIX — [NettyChannelClose.kt:52](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6/libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt#L52):** Add a test where an early physical-close callback hands `close()` to another thread before returning. The existing guard tests only same-loop re-entry. A real-host probe observed `early listener → close operation → later listener` at this head; reverting only the close change restored completion after the later listener. Concurrent callback callers therefore remain uncovered, with a demonstrated ordering change.

2. **SHOULD_FIX — [CompletedStreamWriteNotificationTest.kt:163](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6/libp2p/src/test/kotlin/io/libp2p/transport/CompletedStreamWriteNotificationTest.kt#L163):** Both rejected-write cases assert only exception type. Replacing the real rejection with a new exception carrying different text left all six tests green. Assert the original message—currently `null`—for both muxers so changed failure text is caught.

No existing tests were edited or bounds weakened. Noise reversal passes refute the brief’s preparation-change premise; that behavior already exists on `develop`.

Exact source restored, checkout clean, and all owned processes stopped. [Full findings and evidence](/tmp/claude-1000/-code/4127b3f9-6050-446f-a28d-b0511dacd396/scratchpad/hq/out/R71tc2-findings.md).
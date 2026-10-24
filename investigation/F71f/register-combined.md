| Class | First-attempt tests | Failures | Errors | XML ms | Round 5 reproduction attempts (tests/failures/errors; XML ms) |
|---|---:|---:|---:|---:|---|
| NoisePreparationLifecycleTest | 5 | 0 | 0 | 52276 | — |
| NoisePreparationConcurrencyTest | 1 | 0 | 0 | 11221 | — |
| NoisePreparationFailureTest | 3 | 0 | 0 | 40269 | — |
| NoisePreparationTrafficTest | 4 | 0 | 0 | 83231 | — |
| NoiseBindingReadinessTest | 3 | 1 | 0 | 90129 | 6 attempts, each 3/0/0; 24047, 30327, 24108, 16147, 26774, 22407 ms |
| NoiseHandshakeTest | 6 | 0 | 0 | 15967 | — |
| NetworkPendingConnectTest | 11 | 0 | 0 | 22732 | — |
| CompletedNettyFutureConversionTest | 6 | 0 | 0 | 7487 | — |
| PendingNettyFutureConversionTest | 4 | 0 | 0 | 5233 | — |
| NettyFutureConversionStateTest | 14 | 0 | 0 | 4102 | — |
| CompletedConnectionCloseNotificationTest | 1 | 0 | 0 | 17004 | — |
| ReentrantConnectionCloseNotificationTest | 1 | 0 | 0 | 6171 | — |
| CompletedStreamWriteNotificationTest | 6 | 0 | 0 | 58896 | — |
| NestedConnectionCloseNotificationTest | 1 | 0 | 0 | 10258 | — |
| OffLoopParentWriteNotificationTest | 6 | 0 | 0 | 32746 | — |
| OffThreadConnectionCloseNotificationTest | 1 | 0 | 0 | 4834 | — |
| CompletedCapacityResetNotificationTest | 2 | 0 | 0 | 17402 | — |
| BufferedHalfCloseFramesTest | 14 | 0 | 0 | 7210 | — |
| YamuxPrefixFrameFailureTest | 1 | 0 | 0 | 4372 | — |
| ParentCloseOwnershipTest | 2 | 0 | 0 | 8706 | — |
| ConnectionCloseRetryTest | 10 | 0 | 0 | 17422 | — |
| ReviewTerminalWriteConversionTest | 4 | 0 | 0 | 54090 | — |
| ReviewCompletedShutdownTest | 6 | 0 | 0 | 48498 | — |
| ReviewChildLoopAffinityTest | 3 | 0 | 0 | 19849 | — |
| NoiseAlgorithmReadinessTest | 1 | 0 | 0 | 22214 | 3 attempts, each 1/0/0; 8479, 12200, 13832 ms |
| NoiseHandshakeOffEventLoopTest | 29 | 0 | 0 | 50682 | First attempt completed in round 5 |

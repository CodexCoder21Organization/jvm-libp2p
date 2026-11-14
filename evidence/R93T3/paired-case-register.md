OBSERVED: Paired register64/64 first attempts; head64/64 pass; develop-production rollback11/64 pass,53/64 fail,0 errors/skips/retries. All39 test-file SHA256 values match across trees. A broad rollback failure at setup, cleanup or a different assertion is not proof of the narrow named condition.

OBSERVED: Case01/64 ConnectionCloseRetryTest.explicitRetryClosesRealConnectionAfterFailureAndKeepsOrdinaryResult(): head PASS, old PASS; old location(s) none.
OBSERVED: Case02/64 ConnectionCloseRetryTest.concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges(): head PASS, old FAIL; old location(s) ConnectionCloseRetryTest.kt:338, ConnectionCloseRetryTest.kt:1, ConnectionCloseRetryTest.kt:196, ConnectionCloseRetryTest.kt:193.
OBSERVED: Case03/64 ConnectionCloseRetryTest.firstRetryPublishesOwnershipBeforeItsPhysicalHandlerReenters(): head PASS, old PASS; old location(s) none.
OBSERVED: Case04/64 ConnectionCloseRetryTest.retryStartsInitialCloseAndSuccessRemainsTerminal(): head PASS, old PASS; old location(s) none.
OBSERVED: Case05/64 ConnectionCloseRetryTest.retryFirstStartsOnlyOneAttemptEvenWhenItFailsImmediately(): head PASS, old PASS; old location(s) none.
OBSERVED: Case06/64 ConnectionCloseRetryTest.failureCallbackAndPhysicalHandlerCanReenterWithoutDuplicatingRetry(): head PASS, old PASS; old location(s) none.
OBSERVED: Case07/64 ConnectionCloseRetryTest.transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult(): head PASS, old FAIL; old location(s) ConnectionCloseRetryTest.kt:338, ConnectionCloseRetryTest.kt:1, ConnectionCloseRetryTest.kt:126, ConnectionCloseRetryTest.kt:123.
OBSERVED: Case08/64 ConnectionCloseRetryTest.retrySharesPendingInitialCloseAndCanRecoverItsFailure(): head PASS, old PASS; old location(s) none.
OBSERVED: Case09/64 ConnectionCloseRetryTest.repeatedFailuresReportEachAttemptAndPermitRecovery(): head PASS, old PASS; old location(s) none.
OBSERVED: Case10/64 ConnectionCloseRetryTest.retryAfterPeerClosedFollowingFailureDoesNotRequestAnotherPhysicalClose(): head PASS, old PASS; old location(s) none.
OBSERVED: Case11/64 F93bQuicDialPublicationTest.shutdownFromTheDialVisitorPreventsLateConnectionPublication(): head PASS, old FAIL; old location(s) F93bQuicDialPublicationTest.kt:40.
OBSERVED: Case12/64 F93bQuicRepeatedCloseOrderingTest.repeatedShutdownCannotCloseUdpParentBeforeTheFirstChildOperationSettles(): head PASS, old FAIL; old location(s) F93bQuicRepeatedCloseOrderingTest.kt:82.
OBSERVED: Case13/64 F93bQuicSecondaryCloseFailureTest.[1] false: head PASS, old FAIL; old location(s) F93bQuicSecondaryCloseFailureTest.kt:97.
OBSERVED: Case14/64 F93bQuicSecondaryCloseFailureTest.[2] true: head PASS, old FAIL; old location(s) F93bQuicSecondaryCloseFailureTest.kt:35, F93bQuicSecondaryCloseFailureTest.kt:141.
OBSERVED: Case15/64 F93bQuicTerminatedLoopTest.udpParentCloseSettlesWhenItsLoopWasAlreadyTerminated(): head PASS, old FAIL; old location(s) F93bQuicTerminatedLoopTest.kt:69.
OBSERVED: Case16/64 F93bQuicUnsuppressedCloseFailureTest.[1] false: head PASS, old FAIL; old location(s) F93bQuicUnsuppressedCloseFailureTest.kt:97.
OBSERVED: Case17/64 F93bQuicUnsuppressedCloseFailureTest.[2] true: head PASS, old FAIL; old location(s) F93bQuicUnsuppressedCloseFailureTest.kt:35, F93bQuicUnsuppressedCloseFailureTest.kt:140.
OBSERVED: Case18/64 F93bRepeatedQuicShutdownFailureTest.[1] false: head PASS, old FAIL; old location(s) F93bRepeatedQuicShutdownFailureTest.kt:97.
OBSERVED: Case19/64 F93bRepeatedQuicShutdownFailureTest.[2] true: head PASS, old FAIL; old location(s) F93bRepeatedQuicShutdownFailureTest.kt:35, F93bRepeatedQuicShutdownFailureTest.kt:136.
OBSERVED: Case20/64 F93bRepeatedTcpShutdownFailureTest.oneListenerCloseFailureStillClosesEveryListenerAndAcceptedRoot(): head PASS, old FAIL; old location(s) F93bRepeatedTcpShutdownFailureTest.kt:105.
OBSERVED: Case21/64 F93cQuicBindShutdownTest.shutdownWhileBindIsQueuedRejectsTheDialBeforeHandshakePublication(): head PASS, old FAIL; old location(s) F93cQuicBindShutdownTest.kt:45.
OBSERVED: Case22/64 F93cQuicDialChurnTest.closingChildrenReleasesTheirUdpParentsThroughoutLongLivedDialChurn(): head PASS, old FAIL; old location(s) F93cQuicDialChurnTest.kt:34.
OBSERVED: Case23/64 F93cQuicListenShutdownTest.closeDuringListenerCertificatePreparationPreventsLateListenerPublication(): head PASS, old FAIL; old location(s) F93cQuicListenShutdownTest.kt:44.
OBSERVED: Case24/64 F93cQuicLiveChildRejectedLoopTest.liveChildAndUdpParentSettleWhenTheirLoopRejectsTransportShutdown(): head PASS, old FAIL; old location(s) F93cQuicLiveChildRejectedLoopTest.kt:82.
OBSERVED: Case25/64 F93cQuicMultipleCloseFailuresTest.shutdownRetainsEveryChildAndUdpParentCloseFailure(): head PASS, old FAIL; old location(s) F93cQuicMultipleCloseFailuresTest.kt:44, F93cQuicMultipleCloseFailuresTest.kt:208.
OBSERVED: Case26/64 F93cQuicRejectedLoopTest.releasedUdpParentKeepsItsSuccessfulResultWhenItsLoopLaterRejectsDispatch(): head PASS, old FAIL; old location(s) F93cQuicRejectedLoopTest.kt:59.
OBSERVED: Case27/64 F93cQuicTerminationFailureRetentionTest.firstTransportShutdownAfterLoopTerminationRetainsEarlierConnectionRejection(): head PASS, old FAIL; old location(s) F93cQuicTerminationFailureRetentionTest.kt:119, NettyChannelClose.kt:77, NettyChannelClose.kt:51, NettyChannelClose.kt:40.
OBSERVED: Case28/64 F93dQuicFailedHandshakeTest.failedHandshakeReleasesItsBoundUdpPortWhileTheTransportRemainsUsable(): head PASS, old FAIL; old location(s) F93dQuicFailedHandshakeTest.kt:57, F93dQuicFailedHandshakeTest.kt:60.
OBSERVED: Case29/64 F93dQuicHandshakeShutdownTest.shutdownSettlesAnInFlightHandshakeAndReleasesItsBoundUdpPort(): head PASS, old FAIL; old location(s) F93dQuicHandshakeShutdownTest.kt:61.
OBSERVED: Case30/64 F93dQuicOrdinaryCloseOrderingTest.shutdownWaitsForAnOrdinaryChildCloseThatWasAlreadyPhysicallyRemoved(): head PASS, old FAIL; old location(s) F93dQuicOrdinaryCloseOrderingTest.kt:87.
OBSERVED: Case31/64 F93dQuicShutdownHandoffOrderingTest.anOrdinaryCloseAlreadyInFlightHandsItsParentToTheWholeShutdownBarrier(): head PASS, old FAIL; old location(s) F93dQuicShutdownHandoffOrderingTest.kt:85.
OBSERVED: Case32/64 F93hQuicConnectionSetupReleaseTest.failedDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(): head PASS, old FAIL; old location(s) F93hQuicConnectionSetupReleaseTest.kt:57.
OBSERVED: Case33/64 F93hQuicDatagramReleaseTest.failedDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(): head PASS, old FAIL; old location(s) F93hQuicDatagramReleaseTest.kt:73.
OBSERVED: Case34/64 F93jCallbackFailedParentReleaseTest.callbackClosedDialWaitsForFailedPhysicalParentRelease(): head PASS, old FAIL; old location(s) F93jCallbackFailedParentReleaseTest.kt:69.
OBSERVED: Case35/64 F93jCallbackPendingChildReleaseTest.callbackCloseRequestKeepsTheDialPendingUntilChildAndParentRelease(): head PASS, old FAIL; old location(s) F93jCallbackPendingChildReleaseTest.kt:53.
OBSERVED: Case36/64 F93jQuicFailedDialResourceTest.sequentialFailedDialsReleasePhysicalResourcesAndKeepTheTransportUsable(): head PASS, old FAIL; old location(s) F93jQuicFailedDialResourceTest.kt:194, F93jQuicFailedDialResourceTest.kt:239.
OBSERVED: Case37/64 F93jQuicReleaseReentryTest.releaseStartedFromAnotherReleaseNotificationIsSettledOnTheNextSelectorBatch(): head PASS, old FAIL; old location(s) F93jQuicReleaseReentryTest.kt:62.
OBSERVED: Case38/64 F93jQuicSameLoopReleaseTest.sameLoopDialReleasesDoNotCompleteBeforeBothParentsRelease(): head PASS, old FAIL; old location(s) F93jQuicSameLoopReleaseTest.kt:60.
OBSERVED: Case39/64 F93mPublishedCloseFailureTest.laterShutdownFailuresDoNotChangePublishedListenerErrors(): head PASS, old FAIL; old location(s) F93mPublishedCloseFailureTest.kt:109.
OBSERVED: Case40/64 F93mQuicDestroyedLoopReleaseTest.pendingAndReentrantAndLaterDialReleasesSettleAfterSelectorDestruction(): head PASS, old FAIL; old location(s) F93mQuicDestroyedLoopReleaseTest.kt:50, F93mQuicDestroyedLoopReleaseTest.kt:110, F93mQuicDestroyedLoopReleaseTest.kt:121, F93mQuicDestroyedLoopReleaseTest.kt:1.
OBSERVED: Case41/64 ParentCloseOwnershipTest.networkAndConnectionShutdownShareOnePendingPhysicalClose(): head PASS, old PASS; old location(s) none.
OBSERVED: Case42/64 ParentCloseOwnershipTest.failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest(): head PASS, old FAIL; old location(s) ParentCloseOwnershipTest.kt:123.
OBSERVED: Case43/64 PlainNettyTransportFailedCloseSetTest.oneListenerCloseFailureStillClosesEveryListenerAndAcceptedRoot(): head PASS, old FAIL; old location(s) PlainNettyTransportFailedCloseSetTest.kt:105.
OBSERVED: Case44/64 PlainNettyTransportFailedCloseTest.[1] listener: head PASS, old FAIL; old location(s) PlainNettyTransportFailedCloseTest.kt:107.
OBSERVED: Case45/64 PlainNettyTransportFailedCloseTest.[2] connection: head PASS, old FAIL; old location(s) PlainNettyTransportFailedCloseTest.kt:107.
OBSERVED: Case46/64 PlainNettyTransportFailedCloseTest.[3] listener-throw: head PASS, old FAIL; old location(s) PlainNettyTransportFailedCloseTest.kt:107.
OBSERVED: Case47/64 PlainNettyTransportFailedCloseTest.[4] listener-deferred: head PASS, old FAIL; old location(s) PlainNettyTransportFailedCloseTest.kt:107.
OBSERVED: Case48/64 PlainNettyTransportFailedCloseTest.[5] caller-cancel: head PASS, old FAIL; old location(s) PlainNettyTransportFailedCloseTest.kt:107.
OBSERVED: Case49/64 PlainNettyTransportFailedCloseTest.[6] caller-complete: head PASS, old FAIL; old location(s) PlainNettyTransportFailedCloseTest.kt:107.
OBSERVED: Case50/64 PlainNettyTransportFailedCloseTest.[7] success: head PASS, old PASS; old location(s) none.
OBSERVED: Case51/64 QuicTransportClosedChildParentTest.closingAnOutboundChildDoesNotDiscardItsUdpParentsShutdownOwner(): head PASS, old FAIL; old location(s) QuicTransportClosedChildParentTest.kt:63.
OBSERVED: Case52/64 QuicTransportFailedCloseTest.[1] listener: head PASS, old FAIL; old location(s) QuicTransportFailedCloseTest.kt:96.
OBSERVED: Case53/64 QuicTransportFailedCloseTest.[2] listener-throw: head PASS, old FAIL; old location(s) QuicTransportFailedCloseTest.kt:96.
OBSERVED: Case54/64 QuicTransportFailedCloseTest.[3] listener-deferred: head PASS, old FAIL; old location(s) QuicTransportFailedCloseTest.kt:96.
OBSERVED: Case55/64 QuicTransportFailedCloseTest.[4] caller-cancel: head PASS, old FAIL; old location(s) QuicTransportFailedCloseTest.kt:96.
OBSERVED: Case56/64 QuicTransportFailedCloseTest.[5] caller-complete: head PASS, old FAIL; old location(s) QuicTransportFailedCloseTest.kt:96.
OBSERVED: Case57/64 QuicTransportFailedCloseTest.[6] success: head PASS, old PASS; old location(s) none.
OBSERVED: Case58/64 QuicTransportFailedConnectionSetTest.oneOutboundCloseFailureStillClosesEveryConnectionAndUdpParent(): head PASS, old FAIL; old location(s) QuicTransportFailedConnectionSetTest.kt:108.
OBSERVED: Case59/64 QuicTransportFailedDatagramCloseTest.[1] false: head PASS, old FAIL; old location(s) QuicTransportFailedDatagramCloseTest.kt:97.
OBSERVED: Case60/64 QuicTransportFailedDatagramCloseTest.[2] true: head PASS, old FAIL; old location(s) QuicTransportFailedDatagramCloseTest.kt:35, QuicTransportFailedDatagramCloseTest.kt:131.
OBSERVED: Case61/64 R93AConnectionHandlerDialReleaseTest.connectionHandlerClosedDialWaitsForItsUdpPortRelease(): head PASS, old FAIL; old location(s) R93AConnectionHandlerDialReleaseTest.kt:55.
OBSERVED: Case62/64 R93ARejectedOpenParentDialTest.rejectedCloseLeavingParentOpenDoesNotWaitForSelectorRelease(): head PASS, old FAIL; old location(s) R93ARejectedOpenParentDialTest.kt:61.
OBSERVED: Case63/64 R93ASuccessfulClosedDialReleaseTest.successfulClosedDialWaitsForItsUdpPortRelease(): head PASS, old FAIL; old location(s) R93ASuccessfulClosedDialReleaseTest.kt:55.
OBSERVED: Case64/64 R93TQuicSuccessfulDialReleaseTest.successfulDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(): head PASS, old FAIL; old location(s) R93TQuicSuccessfulDialReleaseTest.kt:55.

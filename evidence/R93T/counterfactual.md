OBSERVED: Counterfactual ledger covers all53 selected cases from28 touched test files; every case ran once on develop and once on exact head, with0skips and0retries. The selector control adds1 attempt for each of2cases, using68c436af and identical fixture source.

INFER: A failed case is not automatically an intended-assertion reproduction. The saved XML and location columns distinguish preparation, body and cleanup; findings.md states the limits. The11 old-source passes include9 existing-test cases and2 successful-close controls.

OBSERVED: ConnectionCloseRetryTest.explicitRetryClosesRealConnectionAfterFailureAndKeepsOrdinaryResult(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges(); existing default test; develop=fail; head=pass; reported failure locations=ConnectionCloseRetryTest.kt:324,ConnectionCloseRetryTest.kt:357,ConnectionCloseRetryTest.kt:1,ConnectionCloseRetryTest.kt:185; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.firstRetryPublishesOwnershipBeforeItsPhysicalHandlerReenters(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.retryStartsInitialCloseAndSuccessRemainsTerminal(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.retryFirstStartsOnlyOneAttemptEvenWhenItFailsImmediately(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.failureCallbackAndPhysicalHandlerCanReenterWithoutDuplicatingRetry(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult(); existing default test; develop=fail; head=pass; reported failure locations=ConnectionCloseRetryTest.kt:324,ConnectionCloseRetryTest.kt:357,ConnectionCloseRetryTest.kt:1,ConnectionCloseRetryTest.kt:122; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.retrySharesPendingInitialCloseAndCanRecoverItsFailure(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.repeatedFailuresReportEachAttemptAndPermitRecovery(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ConnectionCloseRetryTest.retryAfterPeerClosedFollowingFailureDoesNotRequestAnotherPhysicalClose(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: F93bQuicDialPublicationTest.shutdownFromTheDialVisitorPreventsLateConnectionPublication(); PR-added test; develop=fail; head=pass; reported failure locations=F93bQuicDialPublicationTest.kt:40; attempts=1 per source.

OBSERVED: F93bQuicRepeatedCloseOrderingTest.repeatedShutdownCannotCloseUdpParentBeforeTheFirstChildOperationSettles(); PR-added test; develop=fail; head=pass; reported failure locations=F93bQuicRepeatedCloseOrderingTest.kt:82; attempts=1 per source.

OBSERVED: F93bQuicSecondaryCloseFailureTest.[1] false; PR-added test; develop=fail; head=pass; reported failure locations=F93bQuicSecondaryCloseFailureTest.kt:97; attempts=1 per source.

OBSERVED: F93bQuicSecondaryCloseFailureTest.[2] true; PR-added test; develop=fail; head=pass; reported failure locations=F93bQuicSecondaryCloseFailureTest.kt:35,F93bQuicSecondaryCloseFailureTest.kt:139; attempts=1 per source.

OBSERVED: F93bQuicTerminatedLoopTest.udpParentCloseSettlesWhenItsLoopWasAlreadyTerminated(); PR-added test; develop=fail; head=pass; reported failure locations=F93bQuicTerminatedLoopTest.kt:69; attempts=1 per source.

OBSERVED: F93bQuicUnsuppressedCloseFailureTest.[1] false; PR-added test; develop=fail; head=pass; reported failure locations=F93bQuicUnsuppressedCloseFailureTest.kt:97; attempts=1 per source.

OBSERVED: F93bQuicUnsuppressedCloseFailureTest.[2] true; PR-added test; develop=fail; head=pass; reported failure locations=F93bQuicUnsuppressedCloseFailureTest.kt:35,F93bQuicUnsuppressedCloseFailureTest.kt:140; attempts=1 per source.

OBSERVED: F93bRepeatedQuicShutdownFailureTest.[1] false; PR-added test; develop=fail; head=pass; reported failure locations=F93bRepeatedQuicShutdownFailureTest.kt:97; attempts=1 per source.

OBSERVED: F93bRepeatedQuicShutdownFailureTest.[2] true; PR-added test; develop=fail; head=pass; reported failure locations=F93bRepeatedQuicShutdownFailureTest.kt:106; attempts=1 per source.

OBSERVED: F93bRepeatedTcpShutdownFailureTest.oneListenerCloseFailureStillClosesEveryListenerAndAcceptedRoot(); PR-added test; develop=fail; head=pass; reported failure locations=F93bRepeatedTcpShutdownFailureTest.kt:105; attempts=1 per source.

OBSERVED: F93cQuicBindShutdownTest.shutdownWhileBindIsQueuedRejectsTheDialBeforeHandshakePublication(); PR-added test; develop=fail; head=pass; reported failure locations=F93cQuicBindShutdownTest.kt:45,F93cQuicBindShutdownTest.kt:45; attempts=1 per source.

OBSERVED: F93cQuicDialChurnTest.closingChildrenReleasesTheirUdpParentsThroughoutLongLivedDialChurn(); PR-added test; develop=fail; head=pass; reported failure locations=F93cQuicDialChurnTest.kt:34; attempts=1 per source.

OBSERVED: F93cQuicListenShutdownTest.closeDuringListenerCertificatePreparationPreventsLateListenerPublication(); PR-added test; develop=fail; head=pass; reported failure locations=F93cQuicListenShutdownTest.kt:44; attempts=1 per source.

OBSERVED: F93cQuicLiveChildRejectedLoopTest.liveChildAndUdpParentSettleWhenTheirLoopRejectsTransportShutdown(); PR-added test; develop=fail; head=pass; reported failure locations=F93cQuicLiveChildRejectedLoopTest.kt:82; attempts=1 per source.

OBSERVED: F93cQuicMultipleCloseFailuresTest.shutdownRetainsEveryChildAndUdpParentCloseFailure(); PR-added test; develop=fail; head=pass; reported failure locations=F93cQuicMultipleCloseFailuresTest.kt:44,F93cQuicMultipleCloseFailuresTest.kt:204; attempts=1 per source.

OBSERVED: F93cQuicRejectedLoopTest.releasedUdpParentKeepsItsSuccessfulResultWhenItsLoopLaterRejectsDispatch(); PR-added test; develop=fail; head=pass; reported failure locations=F93cQuicRejectedLoopTest.kt:59; attempts=1 per source.

OBSERVED: F93cQuicTerminationFailureRetentionTest.firstTransportShutdownAfterLoopTerminationRetainsEarlierConnectionRejection(); PR-added test; develop=fail; head=pass; reported failure locations=F93cQuicTerminationFailureRetentionTest.kt:119,F93cQuicTerminationFailureRetentionTest.kt:65; attempts=1 per source.

OBSERVED: F93dQuicFailedHandshakeTest.failedHandshakeReleasesItsBoundUdpPortWhileTheTransportRemainsUsable(); PR-added test; develop=fail; head=pass; reported failure locations=F93dQuicFailedHandshakeTest.kt:57,F93dQuicFailedHandshakeTest.kt:60; attempts=1 per source.

OBSERVED: F93dQuicHandshakeShutdownTest.shutdownSettlesAnInFlightHandshakeAndReleasesItsBoundUdpPort(); PR-added test; develop=fail; head=pass; reported failure locations=F93dQuicHandshakeShutdownTest.kt:61; attempts=1 per source.

OBSERVED: F93dQuicOrdinaryCloseOrderingTest.shutdownWaitsForAnOrdinaryChildCloseThatWasAlreadyPhysicallyRemoved(); PR-added test; develop=fail; head=pass; reported failure locations=F93dQuicOrdinaryCloseOrderingTest.kt:87; attempts=1 per source.

OBSERVED: F93dQuicShutdownHandoffOrderingTest.anOrdinaryCloseAlreadyInFlightHandsItsParentToTheWholeShutdownBarrier(); PR-added test; develop=fail; head=pass; reported failure locations=F93dQuicShutdownHandoffOrderingTest.kt:85; attempts=1 per source.

OBSERVED: F93hQuicConnectionSetupReleaseTest.failedDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(); PR-added test; develop=fail; head=pass; reported failure locations=F93hQuicConnectionSetupReleaseTest.kt:57; pre-selector=fail, intended assertion=F93hQuicConnectionSetupReleaseTest.kt:61; attempts=1 per source.

OBSERVED: F93hQuicDatagramReleaseTest.failedDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(); PR-added test; develop=fail; head=pass; reported failure locations=F93hQuicDatagramReleaseTest.kt:71; pre-selector=fail, intended assertion=F93hQuicDatagramReleaseTest.kt:62; attempts=1 per source.

OBSERVED: ParentCloseOwnershipTest.networkAndConnectionShutdownShareOnePendingPhysicalClose(); existing default test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: ParentCloseOwnershipTest.failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest(); existing default test; develop=fail; head=pass; reported failure locations=ParentCloseOwnershipTest.kt:122; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseSetTest.oneListenerCloseFailureStillClosesEveryListenerAndAcceptedRoot(); PR-added test; develop=fail; head=pass; reported failure locations=PlainNettyTransportFailedCloseSetTest.kt:105; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseTest.[1] listener; PR-added test; develop=fail; head=pass; reported failure locations=PlainNettyTransportFailedCloseTest.kt:107; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseTest.[2] connection; PR-added test; develop=fail; head=pass; reported failure locations=PlainNettyTransportFailedCloseTest.kt:107; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseTest.[3] listener-throw; PR-added test; develop=fail; head=pass; reported failure locations=PlainNettyTransportFailedCloseTest.kt:107; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseTest.[4] listener-deferred; PR-added test; develop=fail; head=pass; reported failure locations=PlainNettyTransportFailedCloseTest.kt:107; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseTest.[5] caller-cancel; PR-added test; develop=fail; head=pass; reported failure locations=PlainNettyTransportFailedCloseTest.kt:107; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseTest.[6] caller-complete; PR-added test; develop=fail; head=pass; reported failure locations=PlainNettyTransportFailedCloseTest.kt:107; attempts=1 per source.

OBSERVED: PlainNettyTransportFailedCloseTest.[7] success; PR-added test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: QuicTransportClosedChildParentTest.closingAnOutboundChildDoesNotDiscardItsUdpParentsShutdownOwner(); PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportClosedChildParentTest.kt:63; attempts=1 per source.

OBSERVED: QuicTransportFailedCloseTest.[1] listener; PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedCloseTest.kt:96; attempts=1 per source.

OBSERVED: QuicTransportFailedCloseTest.[2] listener-throw; PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedCloseTest.kt:96; attempts=1 per source.

OBSERVED: QuicTransportFailedCloseTest.[3] listener-deferred; PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedCloseTest.kt:96; attempts=1 per source.

OBSERVED: QuicTransportFailedCloseTest.[4] caller-cancel; PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedCloseTest.kt:96; attempts=1 per source.

OBSERVED: QuicTransportFailedCloseTest.[5] caller-complete; PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedCloseTest.kt:96; attempts=1 per source.

OBSERVED: QuicTransportFailedCloseTest.[6] success; PR-added test; develop=pass; head=pass; reported failure locations=none; attempts=1 per source.

OBSERVED: QuicTransportFailedConnectionSetTest.oneOutboundCloseFailureStillClosesEveryConnectionAndUdpParent(); PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedConnectionSetTest.kt:108; attempts=1 per source.

OBSERVED: QuicTransportFailedDatagramCloseTest.[1] false; PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedDatagramCloseTest.kt:97; attempts=1 per source.

OBSERVED: QuicTransportFailedDatagramCloseTest.[2] true; PR-added test; develop=fail; head=pass; reported failure locations=QuicTransportFailedDatagramCloseTest.kt:35,QuicTransportFailedDatagramCloseTest.kt:131; attempts=1 per source.

OBSERVED: All 60 cases ran once per source. There are 30 named-body assertion failures and 19 other reported failures in the broad pre-fix rollback; those 19 are not relabelled as named-body proof. The 11 passes are nine unchanged scenarios and two success controls.

OBSERVED: ConnectionCloseRetryTest.concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges(); current 1/1 pass; pre-fix 1/1 fail; reported location ConnectionCloseRetryTest.kt:336; cleanup assertion; earlier body failure may be hidden; not named-body proof from this broad rollback.

OBSERVED: ConnectionCloseRetryTest.explicitRetryClosesRealConnectionAfterFailureAndKeepsOrdinaryResult(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.failureCallbackAndPhysicalHandlerCanReenterWithoutDuplicatingRetry(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.firstRetryPublishesOwnershipBeforeItsPhysicalHandlerReenters(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.repeatedFailuresReportEachAttemptAndPermitRecovery(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.retryAfterPeerClosedFollowingFailureDoesNotRequestAnotherPhysicalClose(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.retryFirstStartsOnlyOneAttemptEvenWhenItFailsImmediately(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.retrySharesPendingInitialCloseAndCanRecoverItsFailure(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.retryStartsInitialCloseAndSuccessRemainsTerminal(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: ConnectionCloseRetryTest.transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult(); current 1/1 pass; pre-fix 1/1 fail; reported location ConnectionCloseRetryTest.kt:336; cleanup assertion; earlier body failure may be hidden; not named-body proof from this broad rollback.

OBSERVED: F93bQuicDialPublicationTest.shutdownFromTheDialVisitorPreventsLateConnectionPublication(); current 1/1 pass; pre-fix 1/1 fail; reported location F93bQuicDialPublicationTest.kt:40; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93bQuicRepeatedCloseOrderingTest.repeatedShutdownCannotCloseUdpParentBeforeTheFirstChildOperationSettles(); current 1/1 pass; pre-fix 1/1 fail; reported location F93bQuicRepeatedCloseOrderingTest.kt:82; cleanup exception; not named-body proof from this broad rollback.

OBSERVED: F93bQuicSecondaryCloseFailureTest.[1] false; current 1/1 pass; pre-fix 1/1 fail; reported location F93bQuicSecondaryCloseFailureTest.kt:97; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93bQuicSecondaryCloseFailureTest.[2] true; current 1/1 pass; pre-fix 1/1 fail; reported location F93bQuicSecondaryCloseFailureTest.kt:35; injected close exception reported at its creation origin; not named-body proof from this broad rollback.

OBSERVED: F93bQuicTerminatedLoopTest.udpParentCloseSettlesWhenItsLoopWasAlreadyTerminated(); current 1/1 pass; pre-fix 1/1 fail; reported location F93bQuicTerminatedLoopTest.kt:69; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93bQuicUnsuppressedCloseFailureTest.[1] false; current 1/1 pass; pre-fix 1/1 fail; reported location F93bQuicUnsuppressedCloseFailureTest.kt:97; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93bQuicUnsuppressedCloseFailureTest.[2] true; current 1/1 pass; pre-fix 1/1 fail; reported location F93bQuicUnsuppressedCloseFailureTest.kt:35; injected close exception reported at its creation origin; not named-body proof from this broad rollback.

OBSERVED: F93bRepeatedQuicShutdownFailureTest.[1] false; current 1/1 pass; pre-fix 1/1 fail; reported location F93bRepeatedQuicShutdownFailureTest.kt:97; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93bRepeatedQuicShutdownFailureTest.[2] true; current 1/1 pass; pre-fix 1/1 fail; reported location F93bRepeatedQuicShutdownFailureTest.kt:106; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93bRepeatedTcpShutdownFailureTest.oneListenerCloseFailureStillClosesEveryListenerAndAcceptedRoot(); current 1/1 pass; pre-fix 1/1 fail; reported location F93bRepeatedTcpShutdownFailureTest.kt:105; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93cQuicBindShutdownTest.shutdownWhileBindIsQueuedRejectsTheDialBeforeHandshakePublication(); current 1/1 pass; pre-fix 1/1 fail; reported location F93cQuicBindShutdownTest.kt:45; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93cQuicDialChurnTest.closingChildrenReleasesTheirUdpParentsThroughoutLongLivedDialChurn(); current 1/1 pass; pre-fix 1/1 fail; reported location F93cQuicDialChurnTest.kt:34; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93cQuicListenShutdownTest.closeDuringListenerCertificatePreparationPreventsLateListenerPublication(); current 1/1 pass; pre-fix 1/1 fail; reported location F93cQuicListenShutdownTest.kt:44; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93cQuicLiveChildRejectedLoopTest.liveChildAndUdpParentSettleWhenTheirLoopRejectsTransportShutdown(); current 1/1 pass; pre-fix 1/1 fail; reported location F93cQuicLiveChildRejectedLoopTest.kt:107; cleanup exception; not named-body proof from this broad rollback.

OBSERVED: F93cQuicMultipleCloseFailuresTest.shutdownRetainsEveryChildAndUdpParentCloseFailure(); current 1/1 pass; pre-fix 1/1 fail; reported location F93cQuicMultipleCloseFailuresTest.kt:44; injected close exception reported at its creation origin; not named-body proof from this broad rollback.

OBSERVED: F93cQuicRejectedLoopTest.releasedUdpParentKeepsItsSuccessfulResultWhenItsLoopLaterRejectsDispatch(); current 1/1 pass; pre-fix 1/1 fail; reported location F93cQuicRejectedLoopTest.kt:59; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93cQuicTerminationFailureRetentionTest.firstTransportShutdownAfterLoopTerminationRetainsEarlierConnectionRejection(); current 1/1 pass; pre-fix 1/1 fail; reported location F93cQuicTerminationFailureRetentionTest.kt:119; cleanup exception; not named-body proof from this broad rollback.

OBSERVED: F93dQuicFailedHandshakeTest.failedHandshakeReleasesItsBoundUdpPortWhileTheTransportRemainsUsable(); current 1/1 pass; pre-fix 1/1 fail; reported location F93dQuicFailedHandshakeTest.kt:57; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93dQuicHandshakeShutdownTest.shutdownSettlesAnInFlightHandshakeAndReleasesItsBoundUdpPort(); current 1/1 pass; pre-fix 1/1 fail; reported location F93dQuicHandshakeShutdownTest.kt:61; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93dQuicOrdinaryCloseOrderingTest.shutdownWaitsForAnOrdinaryChildCloseThatWasAlreadyPhysicallyRemoved(); current 1/1 pass; pre-fix 1/1 fail; reported location F93dQuicOrdinaryCloseOrderingTest.kt:87; cleanup exception; not named-body proof from this broad rollback.

OBSERVED: F93dQuicShutdownHandoffOrderingTest.anOrdinaryCloseAlreadyInFlightHandsItsParentToTheWholeShutdownBarrier(); current 1/1 pass; pre-fix 1/1 fail; reported location F93dQuicShutdownHandoffOrderingTest.kt:85; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93hQuicConnectionSetupReleaseTest.failedDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(); current 1/1 pass; pre-fix 1/1 fail; reported location F93hQuicConnectionSetupReleaseTest.kt:57; fixture gate wait; not named-body proof from this broad rollback.

OBSERVED: F93hQuicDatagramReleaseTest.failedDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(); current 1/1 pass; pre-fix 1/1 fail; reported location F93hQuicDatagramReleaseTest.kt:71; cleanup assertion; earlier body failure may be hidden; not named-body proof from this broad rollback.

OBSERVED: F93jCallbackFailedParentReleaseTest.callbackClosedDialWaitsForFailedPhysicalParentRelease(); current 1/1 pass; pre-fix 1/1 fail; reported location F93jCallbackFailedParentReleaseTest.kt:69; cleanup assertion; earlier body failure may be hidden; not named-body proof from this broad rollback.

OBSERVED: F93jCallbackPendingChildReleaseTest.callbackCloseRequestKeepsTheDialPendingUntilChildAndParentRelease(); current 1/1 pass; pre-fix 1/1 fail; reported location F93jCallbackPendingChildReleaseTest.kt:53; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: F93jQuicSameLoopReleaseTest.sameLoopDialReleasesDoNotCompleteBeforeBothParentsRelease(); current 1/1 pass; pre-fix 1/1 fail; reported location F93jQuicSameLoopReleaseTest.kt:60; fixture gate wait; not named-body proof from this broad rollback.

OBSERVED: ParentCloseOwnershipTest.failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest(); current 1/1 pass; pre-fix 1/1 fail; reported location ParentCloseOwnershipTest.kt:122; cleanup assertion; earlier body failure may be hidden; not named-body proof from this broad rollback.

OBSERVED: ParentCloseOwnershipTest.networkAndConnectionShutdownShareOnePendingPhysicalClose(); current 1/1 pass; pre-fix 1/1 pass; reported location none; unchanged existing scenario; not a regression reproducer.

OBSERVED: PlainNettyTransportFailedCloseSetTest.oneListenerCloseFailureStillClosesEveryListenerAndAcceptedRoot(); current 1/1 pass; pre-fix 1/1 fail; reported location PlainNettyTransportFailedCloseSetTest.kt:105; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: PlainNettyTransportFailedCloseTest.[1] listener; current 1/1 pass; pre-fix 1/1 fail; reported location PlainNettyTransportFailedCloseTest.kt:107; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: PlainNettyTransportFailedCloseTest.[2] connection; current 1/1 pass; pre-fix 1/1 fail; reported location PlainNettyTransportFailedCloseTest.kt:107; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: PlainNettyTransportFailedCloseTest.[3] listener-throw; current 1/1 pass; pre-fix 1/1 fail; reported location PlainNettyTransportFailedCloseTest.kt:107; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: PlainNettyTransportFailedCloseTest.[4] listener-deferred; current 1/1 pass; pre-fix 1/1 fail; reported location PlainNettyTransportFailedCloseTest.kt:107; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: PlainNettyTransportFailedCloseTest.[5] caller-cancel; current 1/1 pass; pre-fix 1/1 fail; reported location PlainNettyTransportFailedCloseTest.kt:107; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: PlainNettyTransportFailedCloseTest.[6] caller-complete; current 1/1 pass; pre-fix 1/1 fail; reported location PlainNettyTransportFailedCloseTest.kt:107; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: PlainNettyTransportFailedCloseTest.[7] success; current 1/1 pass; pre-fix 1/1 pass; reported location none; expected success control; not a regression reproducer.

OBSERVED: QuicTransportClosedChildParentTest.closingAnOutboundChildDoesNotDiscardItsUdpParentsShutdownOwner(); current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportClosedChildParentTest.kt:63; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedCloseTest.[1] listener; current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedCloseTest.kt:96; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedCloseTest.[2] listener-throw; current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedCloseTest.kt:96; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedCloseTest.[3] listener-deferred; current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedCloseTest.kt:96; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedCloseTest.[4] caller-cancel; current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedCloseTest.kt:96; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedCloseTest.[5] caller-complete; current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedCloseTest.kt:96; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedCloseTest.[6] success; current 1/1 pass; pre-fix 1/1 pass; reported location none; expected success control; not a regression reproducer.

OBSERVED: QuicTransportFailedConnectionSetTest.oneOutboundCloseFailureStillClosesEveryConnectionAndUdpParent(); current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedConnectionSetTest.kt:108; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedDatagramCloseTest.[1] false; current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedDatagramCloseTest.kt:97; named public-behavior assertion; yes, reported body assertion contradicts the named behavior.

OBSERVED: QuicTransportFailedDatagramCloseTest.[2] true; current 1/1 pass; pre-fix 1/1 fail; reported location QuicTransportFailedDatagramCloseTest.kt:35; injected close exception reported at its creation origin; not named-body proof from this broad rollback.

OBSERVED: R93AConnectionHandlerDialReleaseTest.connectionHandlerClosedDialWaitsForItsUdpPortRelease(); current 1/1 pass; pre-fix 1/1 fail; reported location R93AConnectionHandlerDialReleaseTest.kt:55; fixture gate wait; not named-body proof from this broad rollback.

OBSERVED: R93ARejectedOpenParentDialTest.rejectedCloseLeavingParentOpenDoesNotWaitForSelectorRelease(); current 1/1 pass; pre-fix 1/1 fail; reported location R93ARejectedOpenParentDialTest.kt:59; cleanup assertion; earlier body failure may be hidden; not named-body proof from this broad rollback.

OBSERVED: R93ASuccessfulClosedDialReleaseTest.successfulClosedDialWaitsForItsUdpPortRelease(); current 1/1 pass; pre-fix 1/1 fail; reported location R93ASuccessfulClosedDialReleaseTest.kt:55; fixture gate wait; not named-body proof from this broad rollback.

OBSERVED: R93TQuicSuccessfulDialReleaseTest.successfulDialDoesNotReportBeforeItsUdpPortIsPhysicallyReleased(); current 1/1 pass; pre-fix 1/1 fail; reported location R93TQuicSuccessfulDialReleaseTest.kt:55; fixture gate wait; not named-body proof from this broad rollback.

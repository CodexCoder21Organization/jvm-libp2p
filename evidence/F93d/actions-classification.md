# Four Actions failures classified

Current-head run: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41955053537 on f6326b65a300814f96d871c1009f14f1f9e87e46.

- Introduced by the intended retained-shutdown-failure contract: ParentCloseOwnershipTest.failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest; ConnectionCloseRetryTest.concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges; ConnectionCloseRetryTest.transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult. These are unchanged default-branch tests. Their cleanup currently expects success; the PR contract requires completion with the original failure. Initially three owner questions; binding Addendum A1 approved the cleanup corrections and two additive assertions. Exact before/proposed-after and provenance are in existing-test-question.md.
- Introduced source bug: NoisePreparationLifecycleTest.stop reentered from readiness prevents empty listener startup. Exact existing test fails30/30 on f6326b65 and passes30/30 with develop436997d5 PlainNettyTransport/helper overlays; each invocation has one attempt and no retries. See noise-head-30-summary.json and noise-develop-30-summary.json.

Totals: introduced4, inherited0, unknown0; default-branch test questions0 after A1. These counts distinguish intended contract changes from the one unintended source defect; they do not call the three old test assertions correct.

Mechanism: PlainNettyTransport publishes its one shutdown owner, then attempts readiness.completeExceptionally before it installs cleanup. Even when readiness is already successful, CompletableFuture.completeExceptionally calls postComplete and drains more successful readiness callbacks. A nested readiness callback calls stop().get(), which waits on that same owner while the outer close has not yet scheduled cleanup. The candidate installs every cleanup stage before invoking readiness callbacks.

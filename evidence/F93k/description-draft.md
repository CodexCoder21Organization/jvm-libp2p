UrlProtocol's host shutdown must complete even when a full native event-loop queue rejects close work, as required by https://github.com/CodexCoder21Organization/UrlProtocol/pull/992. This PR supplies the transport cleanup and dial-completion ordering needed for that change: a logically closed UDP parent can still have its port bound until the selector releases it.

TCP and WebSocket terminate their owned acceptor groups after channel close settles, including a failed close. QUIC retains every owned outbound UDP parent through physical close and selector release, and terminates its owned worker group even when channel cleanup fails. The original close failure remains observable; caller completion or cancellation cannot cancel owned cleanup.

A normally returning connection callback that closes its connection keeps the successful dial result pending until owned parent cleanup finishes. A failed close request that leaves the parent physically open reports failure without waiting for a release that has not started. A live successful dial keeps its parent.

## F93k draft coverage settlement

Pending controlled first-attempt results; no draft is claimed proven by this draft description.

| Draft | Review finding and intended proof | Settlement |
| --- | --- | --- |
| F93jCallbackFailedParentReleaseTest | R93T F6 / R93A R1: normal callback closure with a failed parent-close result still waits for UDP release | Pending |
| F93jCallbackPendingChildReleaseTest | R93T F6 / R93A R1: a callback close request remains pending while physical child closure is held | Pending |
| F93jQuicReleaseReentryTest | R93T F1 G2 / R93A Gap2: B cleanup started from A's release callback belongs to the next selector batch | Pending |
| R93ARejectedOpenParentDialTest | R93T F3 G5: rejected close with a physically open parent does not await impossible release | Pending |
| F93jQuicSameLoopReleaseTest | R93T F1 G1 / R93A Gap1: two parents on one actual loop both await release | Pending |
| F93jQuicFailedDialResourceTest | R93T F5 G7 / R93A Gap4: native-resource and close-work guard during 128 failed dials on one live transport | Pending; no Java-history bound claimed |

Controls, exact results, source hashes and review findings: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F93k-evidence-20261114/evidence/F93k . Each accepted test must pass once on the product head and fail twice with its relevant product behavior removed, with no retries.

## Existing default-branch test edits and ruling

F93k changes no existing default-branch test. The following inherited changes are retained under the binding 2026-11-09 17:03 UTC ruling, which keeps main's default successful cleanup and permits retained-failure assertions only in the three approved scenarios against each scenario's own failure object. The two added identity assertions stay.

- `ParentCloseOwnershipTest.failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest`: before, cleanup required combined successful client/server stops; after, this approved failed-close scenario requires the client's exact original failure and a successful server stop in finally. The existing body remains intact and per-stop 10-second bounds remain.
- `ConnectionCloseRetryTest.transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult`: before, helper cleanup required combined successful stops; after, this approved scenario supplies its own cleanup that requires its exact original client failure and successful server stop. Its added transport-close assertion requires the same failure object; before, no transport-close identity assertion occupied that position.
- `ConnectionCloseRetryTest.concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges`: before, helper cleanup required combined successful stops; after, this approved scenario supplies its own exact-failure client cleanup and successful server stop. Its second retained identity assertion checks the original transport-close failure after explicit retry; before, no such assertion occupied that position.
- Shared `withRetryConnection` cleanup: the earlier PR-wide exceptional-readiness branch learned its expected failure from the current transport result and accepted unexpected closure in unrelated scenarios. That branch was removed by F93j. The default again requires combined successful client/server stops for the seven unrelated helper scenarios; only the two approved retry scenarios override it locally. Prior F93j evidence records 10/10 first-attempt passes and 1/1 intended failure after an unexpected public transport close was inserted.

Exact inherited before/after patch: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F93k-evidence-20261114/evidence/F93k/existing-test-edits.patch . No timeout increase, iteration reduction, disabled test or assertion removal is included.

## Current develop and verification

Rebased the 29 PR commits onto develop `e7c79098`, whose one new commit since `3b2b6640` changes Yamux replacement-write accounting. It touches none of the four PR production paths and contains no equivalent UDP release or owned-group fix. Other sessions advanced the pinned snapshot to 49; F93k publishes nothing.

Touched-class single-attempt and final-head Actions counts pending. Informational remote build checks are excluded under this campaign's rule; no remote suite was submitted and no check was re-requested. An earlier TCP connection-setup failure at head `68c436af` remains unclassified; newer green UDP results do not establish its mechanism or show it fixed.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

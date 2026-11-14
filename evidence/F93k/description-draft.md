UrlProtocol's host shutdown must complete even when a full native event-loop queue rejects close work, as required by https://github.com/CodexCoder21Organization/UrlProtocol/pull/992. This PR supplies the transport cleanup and dial-completion ordering needed for that change: a logically closed UDP parent can still have its port bound until the selector releases it.

TCP and WebSocket terminate their owned acceptor groups after channel close settles, including a failed close. QUIC retains every owned outbound UDP parent through physical close and selector release, and terminates its owned worker group even when channel cleanup fails. The original close failure remains observable; caller completion or cancellation cannot cancel owned cleanup.

A normally returning connection callback that closes its connection keeps the successful dial result pending until owned parent cleanup finishes. A failed close request that leaves the parent physically open reports failure without waiting for a release that has not started. A live successful dial keeps its parent.

## F93k draft coverage settlement

Four drafts are added, each in its own commit after single-attempt red/green/reverted-red proof. Two drafts are left out after one honest correction exposed an unrelated incorrect test assumption. No new product defect was established; all production source changes predate F93k.

| Draft | Review finding and intended proof | Settlement |
| --- | --- | --- |
| F93jCallbackFailedParentReleaseTest | R93T F6 / R93A R1: normal callback closure with a failed parent-close result still waits for UDP release | Added; 1 intended red at61, 1 green, 1 reverted-red at61 |
| F93jCallbackPendingChildReleaseTest | R93T F6 / R93A R1: a callback close request remains pending while physical child closure is held | Added; 1 intended red at53, 1 green, 1 reverted-red at53 |
| F93jQuicReleaseReentryTest | R93T F1 G2 / R93A Gap2: B cleanup started from A's release callback belongs to the next selector batch | Left out; unsupported callback error-wrapper identity assertion after one publication-gate correction |
| R93ARejectedOpenParentDialTest | R93T F3 G5: rejected close with a physically open parent does not await impossible release | Added; 1 intended timeout red at48, 1 green, 1 reverted-red at48 |
| F93jQuicSameLoopReleaseTest | R93T F1 G1 / R93A Gap1: two parents on one actual loop both await release | Added; 1 intended pending-result red at97, 1 green, 1 reverted-red at97 |
| F93jQuicFailedDialResourceTest | R93T F5 G7 / R93A Gap4: native-resource and close-work guard during 128 failed dials on one live transport | Left out; the successful probe's success handler always throws, even after one held-loop correction |

All accepted cases ran once per source variant, with no retries/skips and matching test-source hashes. Callback controls remove only the successful callback cleanup join. G1 removes selector release waiting; G5 removes the physically-open failed-close guard. Formatting affected only the new G1/G5 draft source. The README note explains the callback and open-parent completion contract.

G5's first control completed its intended assertion failure and wrote complete XML, but the outer slot wrapper received TERM during the seven-slot launcher transition and returned143 afterward. The test JVM completed; that is the single first-red attempt and was not repeated. The green and reverted-red wrappers returned their normal0/1 statuses.

G2's original control failed on its raw callback identity assertion. The one corrected version forces observation at B publication: the control fails its port rebind, while the head passes both port checks and then fails the unrelated identity assertion. Java permits dependent callback failures to be wrapped in CompletionException while get() exposes the original cause. The test would need to assert that cause identity and repeat fresh red/green/red; changing transport behavior to satisfy raw wrapper identity would impose an unsupported contract. See https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F93k-evidence-20261114/evidence/F93k/g2-exclusion.md and the [JDK contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html).

G7's original control fails its deregistration observation. After the one held-loop correction, the control fails the pending-dial check, and the head passes that ordering before its successful probe invokes the test's always-failing normal connection handler. It needs a normally returning success handler and fresh proof with all128cycles/failure/resource assertions retained. This is not evidence of a transport probe failure; the descriptor guard also establishes no bound on retained Java failure history. See https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F93k-evidence-20261114/evidence/F93k/g7-exclusion.md .

Controls, exact XML, source hashes, both excluded drafts and correction patches: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F93k-evidence-20261114/evidence/F93k . No second correction was applied to either excluded draft.

## Existing default-branch test edits and ruling

F93k changes no existing default-branch test. The following inherited changes are retained under the binding 2026-11-09 17:03 UTC ruling, which keeps main's default successful cleanup and permits retained-failure assertions only in the three approved scenarios against each scenario's own failure object. The two added identity assertions stay.

- `ParentCloseOwnershipTest.failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest`: before, cleanup required combined successful client/server stops; after, this approved failed-close scenario requires the client's exact original failure and a successful server stop in finally. The existing body remains intact and per-stop 10-second bounds remain.
- `ConnectionCloseRetryTest.transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult`: before, helper cleanup required combined successful stops; after, this approved scenario supplies its own cleanup that requires its exact original client failure and successful server stop. Its added transport-close assertion requires the same failure object; before, no transport-close identity assertion occupied that position.
- `ConnectionCloseRetryTest.concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges`: before, helper cleanup required combined successful stops; after, this approved scenario supplies its own exact-failure client cleanup and successful server stop. Its second retained identity assertion checks the original transport-close failure after explicit retry; before, no such assertion occupied that position.
- Shared `withRetryConnection` cleanup: the earlier PR-wide exceptional-readiness branch learned its expected failure from the current transport result and accepted unexpected closure in unrelated scenarios. That branch was removed by F93j. The default again requires combined successful client/server stops for the seven unrelated helper scenarios; only the two approved retry scenarios override it locally. Prior F93j evidence records 10/10 first-attempt passes and 1/1 intended failure after an unexpected public transport close was inserted.

Exact inherited before/after patch: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F93k-evidence-20261114/evidence/F93k/existing-test-edits.patch . No timeout increase, iteration reduction, disabled test or assertion removal is included.

## Current develop and verification

Rebased the 29 PR commits onto develop [e7c79098](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/e7c79098db289f6a08c8dd467f4bedeb4fd7d168), whose one new commit since [3b2b6640](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/3b2b66400d7a9c6fa0c3cdc420565368f726be1c) changes Yamux replacement-write accounting. It touches none of the four PR production paths and contains no equivalent UDP release or owned-group fix. A fresh fetch before the final gate found zero further develop commits. Other sessions advanced the pinned snapshot to49; F93k publishes nothing.

Final [head 34a2e78b](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/34a2e78bb82637e2c970a0be40fe4244c76cb547): all 35 touched classes passed 60/60 first-attempt cases in one Gradle run, with 0 failures, 0 skips and 0 retry entries. Gradle used --no-daemon, one build slot, two test slots, max-workers=1 and one test fork; no JVM tuning flags were added. Compilation, formatting and static analysis passed.

Completed final-head [native Actions](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/43563295328) passed 1713/1715 first-attempt cases: 0 failures, 2 existing skips, retries []. All 293 XML files from artifact 14101986723 were inspected, including 60/60 touched cases. The skips are EchoSampleTest.connect1 and QuicServerTestJava.checkConnectionIsNotClosedByTimeout. Its run attempt is 1; workflow/Gradle retry declarations and XML retry entries are both 0. The separate [parallel native workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/43563295235) remained in progress on attempt 1 at 06:07 UTC; the other three final-head workflows were successful on attempt 1. Parallel workflow executions are reported separately from retries.

The rebased baseline before the four additions passed1709/1711 first attempts, with2existing skips,0failures/retries and56/56touched cases, in [native Actions](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/43559075231). These are baseline counts, not the final-head verdict. Informational remote checks are excluded under this campaign's rule; no remote suite was submitted and no check was re-requested. An earlier TCP connection-setup failure at [head68c436af](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/68c436af) remains unclassified; newer green UDP results do not establish its mechanism or show it fixed.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Host shutdown could hang or fail when a native event loop rejected close work. This PR is the upstream half of https://github.com/CodexCoder21Organization/UrlProtocol/pull/992; F93d was asked to "finish the coverage gaps, run the gate, publish the corrected snapshot" after classifying the four Actions failures.

The original three shutdown findings are fixed in the branch. F93d also schedules TCP/WebSocket cleanup before readiness callbacks can reenter shutdown, preserving the first shutdown result without the Noise readiness wait cycle. The existing Noise case failed30/30 on f6326b65 and passed30/30 on develop436997d5; the correction passes its unchanged child body and the later Actions run.

Current product head: [0363c80e](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/0363c80e44b62bb86782142ea01ab8083c292d25). No functional QUIC churn candidate from F93d has been adopted: its evidence-only v2 passes41/41 targeted first attempts but review still identifies an untested late-child registration/cleanup path and incomplete soak evidence. The full-queue child probe fails0/1 on snapshot45 and passes1/1 on current code; its permanent fixture adoption is unfinished. The30x gate has not started. No new snapshot has been published.

Existing-test edits approved by Addendum A1:

| Test | Before | After |
|---|---|---|
| `ParentCloseOwnershipTest.failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest` | Cleanup `allOf(client.stop(), server.stop()).get(10, SECONDS)` required success. | Cleanup expects `client.stop()` to fail, unwraps and `assertSame(failure, reported)`, then separately awaits successful server stop with the same10-second bound. |
| `ConnectionCloseRetryTest.concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges` | Same implicit-success cleanup after a deliberately failed transport shutdown and successful physical retry. | Closed-transport cleanup asserts the exact retained failure already checked by the body; server stop remains successful. Adds `assertSame(failure, retryCloseFailure(connection.transport().close()))` after successful retry. |
| `ConnectionCloseRetryTest.transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult` | Same implicit-success cleanup. | Same retained-failure identity cleanup and the approved additive post-success assertion. |

- Parent-close case: contract preserved: ownership of a failed parent close; cleanup now asserts the retained failure the README documents instead of expecting success.
- Concurrent-retry case: contract preserved: explicit retry after a failed close without changing the ordinary result; cleanup now asserts the retained failure the README documents instead of expecting success.
- Transport-owned case: contract preserved: explicit retry after a failed close without changing the ordinary result; cleanup now asserts the retained failure the README documents instead of expecting success.

All original scenario lambda bodies, iterations and body assertions remain, apart from those two approved additions. The shared fixture reads already-settled public readiness only within cleanup to distinguish a prior shutdown; unaffected callers retain the original concurrent `allOf(...).get(10, SECONDS)` check. The full affected classes pass12/12 first attempts; with the fix removed, exactly the three approved cases fail (9/12 passes), confirming the new checks detect the retained-result defect.

Actions evidence:

- f6326b65:1586/1592 passes,4 failures,2 pre-existing skips,retries[]. All four classified introduced (three intended retained-failure expectations plus the Noise source bug); inherited0,unknown0,open default-branch questions0 after A1.
- [3caf5260 Actions run](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41959991939):1590/1592 first-attempt passes,0 failures,2 existing skips,retries[]. The job failed only `spotlessKotlinCheck` on one QUIC builder closing-parenthesis indentation;0363c80e corrects that exact line. [Final-head Actions](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41962734931) on0363c80e is success:1590/1592 first-attempt passes,0 failures,2 existing skips,retries[]; final XML is preserved in F93d evidence.

Snapshot45 was published as `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-45`, SHA-256 `a2c9e25b16c69d83bdd813648c447d74bf549ec9a5eaad83cdb51c53a3b19a2c`, with [byte/POM verification evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/ebcb5f2d737554c506bab6c070ba33a4c6c09b0e/evidence/FLIBP8). It has not been overwritten. Snapshot46 jar/POM were404 at preflight; that is not a reservation or a publication.

[F93d evidence, exact XML and candidate patch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F93d-evidence-20261105/evidence/F93d). No merge, enqueue, PR close, deployment, check rerequest or handoff command was performed.


### F93f candidate checkpoint

The owned UDP-parent candidate is on product head `460504238f9f66d3f8aab3400adb8c5e45d404be`. With only its implementation removed and all three new regressions kept, the single-attempt control had 3 attempts: 1 pass and 2 assertion failures (stopped handshake error kind, and a UDP parent closed before its pending child-close result). The restored implementation passed 3/3; removing it again reproduced the same 2 failures with the handoff case passing as a control. No retries occurred.

[F93f evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F93f-evidence-20261105/evidence/F93f) includes the per-attempt XML and independent whole-file review. The late-child close-result gap, full-queue permanent fixture, soak, per-test 30x and full repetition gate remain unfinished at this checkpoint. Snapshot-46 has not been published.

Candidate test correction: the PR-added F93cQuicRejectedLoopTest now requires ordinary child close to release its UDP parent before native group shutdown, then requires successful repeated shutdown because no later parent close dispatch occurs. Actual rejected-dispatch physical cleanup needs its separate full-queue regression. No default-branch test was edited by F93f; earlier owner-approved default-branch cleanup corrections remain listed above.

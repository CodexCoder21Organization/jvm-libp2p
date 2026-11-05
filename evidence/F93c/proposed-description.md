[UrlProtocol PR](https://github.com/CodexCoder21Organization/UrlProtocol/pull/992) needs host shutdown to complete when a full native event-loop queue rejects close work. Its review found that a failed listener close skipped upstream acceptor-group cleanup; expanded coverage then found a second gap: an outbound QUIC UDP parent remained open and registered after its own event loop terminated.

The user asked F93c to “close the UDP-parent gap inside jvm-libp2p, finish the coverage gaps and the gate, publish the corrected snapshot.” This resumes [F93b](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/70b876d0a024ab8204002633671cf9ab9c646262/evidence/F93b) against [R93a's review ledger](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/996be90fb23040717da7974bbec39aa5443d9b98/evidence/R93a/review.md). Work remains unfinished; no corrected snapshot is published.

Each transport owns one shutdown completion, preserves its original result across repeated calls, and collects distinct channel/parent cleanup failures. F93c repairs the rejected UDP-parent close by performing native closure in the already-installed shutdown hook while that hook still owns the loop; the original rejection stays reported, and native cleanup outcomes are included. No Netty change or artifact is needed for that parent path. On a terminated loop the contract is physical closure, a completed physical close future, and preserved failure; native deregistration is not required.

The dial visitor can stop the transport. The transport now checks closure before publishing the connection after that visitor, and atomically checks closure when registering children. A queued-bind rejection check and further coverage remain in progress.

Current pushed head: [702b3800a552da5da20abd312794ae1c2e2e0bb0](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/702b3800a552da5da20abd312794ae1c2e2e0bb0). Default `develop` remains [436997d5e5f5b80f1503504b19e5b813654a3a49](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/436997d5e5f5b80f1503504b19e5b813654a3a49), with no equivalent shutdown changes.

Measured validation so far:

- Native rejected-parent case: red0/1, green1/1, removed-fix red0/1, followed by another post-rebase green1/1; one attempt per execution, no retries. The child was ordinarily closed first; the live-child neighbor is being audited separately, so no every-path claim is made from this case.
- In-flight dial visitor: red0/1, green1/1; one attempt each.
- Queued bind during shutdown: red0/1 on the expected public error contract; correction in progress.
- Long-lived dial churn: red0/1 because a completed connection retains its UDP parent until transport shutdown; not fixed.
- F93b's inherited register:27/27 actual first-attempt passes at b7eecc3a, not a final-head register. Repeated shutdown red0/4 green4/4 removed-fix red0/4; secondary cleanup reporting red1/2 green2/2 removed-fix red1/2.
- Larger multi-child/parent failure and TCP/WS inventory cases are drafts in evidence, not completed gates. Full reviews, the30x gate and final-head Actions XML are unfinished. No test retries or check rerequests were used.

Actions on55fc5fe7 stopped at test compilation because F93bQuicTerminatedLoopTest stored an unused `reportedFailure` while Gradle treats warnings as errors. A dedicated commit strengthens that PR-added test with `assertNull` on the graceful shutdown result. Existing default-branch test edits: none. Existing PR-added test edits: that assertion/import addition only so far; no assertion weakened.

Already published: `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-45`, from [e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe). Local/downloaded JAR SHA-256 `a2c9e25b16c69d83bdd813648c447d74bf549ec9a5eaad83cdb51c53a3b19a2c`; all1107 entries and POM bytes verified in [FLIBP8 publication evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/ebcb5f2d737554c506bab6c070ba33a4c6c09b0e/evidence/FLIBP8). Snapshot45 is untouched. Snapshot46 JAR/POM initially returned404; it is not reserved or published.

[F93c evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F93c-evidence-20261105/evidence/F93c) contains the plan, exact summaries, native XML, source controls and pending public reproducers. No merge, enqueue, close, deployment or handoff command was performed.

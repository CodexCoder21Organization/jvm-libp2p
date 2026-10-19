UrlProtocol's unchanged 1,000 ms dial-budget test fails on main CI and blocks https://github.com/CodexCoder21Organization/UrlProtocol/pull/725. This version records the publication of the loop-local Noise scheduling change merged in https://github.com/CodexCoder21Organization/jvm-libp2p/pull/53, so the consumer can qualify N1 independently of the later scheduling rules.

Records `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-33` on kotlin.directory. The artifact is built from develop merge ecbc4a354c323f366150165842d6a039dd105128 with only this version and release-coordinate update; no implementation or test behavior changes.

Validation: publication build passed; exact Coursier fetch and local/published SHA-256 comparison are retained in the evidence branch below. The inherited merged-head suite has 1,104 native attempts: 1,100 passed, 2 failed, 2 skipped. `QuicKuboTestJava.pingKubo` requires an absent local Kubo daemon and fails before/after N1. `GossipTwoHostTest` “message larger than mplex frame” timed out in that suite; the owner's required fresh-JVM rate control passed 10/10 first attempts at each head, classifying it as a pre-existing fork flake under the stated rule. Both failures and stacks remain recorded; neither is claimed fixed. The N1 change has separate 92/92 first-attempt Noise-and-neighbour evidence. Fork Actions do not start under the existing external-action policy; no CI success is claimed.

Evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/flake-urlprotocol-dial8-evidence and inherited controls https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/flake-urlprotocol-dial7-evidence .

Existing-test edit ledger: empty; no test changed in this PR. UrlProtocol qualification and pin are separate; publication is not a claim that N1 meets the original budget.

Follow-ups (separate PRs): N2 bounded workers, N3 static-key preparation, and N4′ shared-dial last-departure cancellation; the two named fork-suite items remain separate work.

Consumer qualification: snapshot-33 **did not meet the pin gate**. The unchanged original test and all three cold probes failed first attempt in https://buildtest.kotlin.build/run?id=e7d9b856 (0/4, baseline 0/4 at https://buildtest.kotlin.build/run?id=f280853e). The sixteen-client diagnostic copies failed 10/10 first attempts in https://buildtest.kotlin.build/run?id=773c8558 (0/10 passes, baseline 0/10 at https://buildtest.kotlin.build/run?id=b95861e5). Connection-ready was observed at 905–988 ms with no stream-ready before the unchanged 1,000 ms budget. N1 alone is insufficient; no consumer pin PR was opened. Full stacks, phase evidence and explicit ordinal-1 attempt rows are on the evidence branch.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

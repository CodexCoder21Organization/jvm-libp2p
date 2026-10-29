# F992b final checkpoint — partial, blocked

The request was to fix the fresh review of https://github.com/CodexCoder21Organization/UrlProtocol/pull/992 and close https://github.com/CodexCoder21Organization/jvm-libp2p/pull/74 with the prescribed explanation.

Product branch `flibp4-native-shutdown-backport` is pushed and verified local=remote at `314e77bf5895352f4e7f1235bebaa069ec373efc`. Main remains `18bdb17e031aaee0614233ccc3ca71238de29e66`; the rebase was a no-op and R992's snapshot-41 overlap analysis remains current. Evidence is on `wip/F992b-evidence-20261029`; its final SHA is verified after the last push and supplied in the final response.

Listener registration retains an owned copy of the bind list and each component's bytes. Dial input is copied before admission, and native initiation/registration plus preparation continuations share the shutdown boundary. The new late-handshake test catches a second physical close after failed native stop.

Findings 1 and 2 are fixed; finding 3's coverage gap is fixed. Finding 4 is fixed through the specified gh api PATCH, with exact body readback. Finding 5 is confirmed and unfixed: the actual snapshot-41 transport skips its private acceptor-loop shutdown on channel-close failure, and this job forbids publishing the upstream artifact needed by the consumer. The failing probe and complete results are preserved; the description explicitly says the PR is not ready to merge. No upstream repair or artifact publication was made.

All 18 scenario names ran exactly 30 times, one attempt each: 510/540 passed. The 17 actionable scenarios passed 510/510; native acceptor cleanup failed 30/30 at the intended assertion. Six of six mutations were caught by intended behavior failures without compilation failures. The final post-rebase gate passed 17/17, 17 attempts, no retries. No existing main tests or existing 13 PR tests were edited. The added byte test's initial compile error was corrected, its invalid early results excluded, and its proper baseline failure/fixed pass retained.

At the last Actions assessment (10:29 UTC), https://github.com/CodexCoder21Organization/UrlProtocol/actions/runs/40856006182 is green on workflow attempt 1. https://github.com/CodexCoder21Organization/UrlProtocol/actions/runs/40856005977 is still running all four test shards on workflow attempt 1, with zero XML artifacts uploaded. Test first-attempt counts and retries remain unverified; the old head's green suite was not reused. Binding M8 makes Actions the full-suite gate, so no full local or manual remote suite was run.

The legacy PR is CLOSED with the required artifact links and unused-because-snapshot-41 wording: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/74#issuecomment-6493023443. No other PR was closed. No merge, enqueue, deploy, publish, check rerequest or handoff-cli command occurred.

All tracked local commands and read-only once assessments completed. Full process inspection found no owned process, JVM slot or remaining background wait loop; raw process lines were deleted locally and never committed. Product and mutation worktrees are clean. No delegated work is active. The remaining work is upstream native cleanup and a consumable artifact, then downstream verification and the new-head Actions XML/retry classifications.

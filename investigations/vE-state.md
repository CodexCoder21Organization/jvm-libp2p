# vE final checkpoint

## STATE

OBSERVED: Time-bounded continuation stopped before all requested validation completed. This is not DONE. The artifact and source work are durable; uncompleted validation below is required before relying on the new proofs.

- Done: published `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-27`; generated JAR and full-dependency POM both served HTTP 200 with matching local/served hashes (see vE-release.md).
- Done: resolver build, README, 1,446 direct test pins and packaged-JAR expectations adopt snapshot-27.
- Done: three new public-API proofs fail first on snapshot-26 at physical-close counts 4, 2, and 4, with expected count 1. Their preceding overlap/unpublished-parent/previous-release-peer assertions passed. Full stacks are committed in the resolver investigations directory.
- Refuted: the six O6 source guards were missing. All six named tests already existed; they were retained unchanged except artifact selection. Their new-artifact executions remain required below.
- Done: upstream exact-head testClasses compilation passed (6m24s). This alone is not a native test result.
- Fixed-artifact passing gates: `testNativeNetworkShutdownClosesParentOnce` (1/1), `testRelayReplacementAdmitsReversePeerExchange` (1/1), `testWithdrawalStaleMuxKeepsAcceptedRpc` (1/1), `testRegistrationResyncEscapesStaleMuxAfterAnnouncement` (1/1).
- Upstream native exact-head gate: PASS (see local result evidence).

INFER: shared slot waits plus fresh dependency compilation consumed the bounded run window. There is no evidence supporting a fixed-artifact pass for any uncompleted selector. No source fix was guessed from a timeout or queue wait.

## Remaining commands

Use fresh checkouts from the branches below. Run one selector at a time through the supplied local wrapper. Never rerun already recorded passing gates merely for repetition. Fetch/rebase on origin/main (resolver) or origin/develop (upstream) before builds. The old automatic runner has a lane-specific clock cutoff and must not be reused unchanged.

```bash
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testWithdrawalRecoversFromStaleOpenMux
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testRecoverySecureSessionFailureIsObservable
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testConcurrentRecoveryDialSharesOneParent
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testShutdownClosesUnpublishedRecoveryDialOnce
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testRecoveryWithPreviousReleasePeer
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test stressTestWithdrawalLossDuringInitialBootstrapHandshake
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test stressTestBootstrapServiceResyncDoesNotGiveUpUnderSaturation
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testReRegistrationDuringWithdrawalResyncWindowStaysLive
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testHealthyConfiguredBootstrapRecoveryRemainsPrompt
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testWithdrawalRetriedAfterPendingDialStall
/tmp/claude-1000/-code/cc161e27-cb11-48c5-89b5-f0960e498312/scratchpad/util/tb.sh <absolute-UrlResolver-checkout> --test testRegistrationRecoversFromStaleOpenMux
```

After these results, rebase, confirm both PRs OPEN, push with --force-with-lease, PATCH both bodies via gh api and re-read them. Do not merge, enqueue or deploy.

## Durable branches

- https://github.com/CodexCoder21Organization/UrlResolver/tree/wip/sO-rpc-connection-closure
- https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/uO-single-parent-close
- https://github.com/CodexCoder21Organization/UrlResolver/pull/1080
- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/39

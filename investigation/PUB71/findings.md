# PUB71 progress

Plan
- In progress: confirm upstream merge and reserve a free Maven version by checking remote files.
- Pending: build merged source, publish once, compare jar and POM SHA-256 values.
- Pending: apply the existing consumer pin work, check clean retrieval, run ten scenario repetitions and targeted tests.
- Pending: run the complete suite race, update the open consumer PR, and watch required checks.
- Pending: final correctness review, preserve findings remotely, and stop all owned tasks.

OBSERVED: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71 merged at 2026-10-26T20:00:39Z with commit 25efb1996bd79e59e6eef28d2d44d9a0405fdd78.
OBSERVED: https://github.com/CodexCoder21Organization/UrlProtocol/pull/945 remains open.
INFER: The objective explicitly authorizes this Maven publication; the generic publication prohibition applies to other artifacts. The objective explicitly requests checking required CI on this existing PR.

The mechanism is a full callback queue losing a completed future notification, and the evidence that demonstrates it is the prior lane's snapshot-25 result of 10/10 unsuccessful runs versus local snapshot-41 result of 10/10 successful runs. This inherited evidence will be checked against the linked findings and merged source before publication.

## Preparation checkpoint
OBSERVED: snapshot-41 jar and POM both return HTTP 404. The fresh upstream checkout uses develop as its default branch; origin/main does not exist. Fetch and rebase against origin/develop retained the exact merged commit 25efb1996bd79e59e6eef28d2d44d9a0405fdd78. The source declares snapshot-41.
OBSERVED: read both README files where available, upstream FORK.md, build configuration, the linked MQ945 findings, and Testing Architecture sections Test-Driven Development and Never Calibrate a Reproducer to a Machine. The consumer clone is still receiving data, so its README will be read when checkout completes.
OBSERVED: the prior lane measured the deterministic after-queue-drain scenario red on attempt 1 in 10/10 snapshot-25 runs, green on attempt 1 in 10/10 local snapshot-41 runs, then red again after returning to snapshot-25. The original consumer cleanup body passed attempt 1 with local snapshot-41 and no added wait. This lane will retain the original consumer assertions.
INFER: publication can proceed with snapshot-41 after a successful exact-source build. No new source fix is proposed.

## Build milestone
OBSERVED: Gradle jar and POM generation both completed with exit 0 from merged source; no implementation or build configuration was changed. Staged POM coordinates were parsed and checked. Immediately before upload the POM still returned 404.
Built jar SHA-256: 2482ff751bd14b929b3e6b9ad6161ce7a645eaff1c1bad7d43423a731edbfe3d.
Built POM SHA-256: 144f73fdee0cfe6a2269c758976d6a642e588f4e619e7f602e3438bfb23cf48e.
OBSERVED: publisher metadata confirms documented CLI version 0.0.6 is latest. The upload is now running using the user-authorized publication objective.
Durable checkpoint: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/PUB71-publication-evidence .

## Publication milestone
OBSERVED: publisher exited 0. Snapshot-41 jar and POM downloaded from kotlin.directory both match the built SHA-256 values. No existing version was replaced.
.jar downloaded SHA-256 2482ff751bd14b929b3e6b9ad6161ce7a645eaff1c1bad7d43423a731edbfe3d
.pom downloaded SHA-256 144f73fdee0cfe6a2269c758976d6a642e588f4e619e7f602e3438bfb23cf48e
OBSERVED: the consumer full-history transfer was still receiving hundreds of megabytes before checkout; a second fetch was started before checkout completed. Both owned transfer tasks were stopped; a new depth-one clone will fetch only the required branches. This is a local preparation correction.
Plan: publication complete; consumer verification in progress.

OBSERVED at 2026-10-26 20:07 UTC: all times above are milestone labels; the upstream merged timestamp is exact. Upstream evidence is now committed and pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/4561bf404186188428c20df83e3b6ddcb650b713 . Clean Coursier cache retrieval exited 0 and its jar SHA-256 matches the built and downloaded jar. The consumer README and scripts have now been read.

## Consumer scope
OBSERVED: the supplied pin branch changes 2695 existing Kotlin scripts only by version text, adds one completed-close scenario, and changes one existing cleanup body. It also contains investigation records from earlier work. The existing PR changes one independent logging scenario.
INFER: the required pin consists of the version-only selections plus the added completed-close scenario. The additional cleanup wait is unnecessary because the upstream result handoff fix owns the behavior and the original consumer body already passed in the prior lane. This lane will not copy that wait or unrelated investigation records.
OBSERVED: the consumer branch was rebased onto current main after configuring its local Git identity. A continuation from the initial identity pause had incomplete local rebase metadata; restarting that local rebase completed normally. No remote PR branch has been updated yet.
Test edit inventory: a new scenario from the supplied pin branch is initially selected against snapshot-25 to verify the same completed-root result condition before selecting snapshot-41. Existing test bodies remain unchanged; all existing pin edits will be version text only.

## First local baseline milestone
OBSERVED: the new completed-close scenario fails on attempts 1, 2, and 3 under snapshot-25. Each recorded stack trace names the exact assertion: A completed physical close must remain observable after its event-loop queue has drained. Source promise completion and full queue are asserted first; accepted tasks and an executor barrier finish before this assertion. This is a result-delivery check after queue recovery.

## 2026-10-26 20:12 UTC consumer pin milestone
OBSERVED: baseline exact assertion confirmed in all three attempts. Checkpoint: https://github.com/CodexCoder21Organization/UrlProtocol/tree/wip/PUB71-root-baseline . The consumer proposal now selects snapshot-41 in 2697 files, comprising the supplied branch's existing selections and its new completed-close scenario. The original cleanup body was compared after normalizing only the libp2p coordinate and is identical. README now names the published version and links its upstream change.
Existing-test edit inventory: each selected existing test changes only its libp2p artifact annotation from its prior snapshot to snapshot-41. No assertion, wait, bound, iteration count, or test body changed. The original PR's existing logging adjustment remains as rebased. New-test inventory: the completed-close scenario is copied from F71-consumer-pin unchanged apart from the temporary baseline selection already measured.
Plan: baseline complete, published comparison running, ten original-consumer repetitions next, then targeted neighbors and the mandatory complete-suite race.

OBSERVED 2026-10-26T20:13:00.089723+00:00: published snapshot-41 completed-close scenario passes on attempt 1; [{'success': 'true', 'attempts': 1, 'time': '2063'}]. Existing scripts checked individually: all 2696 tracked Kotlin changes are libp2p coordinate text only.

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

## 20:06 UTC checkpoint
OBSERVED: snapshot-41 jar and POM both return HTTP 404. The fresh upstream checkout uses develop as its default branch; origin/main does not exist. Fetch and rebase against origin/develop retained the exact merged commit 25efb1996bd79e59e6eef28d2d44d9a0405fdd78. The source declares snapshot-41.
OBSERVED: read both README files where available, upstream FORK.md, build configuration, the linked MQ945 findings, and Testing Architecture sections Test-Driven Development and Never Calibrate a Reproducer to a Machine. The consumer clone is still receiving data, so its README will be read when checkout completes.
OBSERVED: the prior lane measured the deterministic after-queue-drain scenario red on attempt 1 in 10/10 snapshot-25 runs, green on attempt 1 in 10/10 local snapshot-41 runs, then red again after returning to snapshot-25. The original consumer cleanup body passed attempt 1 with local snapshot-41 and no added wait. This lane will retain the original consumer assertions.
INFER: publication can proceed with snapshot-41 after a successful exact-source build. No new source fix is proposed.

## 20:10 UTC build milestone
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

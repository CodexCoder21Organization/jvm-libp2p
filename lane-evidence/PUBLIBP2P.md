# PUBLIBP2P findings

Started 2026-10-22 09:43 UTC. Deadline 11:43 UTC.

## Scope
Publish jvm-libp2p built from exactly e15e0f1947842be7ef6836c116c14ba47cc8eea1, containing the merged Yamux changes. Only repository writes allowed: evidence branch wip/publibp2p-*. No consumer PRs, merges, service changes, or remote builds.

## Plan
- [done] Locate fork publishing history, current consumer coordinate, and required documentation.
- [in progress] Confirm an unused version and build the exact requested source commit locally.
- [pending] Publish with the Maven publishing CLI; download JAR and POM, compare SHA-256, and inspect merged bytecode.
- [pending] Push final evidence, report coordinate and consumer edit, and confirm all started background work has ended.

## Observations
- OBSERVED 09:43 UTC: Read github-repos and publish-maven-artifact skills. Explicit lane publishing authorization covers the skill confirmation step. Pre-built Gradle artifacts can be uploaded with --artifact-dir and HTTP API.
- OBSERVED: The explicit exact-commit requirement takes precedence over global rebase-before-build instructions. No production deployment is authorized.

- OBSERVED 09:46 UTC: Fresh clone HEAD is exactly the requested commit e15e0f1947842be7ef6836c116c14ba47cc8eea1. This is a Gradle repository, with no scripts/build.bash or scripts/test.bash. FORK.md documents Gradle Maven publication and staging only JAR/POM for the HTTP publishing CLI. The root version is snapshot-37. UrlProtocol currently pins snapshot-25. Tags contain no fork release tags.
- OBSERVED: kotlin.directory already serves a POM for snapshot-37, so that version cannot be reused. Checking the version listing for the next unused version. An external Gradle init script can supply release metadata without changing the source checkout.
- OBSERVED: Read repository README.md, FORK.md, CLAUDE.md, root/libp2p Gradle configuration, settings.gradle, and gradle.properties. Local project instructions recommend MockK, but lane instructions prohibit mocks and take precedence. No tests or implementation changes are planned.

- OBSERVED 09:47 UTC: Both snapshot-38 JAR and POM return HTTP 404, and the repository version listing ends at snapshot-37. Selected snapshot-38. FORK.md says an absent version returns 401; live endpoint returns 404 instead. Use actual HTTP status plus listing, not the stale status comment. First evidence commit is pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/948d84a0 .
- OBSERVED: Starting a local Gradle artifact build under the machine-wide suite lock with one Gradle worker, no added JVM tuning, and a 45-minute hard deadline. This does not run the full suite. memory.current before start: 10737184768 bytes; memory.max: 10737418240 bytes.

- OBSERVED 09:48 UTC: Artifact build is waiting for the machine-wide lock; no Gradle process has started for this lane. memory.current is 10165358592 bytes; memory.stat reports about 8.3 GB anonymous memory. Continuing metadata and bytecode checks while compilation waits.
- OBSERVED: GitHub reports both requested changes merged. https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63 merged as b01e08e5e40daecd68a529bc4c07a03999397657; https://github.com/CodexCoder21Organization/jvm-libp2p/pull/64 merged as the exact requested e15e0f1947842be7ef6836c116c14ba47cc8eea1.

- OBSERVED 09:53 UTC: Build still waiting for lock held by another lane full-suite run. memory.current=8134569984 bytes. No lane compilation has begun.
- OBSERVED: Downloaded existing snapshot-37 JAR; SHA-256 196307da543f4b93ace04dab45539556b0801c0038f25e3f606827317d5ba55e. javap shows preparePendingChildRead(ByteBuf) and no coalescePendingChildRead, confirming snapshot-37 lacks the requested merged implementation.
- OBSERVED: Prior releases used the same Gradle jar and generatePomFileForMavenJavaPublication tasks in https://github.com/CodexCoder21Organization/jvm-libp2p/commit/7f0ab46b9a82bd21a08a2aca0bd72058f5f767af . Publisher 0.0.6 with exact read-back checksums is recorded in https://github.com/CodexCoder21Organization/jvm-libp2p/commit/6af87f01a01ca127a4bfaded7da183ddc78884ca .
- OBSERVED: Source tree hash is 73b8c674c3f29fe41b189ba9a48d57bfe05dc786; source checkout is clean. The earlier merged commit b01e08e5e40daecd68a529bc4c07a03999397657 is an ancestor of the release commit.
- OBSERVED: Read the linked PHILOSOPHY.md and TESTING.md guidance, including Extreme-Condition Testing, Never Calibrate a Reproducer to a Machine, and one scenario per test. The linked document says remote kotlin.build is required; the lane explicitly marks it informational and forbids remote runs, so the lane instructions control. No tests are being added or changed in this publishing-only lane.

- OBSERVED 09:57 UTC: Build is still queued on the common lock, with an empty build log and no Gradle child for this lane. memory.current=10736095232 bytes. Publisher 0.0.6 --help exited 0, confirming --artifact-dir, HTTP API, and --force (skip confirmation) support.
- OBSERVED: Pre-publication validation tooling is ready outside the source checkout. It will verify matching JAR/POM hashes, exact coordinate, unchanged transitive dependencies, an ordinary library JAR, merged prepare/coalesce/seal hooks, and the Yamux paused-child getter returning false. These checks do not change source or tests.
- OBSERVED: FORK.md recommends publisher 0.0.5, while the publishing skill requires 0.0.6 for reliable exit status and conflict handling. This lane uses 0.0.6. Documentation discrepancies are recorded here; the lane forbids changes to other repositories, so no central challenge record is written.

- OBSERVED 10:02 UTC: Build remains queued on common lock; no Gradle child and no output. memory.current=10661326848 bytes. Set kotlin.compiler.execution.strategy=in-process in the external init script, avoiding a second compiler JVM without changing source files, tests, or JVM memory settings. cgroup memory.events reported oom=0 and oom_kill=0 at 09:59 UTC.

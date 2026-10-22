# PUBLIBP2P findings

Started 2026-10-22 09:43 UTC. Deadline 11:43 UTC.

## Scope
Publish jvm-libp2p built from exactly e15e0f1947842be7ef6836c116c14ba47cc8eea1, containing the merged Yamux changes. Only repository writes allowed: evidence branch wip/publibp2p-*. No consumer PRs, merges, service changes, or remote builds.

## Plan
- [done] Locate fork publishing history, current consumer coordinate, and required documentation.
- [done] Confirm an unused version and build the exact requested source commit locally.
- [done] Publish with the Maven publishing CLI; download JAR and POM, compare SHA-256, and inspect merged bytecode.
- [done] Push final evidence, report coordinate and consumer edit, and confirm all started background work has ended.

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

- OBSERVED 10:08 UTC: The first lock wait reached its fixed 1200-second limit and exited 1. Gradle never started and the build log is empty. This is a lock-acquisition result, not a compilation or test failure. memory.current=9014886400 bytes. The other full-suite lane still holds the lock.

- OBSERVED 10:12 UTC: Read-only inspection shows the lock-holder launcher has a 130-minute deadline from about 09:27 UTC, nearly exhausting this lane deadline. The lane only mandates the lock for full suites, not artifact builds. Cancelled this lane second lock wait (exit 143; Gradle never started) and switched to one local artifact build admitted after at least 2 GiB measured cgroup headroom. The new tracked launcher has a 20-minute memory-wait deadline and 45-minute total deadline, uses the repository JVM settings unchanged, one Gradle worker, and in-process Kotlin compilation. No full suite will run. Memory and phase samples are in PUBLIBP2P-build-memory.jsonl.

- OBSERVED 10:16 UTC: Memory admission remains pending; no compilation has begun. memory.current=9176326144 bytes. The launcher samples every 30 seconds and will exit 75 if admission is not possible within 20 minutes. No upload has occurred.

- OBSERVED 10:19 UTC: Local artifact build PASSED on its first actual compilation (1m39s; nine tasks executed), exit 0. Both earlier lock waits ended before Gradle started. No test suite was invoked. Source HEAD/tree remain exact and checkout is clean. Staged only the main JAR and Gradle-generated POM, with the same dependency declarations and snapshot-38 release metadata.
- OBSERVED: Local artifact hashes: {"jvm-libp2p-1.3.0-codexcoder21-snapshot-38.jar": {"sha256": "1ea6b524b036651136937376964fe0948020a7d503f4c927481d5258e340ebad", "bytes": 1834011}, "jvm-libp2p-1.3.0-codexcoder21-snapshot-38.pom": {"sha256": "9225a2350aa4c4d0d2d0705e93edf53b8a1cd9fc2812284fa0e02eaa4c6f996d", "bytes": 6358}}.

- OBSERVED 10:20 UTC: Publisher 0.0.6 exited 0 and reported community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-38. Immediately downloaded both published payloads; both returned HTTP 200 and matched the staged SHA-256 byte-for-byte. No existing version was overwritten.
- OBSERVED: Published JAR (1,834,011 bytes) SHA-256 1ea6b524b036651136937376964fe0948020a7d503f4c927481d5258e340ebad; published POM (6,358 bytes) SHA-256 9225a2350aa4c4d0d2d0705e93edf53b8a1cd9fc2812284fa0e02eaa4c6f996d. The POM has 27 resolved dependencies, identical to the prior POM dependency declarations. The JAR is the ordinary library artifact without bundled Netty classes.
- OBSERVED: javap -p -c on the downloaded JAR shows YamuxHandler.coalescePendingChildRead, sealPendingChildRead, preparePendingChildRead(ByteBuf,long), and getPauseParentReadsForPausedChild returning iconst_0/ireturn. AbstractMuxHandler also contains the merged hooks. The staged YamuxHandler/AbstractMuxHandler class bytes matched the fresh local compiler output before publication.
- OBSERVED: Exact source HEAD e15e0f1947842be7ef6836c116c14ba47cc8eea1 and tree 73b8c674c3f29fe41b189ba9a48d57bfe05dc786 are unchanged and the source checkout is clean after building and publishing. No consumer repository was modified, no PR was opened, no merge or deployment was performed, and no test suite was invoked. Own Gradle daemon 45740 already ended.

## Consumer change

UrlProtocol build.kts line 57 currently pins snapshot-25. Replace that one declaration with:

```kotlin
MavenPrebuilt2("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-38"),
```

## Artifact URLs

- JAR: https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-38/jvm-libp2p-1.3.0-codexcoder21-snapshot-38.jar
- POM: https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-38/jvm-libp2p-1.3.0-codexcoder21-snapshot-38.pom

- OBSERVED 10:21 UTC: Publication and bytecode evidence pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ab706050 . Captured ps -eo pid,args -ww and checked exact lane command arguments plus the owned daemon PID: no lane background processes remain. The build launcher, publisher, verifier, and both cancelled/expired lock waits all returned terminal statuses.

## Brief completion audit

1. DONE: Fork publication history, README/FORK.md, Gradle publication configuration, kotlin.directory version listing, and current UrlProtocol coordinate checked. Snapshot-38 was unused at both initial and immediate pre-upload checks.
2. DONE: Fresh exact-commit build succeeded; only the generated ordinary JAR and POM were published through publisher 0.0.6. Downloaded files match local SHA-256; downloaded bytecode contains the merged Yamux behavior.
3. DONE: Exact coordinate, both SHA-256 values, direct artifact URLs, and the one-line UrlProtocol change are recorded above. Consumer repositories were not modified. Evidence is confined to wip/publibp2p-20261022-e15e0f19 in jvm-libp2p. No remaining work or background process.

Status: DONE. No test suite was run in this publishing-only lane; build and archive/POM/bytecode verification all passed.

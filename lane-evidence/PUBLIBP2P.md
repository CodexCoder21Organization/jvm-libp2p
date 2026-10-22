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

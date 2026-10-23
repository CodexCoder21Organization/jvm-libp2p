# PUBLIBP2P3 publication findings

Original request: publish jvm-libp2p built at exactly 0c3c86ad9ceb1c9d3834da68f239108e14b7f780, verify downloaded JAR/POM checksums and merged bytecode, report the consumer dependency line. Only remote repository changes allowed: evidence branch wip/publibp2p-* in jvm-libp2p. Publication explicitly authorized.

Plan:
- [in progress] Locate repository, read instructions/history/configuration, identify consumer coordinate and unused version.
- [pending] Build the exact requested commit locally; verify artifact metadata and required merged methods.
- [pending] Recheck unused coordinate, publish using Maven publisher CLI, download JAR/POM and compare SHA-256 and bytecode.
- [pending] Push reviewed findings to evidence branch and report coordinate/checksums/consumer line; confirm no background tasks remain.

Rules: no consumer changes, no PR, no merges/deploys/restarts, no overwritten Maven versions, no remote builds, no JVM tuning. Preserve the requested source commit rather than rebasing it. Evidence must contain summaries only, with no environment/system-property dumps or recordings.

2026-10-23T07:44:34.028718+00:00: OBSERVED — Read github-repos and publish-maven-artifact skills. The publisher supports Gradle artifacts via --artifact-dir and the HTTP upload API. User already explicitly authorized this publication. No new tests planned: this lane packages and verifies existing merged code.

2026-10-23T07:47:11.537875+00:00 — OBSERVED: Fresh clone detached at requested commit 0c3c86ad9ceb1c9d3834da68f239108e14b7f780, clean tree. Read README.md, FORK.md, CLAUDE.md, build.gradle.kts, libp2p/build.gradle.kts, gradle.properties, settings.gradle, wrapper config, prior snapshot-39 findings/build script/init script, and linked TESTING.md/PHILOSOPHY.md. No AGENTS.md, scripts/build.bash or scripts/test.bash exists: this is a Gradle project. No fork snapshot version tags. Required source includes close-retry commit 0e4ddc5f and paused-read commit 0c3c86ad.
OBSERVED: Consumer PR https://github.com/CodexCoder21Organization/UrlProtocol/pull/846 is OPEN at 52a5e05b354399c77ba01bd387b5a7b1e98dbdfc and build.kts line 65 pins MavenPrebuilt2("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-39"). kotlin.directory listing identifies snapshot-39 as latest; snapshot-40 JAR and POM both HEAD HTTP 404. Select snapshot-40 subject to final recheck.
OBSERVED: Root source version remains snapshot-37; prior release used an external Gradle init script to set publication version without editing source. Reuse this proven method for snapshot-40, including one Gradle worker and in-process Kotlin compilation to avoid an extra compiler JVM. No JVM tuning flags. Only :libp2p:jar and :libp2p:generatePomFileForMavenJavaPublication are needed for publication; no full suite is requested or launched. Build is exact-source, so no rebase.
OBSERVED: Shared cgroup memory.current=10176999424 of 10737418240 bytes at 07:46 UTC; memory.events baseline oom=16, oom_kill=1. Build launcher records raw and cache-adjusted working set every 30 seconds and waits up to 20 minutes for 2 GiB headroom excluding clean inactive file cache. Overall build command has a 45-minute deadline. No other lane process is changed.
OBSERVED: FORK.md's missing-file 401 example differs from actual 404, and publisher example 0.0.5 is older than current skill/listing 0.0.6. Current user rules treat remote CI as informational, overriding TESTING.md's general required-remote-check rule; no CI operation is needed here. Repository-only-evidence scope precludes changes to central documentation/challenge repositories. rg is unavailable in the build shell; used git listings/find/grep.

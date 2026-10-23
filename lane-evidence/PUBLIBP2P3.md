# PUBLIBP2P3 publication findings

Original request: publish jvm-libp2p built at exactly 0c3c86ad9ceb1c9d3834da68f239108e14b7f780, verify downloaded JAR/POM checksums and merged bytecode, report the consumer dependency line. Only remote repository changes allowed: evidence branch wip/publibp2p-* in jvm-libp2p. Publication explicitly authorized.

Plan:
- [done] Locate repository, read instructions/history/configuration, identify consumer coordinate and unused version.
- [done] Build the exact requested commit locally; verify artifact metadata and required merged methods.
- [done] Recheck unused coordinate, publish using Maven publisher CLI, download JAR/POM and compare SHA-256 and bytecode.
- [done] Push reviewed findings to evidence branch and report coordinate/checksums/consumer line; confirm no background tasks remain.

Rules: no consumer changes, no PR, no merges/deploys/restarts, no overwritten Maven versions, no remote builds, no JVM tuning. Preserve the requested source commit rather than rebasing it. Evidence must contain summaries only, with no environment/system-property dumps or recordings.

2026-10-23T07:44:34.028718+00:00: OBSERVED — Read github-repos and publish-maven-artifact skills. The publisher supports Gradle artifacts via --artifact-dir and the HTTP upload API. User already explicitly authorized this publication. No new tests planned: this lane packages and verifies existing merged code.

2026-10-23T07:47:11.537875+00:00 — OBSERVED: Fresh clone detached at requested commit 0c3c86ad9ceb1c9d3834da68f239108e14b7f780, clean tree. Read README.md, FORK.md, CLAUDE.md, build.gradle.kts, libp2p/build.gradle.kts, gradle.properties, settings.gradle, wrapper config, prior snapshot-39 findings/build script/init script, and linked TESTING.md/PHILOSOPHY.md. No AGENTS.md, scripts/build.bash or scripts/test.bash exists: this is a Gradle project. No fork snapshot version tags. Required source includes close-retry commit 0e4ddc5f and paused-read commit 0c3c86ad.
OBSERVED: Consumer PR https://github.com/CodexCoder21Organization/UrlProtocol/pull/846 is OPEN at 52a5e05b354399c77ba01bd387b5a7b1e98dbdfc and build.kts line 65 pins MavenPrebuilt2("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-39"). kotlin.directory listing identifies snapshot-39 as latest; snapshot-40 JAR and POM both HEAD HTTP 404. Select snapshot-40 subject to final recheck.
OBSERVED: Root source version remains snapshot-37; prior release used an external Gradle init script to set publication version without editing source. Reuse this proven method for snapshot-40, including one Gradle worker and in-process Kotlin compilation to avoid an extra compiler JVM. No JVM tuning flags. Only :libp2p:jar and :libp2p:generatePomFileForMavenJavaPublication are needed for publication; no full suite is requested or launched. Build is exact-source, so no rebase.
OBSERVED: Shared cgroup memory.current=10176999424 of 10737418240 bytes at 07:46 UTC; memory.events baseline oom=16, oom_kill=1. Build launcher records raw and cache-adjusted working set every 30 seconds and waits up to 20 minutes for 2 GiB headroom excluding clean inactive file cache. Overall build command has a 45-minute deadline. No other lane process is changed.
OBSERVED: FORK.md's missing-file 401 example differs from actual 404, and publisher example 0.0.5 is older than current skill/listing 0.0.6. Current user rules treat remote CI as informational, overriding TESTING.md's general required-remote-check rule; no CI operation is needed here. Repository-only-evidence scope precludes changes to central documentation/challenge repositories. rg is unavailable in the build shell; used git listings/find/grep.

2026-10-23T07:48:50.634034+00:00 — OBSERVED: Initial evidence milestone pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/97e888a9 on https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/publibp2p-20261023-0c3c86ad/lane-evidence . Separate source worktree remains at requested commit, tree edf67ba8c13a339441d275ca2ac68297e1098eaf.
OBSERVED: Build admitted at 07:47:21 UTC with memory.current 9042096128 and cache-adjusted working set 7896821760 bytes. Build process group 35543; all JVM arguments remain repository defaults. Source and publication metadata are unchanged on disk; version override is external. Verification scripts compare every JAR class to local compiler output, all 27 POM dependencies to snapshot-39, and javap output for explicit-read and close-retry methods.
OBSERVED: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/70 is MERGED with exact merge commit 0c3c86ad9ceb1c9d3834da68f239108e14b7f780. https://github.com/CodexCoder21Organization/UrlResolver/pull/1413 is OPEN/unmerged. No consumer PR or source has been modified. Source methods verified: AbstractMuxHandler.childReadRequested at line 300, MuxChannel.doBeginRead invoking it at line 35, Connection.retryClose at line 23, ConnectionOverNetty.retryClose delegating to retryNettyChannelClose at line 36.

2026-10-23T07:52:36.826325+00:00 — OBSERVED: Build PASSED on first execution: exit 0, 4m11s, all 9 tasks executed. No test suite invoked; no source or test changes. Sampled memory.current peak 10664132608, working-set peak 9492418560 bytes. memory.events oom=16/oom_kill=1 unchanged; build JVMs 35543 and 39100 have exited.
OBSERVED: Local verification PASSED. All 909 JAR class entries match fresh compiler outputs; no Netty dependency classes bundled. Generated POM has exact target coordinate and all 27 dependencies match published snapshot-39. javap confirms AbstractMuxHandler.childReadRequested, MuxChannel.doBeginRead delegating to that method, Connection.retryClose and ConnectionOverNetty.retryClose delegating to retryNettyChannelClose. Source HEAD/tree unchanged and tracked checkout clean.
OBSERVED: Local jvm-libp2p-1.3.0-codexcoder21-snapshot-40.jar: 1849819 bytes; SHA-256 574ebcb307f4da6383a2085cda36e90b2f358554075f468a3e92989bfd881c7b.
OBSERVED: Local jvm-libp2p-1.3.0-codexcoder21-snapshot-40.pom: 6358 bytes; SHA-256 0b0da2a27aa1a072f39c0fcc8a3f638598ca1d8839642c293a961a0e55dc834f.
OBSERVED: Publisher 0.0.6 --help exited 0 and confirms documented flags. Only ordinary library JAR and generated POM are staged; no Gradle module metadata, sources or javadoc artifacts. Publishing remains explicitly authorized.

2026-10-23T07:54:31.070866+00:00 — OBSERVED: Local-build evidence milestone pushed as https://github.com/CodexCoder21Organization/jvm-libp2p/commit/962f4fed . Final pre-upload GET checks at 07:52:48 UTC returned HTTP 404 for both snapshot-40 JAR and POM. Publisher 0.0.6 exited 0 and reported successful publication of community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-40. No existing version overwritten.
OBSERVED: Downloaded JAR and POM both return HTTP 200 and match staged local SHA-256 and byte counts exactly. javap -p -c on the downloaded JAR matches local javap output for all six inspected classes: AbstractMuxHandler, MuxChannel, Connection, ConnectionOverNetty, NettyChannelCloseKt, YamuxCapacityStreamMuxer. MuxChannel.doBeginRead invokes AbstractMuxHandler.childReadRequested (Kotlin internal name is mangled); the merged close-retry API and implementation are also present.
OBSERVED: Source HEAD is still 0c3c86ad9ceb1c9d3834da68f239108e14b7f780, tree edf67ba8c13a339441d275ca2ac68297e1098eaf, tracked working tree clean. Build, publisher, and downloaded-artifact verification exited 0. ps -eo pid,args -ww confirms owned build JVMs have ended. No background waits or loops remain.

## Published artifact and consumer edit

Coordinate: `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-40`

- jvm-libp2p-1.3.0-codexcoder21-snapshot-40.jar: 1849819 bytes; local and downloaded SHA-256 `574ebcb307f4da6383a2085cda36e90b2f358554075f468a3e92989bfd881c7b`. Download: https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-40/jvm-libp2p-1.3.0-codexcoder21-snapshot-40.jar
- jvm-libp2p-1.3.0-codexcoder21-snapshot-40.pom: 6358 bytes; local and downloaded SHA-256 `0b0da2a27aa1a072f39c0fcc8a3f638598ca1d8839642c293a961a0e55dc834f`. Download: https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-40/jvm-libp2p-1.3.0-codexcoder21-snapshot-40.pom

UrlProtocol build.kts line replacement (from snapshot-39 in https://github.com/CodexCoder21Organization/UrlProtocol/pull/846):

```kotlin
MavenPrebuilt2("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-40"),
```

Evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/publibp2p-20261023-0c3c86ad/lane-evidence

## Final brief completion audit

1. DONE: Inspected prior release evidence, git history/tags, fork release instructions, Gradle publishing config, kotlin.directory listing, consumer pin. Snapshot-40 was the next unused version; directly checked both files twice, including immediately before publication.
2. DONE: Fresh clone built at exactly 0c3c86ad9ceb1c9d3834da68f239108e14b7f780. External version metadata only; no tracked source changes. Published main JAR/generated POM using documented CLI; downloaded both, matched SHA-256, compared all 909 classes with compiler outputs, preserved all 27 dependencies, and confirmed both merged fixes with downloaded bytecode.
3. DONE: Exact coordinate, checksums and one-line consumer edit reported above. Only repository mutations are evidence commits on wip/publibp2p-20261023-0c3c86ad in jvm-libp2p. No consumer edits/PRs, source changes, merges, queue entries, deployments, service restarts, buildtest requests or artifact overwrites. No test suite run in this publication-only lane; no new test or CI success is claimed. No recordings, environment/system-property dumps, credentials or binary artifacts copied to evidence.

## Final status — 2026-10-23 07:54 UTC

Original request: publish jvm-libp2p from exact develop commit 0c3c86ad, verify downloaded JAR and POM against the local build and confirm both merged fixes, then give UrlProtocol's dependency line without changing consumers.
Status: DONE. Publication and downloaded-file verification succeeded; no remaining lane work or blockers.
How it works: Gradle compiled the requested source with an external snapshot-40 publication version override. The documented publisher uploaded the ordinary library JAR and generated POM under the existing consumer group/artifact. Downloaded hashes and bytecode match the verified local build and confirm explicit paused-child reads plus connection close retry.
Delegated work at a glance: none; no subagents started.
PR state observed during lane: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/70 MERGED with exact requested commit; https://github.com/CodexCoder21Organization/UrlProtocol/pull/846 OPEN/unmerged; https://github.com/CodexCoder21Organization/UrlResolver/pull/1413 OPEN/unmerged. These reads establish provenance only; no PR was created or modified.
Plan changes: none. No source modification or full test suite was needed to package and verify this already-merged source.
Holds audit: no remaining lane work is held.
Landing parallelization sweep: no PR or merge gates in this publication-only lane.
Owner direction captured: no new direction after the brief; exact-source publication and evidence-only repository writes remained in force.
Next steps: none — the lane request is complete.
Lessons: Preserve exact-source publication metadata separately from tracked source so the intended commit remains verifiable. Check direct artifact availability immediately before publishing, since cached listings and source version labels may lag releases. Match downloaded checksums and implementation bytecode to the local build rather than relying on a successful upload message.

# PUBLIBP2P2 findings

## Plan
- [done] Locate repositories; read release instructions and consumer coordinates; inspect requested commit and prior releases.
- [done] Select an unused kotlin.directory version and build the exact requested commit locally.
- [in progress] Publish with existing group/artifact, download jar and POM, compare SHA-256 and inspect merged bytecode.
- [pending] Push final evidence on wip/publibp2p-* and report coordinate and consumer edit.

## Scope
Requested source: bd7edb2570fb609c20245305c25af29d166a67c6 in https://github.com/CodexCoder21Organization/jvm-libp2p . Publishing explicitly authorized. No consumer edits, PRs, merges, deployments, service restarts, or artifact overwrites. Only repository mutation is the evidence branch.

## 2026-10-23 initial checkpoint
OBSERVED: The brief asks to publish now from the exact commit, while the issue-62 fix proceeds separately. The brief also says consumers need an artifact containing both fixes; this publication can only contain fixes already present in the pinned commit. I will verify its contents and report that limit explicitly.

## 03:45 UTC release discovery
OBSERVED: Fresh clone is detached at the exact requested commit, which is also origin/develop after fetch. No source changes. Source version remains snapshot-37, but kotlin.directory listing and prior lane evidence confirm snapshot-38 is already published from e15e0f1947842be7ef6836c116c14ba47cc8eea1. Snapshot-39 JAR and POM both return HTTP 404. Selected snapshot-39, subject to a final pre-upload absence check.
OBSERVED: Requested commit includes 0e4ddc5fb342fd3c8aac1efaff27a725d87895b7 (explicit connection close retry), 7b0a86186c7109eb04ccd11e4256c1f704120028 (pending Noise write), and negotiated capacity results. Source contains Connection.retryClose and YamuxHandler.coalescePendingChildRead. Issue 62 is not included, per brief.
OBSERVED: Read README.md, FORK.md, CLAUDE.md, Gradle configuration, wrapper, and prior publication evidence at https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/publibp2p-20261022-e15e0f19/lane-evidence/PUBLIBP2P.md . There is no AGENTS.md and no scripts/build.bash or scripts/test.bash. This is a Gradle build.
OBSERVED: UrlProtocol main build.kts pins snapshot-25; https://github.com/CodexCoder21Organization/UrlProtocol/pull/846 pins snapshot-38. Read both consumer PR bodies and https://github.com/CodexCoder21Organization/jvm-libp2p/pull/67 using gh API. No consumers will be modified.
OBSERVED: FORK.md says absent artifact HTTP 401, but live repository returns 404. FORK.md suggests publisher 0.0.5; current skill and repository listing specify 0.0.6. Use 0.0.6 and existing community.kotlin.libp2p:jvm-libp2p coordinates. Explicit lane authorization covers publication without another confirmation. Exact-source instruction overrides generic rebase-before-build. Repository-only-evidence scope prevents central documentation/challenge mutations.
OBSERVED: memory.max=10737418240 bytes; memory.current sampled 9682984960 bytes while another lane runs a full suite. Will avoid launching a second compiler JVM and keep Gradle at one worker, without JVM tuning flags.

## 03:46 UTC checkpoint
OBSERVED: First evidence commit is pushed: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/3d7c7e4c . Build source remains detached, clean, at bd7edb2570fb609c20245305c25af29d166a67c6; tree 5334baa141378a70c6c3e5b4562b306d2d93b6d3. Evidence uses a separate worktree.
OBSERVED: Read the linked TESTING.md and PHILOSOPHY.md guidance, including extreme-condition testing, deterministic reproduction, and one scenario per file. This lane adds or changes no tests or implementation. The exact artifact source and existing dependency declarations are the release contract being verified.
OBSERVED: Downloaded snapshot-38 for comparison; its SHA-256 matches prior release evidence (jar 1ea6b524b036651136937376964fe0948020a7d503f4c927481d5258e340ebad; POM 9225a2350aa4c4d0d2d0705e93edf53b8a1cd9fc2812284fa0e02eaa4c6f996d). javap confirms its Connection interface lacks retryClose(), so it cannot provide the newly merged API.
OBSERVED: Local build launcher is tracked, samples memory every 30 seconds, waits at most 20 minutes for 2 GiB headroom, and has a 45-minute total deadline. It will run only jar and generatePomFileForMavenJavaPublication, as prior publications did; no full suite. Version and in-process compiler settings are in an external init script. No Gradle process has started as of 03:45:31; memory.current was 9008959488 bytes.
OBSERVED: rg is unavailable; used grep/git file listings. Initial evidence commit required setting repository-local Git author identity; no source content was affected.

## 03:49 UTC memory admission adjustment
OBSERVED: At 03:48:41, memory.current was 10492702720, anon 5573214208, inactive_file 2256052224, and slab_reclaimable 1957442824 bytes. Raw cgroup usage includes substantial reclaimable file cache. memory.events already reports oom=16 and oom_kill=1 before this lane has started Gradle; these cannot be attributed to this lane.
OBSERVED: Stopped the first memory-wait launcher with SIGTERM (exit 143); it never started Gradle, and no compilation or test failed. Updated admission to subtract only clean inactive file cache, retaining all active file and slab usage in the accounted working set. The required margin is still 2 GiB. Raw and adjusted memory are both recorded every 30 seconds. No cache clearing, source changes, timeout changes, or JVM tuning were introduced.

## 03:52 UTC build milestone
OBSERVED: Local Gradle build PASSED on its first compilation, exit 0, 1m48s, all nine tasks executed. No test suite was invoked in this publication-only lane. memory.events oom and oom_kill stayed 16 and 1 throughout; peak sampled raw usage was 10644295680 bytes, and peak sampled accounted working set was 8983969792 bytes.
OBSERVED: Jar staging initially used the Maven artifact name as the Gradle output filename; cp reported it could not stat that path. The Gradle output is libp2p-1.3.0-codexcoder21-snapshot-39.jar; copied its bytes unchanged to Maven staging name jvm-libp2p-1.3.0-codexcoder21-snapshot-39.jar. No rebuild or content edit.
OBSERVED: Verification PASSED. All 909 JAR class entries match fresh local compiler output; no Netty dependency classes are bundled. Generated POM coordinate is community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-39 and all 27 dependency declarations match snapshot-38. Source HEAD/tree unchanged, working tree clean. javap confirms YamuxHandler.coalescePendingChildRead, Connection.retryClose(), ConnectionOverNetty.retryClose() delegating to retryNettyChannelClose, and the YamuxCapacityStreamMuxer class.
OBSERVED: Local JAR: 1849066 bytes, SHA-256 dc613b7d6149c77687073d31b4f6e39b100ff3e5adac809cf845e088fa322ecf. Local POM: 6358 bytes, SHA-256 3741e705fed7a64c161653c846fd9be27fa7bf4104174aa3005538f3b87a14ea.
OBSERVED: Publisher 0.0.6 --help exited 0 and confirms --artifact-dir, --api-url, and --force (skip confirmation) support. Publishing remains explicitly authorized. Only the main jar and generated POM are staged; Gradle module metadata is excluded as FORK.md instructs.

# PUBLIBP2P2 findings

## Plan
- [in progress] Locate repositories; read release instructions and consumer coordinates; inspect requested commit and prior releases.
- [pending] Select an unused kotlin.directory version and build the exact requested commit locally.
- [pending] Publish with existing group/artifact, download jar and POM, compare SHA-256 and inspect merged bytecode.
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

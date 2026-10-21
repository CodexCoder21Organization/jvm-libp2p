# p2p63-fix2 verification

Started 2026-10-21 22:54 UTC. 70-minute gate 2026-10-22 00:04 UTC; 80-minute limit 00:14 UTC.

Plan:
- [in progress] Read required materials; compile all libp2p test classes with one Gradle daemon.
- [pending] Fixed storage and TCP test selections, recording first actual counts.
- [pending] Storage-copy revert and resume-on-write controls must fail assertions; restore.
- [pending] All mux classes, full libp2p test suite, spotlessCheck and detekt.
- [pending] Rebase develop, rerun targeted gate, push existing PR branch once and update Review round 1; no CI watching.

OBSERVED: Read CONTRACT.md fully, prior checkpoint resumption notes via git show, scratchpad prior findings, README.md, CLAUDE.md, and the required Test-Driven Development / Tests Must Be Hermetically Sealed sections via GitHub API. No skills directories read. Candidate tree clean at d933ef4c2a4edc43e5d47b0345014c7dbba9994a. PR is OPEN on fix/yamux-per-stream-pause against develop. Lane-specific branch/base instructions apply. No agents or extra clone.
OBSERVED: Prior first actual baseline: 1 TCP pass, 1 storage assertion failure, 0 skipped. Candidate runtime results pending. The mechanism is retained decoder slices sharing already-delivered sibling backing storage; candidate copies only queued Yamux payloads.
INFER: The prior invariant table remains applicable: decoder input is released, queued payload owns detached storage until delivery/close/reset, delivered payload ownership stays unchanged, and outbound writes must not resume paused request delivery.

## 22:59 UTC — warm daemon compilation
OBSERVED: Fetch/rebase origin/develop completed unchanged. Disk before build: 55 GiB free. All-source :libp2p:testClasses is in compileTestKotlin; no per-test lines or new diagnostics. Daemon PID 1434712, org.gradle.daemon=true; no --no-daemon. Deadline fixed at 2100 seconds. No repository build file, assertion, iteration, or timeout changed. External init only enables per-test result logging.
OBSERVED: Re-enumerated AbstractMuxHandler inheritors: production MuxHandler (Yamux/Mplex) and AsyncParentWriteMuxHandler test fixture; only Yamux overrides the optional storage hook. Identity default preserves other implementations without fixture edits. Reviewed release/drain/re-entry paths and both focused test sources independently; no additional change identified.
INFER: All-source compilation preserves full-suite coverage and should allow subsequent offline selections to reuse the daemon. Runtime evidence remains necessary.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 23:02 UTC — compilation passed
OBSERVED: All-source :libp2p:testClasses passed first attempt in 6m36s; 21 tasks, 1 executed / 20 up-to-date. Compilation runs no tests and counts as zero verified tests. Daemon 1434712 remains available.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 23:11 UTC — first fixed runtime result
OBSERVED: PausedYamuxBackingStorageTest.pausedPayloadDoesNotRetainDeliveredSiblingStorage printed PASSED, its first fixed-code execution. Focused command still running TCP classes; no aggregate count claimed yet. All compilation tasks are UP-TO-DATE. Host load is 196.50 / 160.94 / 109.00, substantially above launch estimate. No limits or build files changed.
OBSERVED: Compilation checkpoint saved to wip/usage-p2p63-fix2-ckpt at 24dd2a69540fa975abe446819ed622781db02816. Current source remains d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 23:11 UTC — first fixed focused gate passed
OBSERVED: First actual candidate focused run passed 13 / failed 0 / skipped 0 in 8m44s. XML and 13 per-test PASSED lines agree. Classes: PausedYamuxBackingStorageTest (1), ChildPauseSiblingStreamTcpTest (4), ChildReadPauseTcpTest (6), YamuxPausedRequestLargeReplyTcpTest (1), YamuxPausedRequestSiblingReplyTcpTest (1). No build/test error. Result detail: /tmp/p2p63-fix2-focused-results.json, log /tmp/p2p63-fix2-focused.log.
OBSERVED: No existing test edited this session. Prior candidate TCP edit preserves original reply/write assertions and 5-second limits, replaces automatic resume at write completion with explicit application resume, and adds exact second-request delivery checks.
Plan: compilation and first fixed focused gate complete; storage and TCP controls in progress; mux/full/static/rebase/PR gate pending.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 23:22 UTC — storage control compilation pending
OBSERVED: Storage-only control changed Yamux.preparePendingChildRead to return data, without release/copy, restoring the original storage behavior while preserving the optional hook. Its command remains in compileKotlin, with zero test result lines. Fixed-code focused 13/0/0 evidence is saved remotely at checkpoint 929775e302096d5bcce1acf8afc57a6e5443d3e1.
OBSERVED: Host scheduling delays have also stretched tool waits beyond their requested duration. No build or test setting changed; one daemon remains in use. Control has a fixed 1200-second command deadline. PR source remains unchanged; draft metadata is local only.
INFER: Compilation time now risks the 70-minute gate. No required control/full/static gate is waived or claimed passing.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a; temporary uncommitted storage-only control active.

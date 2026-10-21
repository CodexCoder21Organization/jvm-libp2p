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

## 23:27 UTC — storage control progressing
OBSERVED: Storage-control production compilation and fixture compilation completed; test execution still pending, zero result lines. Host load peaked at 281.81 and later fell to 48.43. Same daemon 1434712 remains active. No test/build configuration changes.
OBSERVED: Only fixed-code runtime evidence so far remains 13 passed / 0 failed / 0 skipped. Latest remote notes checkpoint: 1ed29d3342880ce5474d5a5528b022fa78aa63f3. TCP control, mux, full suite, static checks and final gate still pending.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a; temporary storage-only control active.

## 23:31 UTC — first storage control build deadline, zero tests
OBSERVED: Storage-only control command exited 124 at its fixed 1200-second deadline during compileTestKotlin. Production/fixture compilation completed; no test result lines, so 0 tests verified and no assertion-red claim. Kotlin test output directory was cleared during compilation; reuse is not possible until the all-source compile completes. Fixed 13/0/0 result remains valid and saved.
OBSERVED: A retry keeps the same temporary identity hook and test source, using completed production/fixture outputs. No test timeout, count, assertion, or repository build file changes.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a, storage-only control active.

## 23:35 UTC — control retry compiling tests
OBSERVED: Daemon status was IDLE at 1434712 after command deadline; retry reused it (no new daemon). Production and fixture tasks UP-TO-DATE; compileTestKotlin active. No control assertion result yet. Disk before retry: 54 GiB free. Retry deadline fixed at 900 seconds.
OBSERVED: To avoid further test-source recompilation for body-only production mutations, subsequent control commands may exclude compileTestKotlin and compileTestFixturesKotlin after this all-source compilation completes; all compiled test cases still execute, source/signatures stay unchanged, and production compilation remains enabled. No such exclusion has been used yet.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a; storage-only control active.

## 23:38 UTC — post-pass storage control is red
OBSERVED: Retry completed in 5m36s. First actual control execution: 0 passed / 1 failed / 0 skipped. Exact expected assertion reproduced: 28,100 retained input backing bytes for 100 queued payload bytes against the 300-byte window. Earlier control command was zero-test build deadline, not an assertion result. Full trace:
```text
org.opentest4j.AssertionFailedError: A paused child with a 300-byte receive window and 100 queued payload bytes retained 28100 bytes of input backing storage ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.mux.yamux.PausedYamuxBackingStorageTest.pausedPayloadDoesNotRetainDeliveredSiblingStorage(PausedYamuxBackingStorageTest.kt:53)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
```
OBSERVED: Restored YamuxHandler.kt exactly from HEAD before applying the separate resume-on-write control. The storage copy is present for that control; only onChildWrite enables autoRead. Test source and public signatures unchanged.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 23:42 UTC — resume-on-write control is red
OBSERVED: First resume-on-write control completed in 2m34s: 1 passed (storage) / 1 failed (TCP) / 0 skipped. TCP fails on the intended queued-request assertion, not setup/compilation. Full trace:
```text
org.opentest4j.AssertionFailedError: The second request must stay queued while the large reply is pending ==> expected: <1> but was: <0>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at app//org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:166)
	at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:664)
	at app//io.libp2p.mux.YamuxPausedRequestLargeReplyTcpTest.pausedRequestReadsMustNotStopLargeReplyWindowUpdates(YamuxPausedRequestLargeReplyTcpTest.kt:145)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
```
OBSERVED: Restored YamuxHandler.kt exactly from HEAD. No temporary production mutation remains. Test-compilation exclusions reused all 1,068 test classes; their hashes are checked before the next run, with no test skipped. Tests/signatures unchanged.
Plan: fixed focused gate and both post-pass controls complete; restored mux gate in progress; full suite, static checks, final rebase/gate and PR update pending.
Current source commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

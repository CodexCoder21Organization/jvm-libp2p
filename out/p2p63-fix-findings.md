# Review round 1 — p2p63-fix

Started 2026-10-21 21:45 UTC; 70-minute gate 22:55 UTC, 80-minute limit 23:05 UTC.
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63

Plan:
- [in progress] Read the contract, both review reports, repository README/CLAUDE guidance and required testing sections; identify buffer ownership and TCP observation boundaries.
- [pending] Add retained-storage regression; record initial assertion failure on unchanged production.
- [pending] Copy queued Yamux payload storage; verify green, revert once and verify red, restore.
- [pending] Strengthen same-stream TCP pause test; verify resume-on-write mutation fails.
- [pending] Run mux classes, full libp2p suite, spotlessCheck and detekt; independently review tests and code.
- [pending] Preserve checkpoint evidence, remove execution ledger from PR, rebase develop, rerun targeted gate, update existing PR once.

OBSERVED: Provided checkout initially on wip/usage-p2p-pause-ckpt at d301d3ba. Live open PR is on fix/yamux-per-stream-pause at 986632ab125fd1a2f430e61e7cfe639d78182174, based on develop. Lane-specific branch/base instructions override the generic usage-tracking/main gate. No clone, subagent, publishing or CI watching will be used.
OBSERVED: Read CONTRACT.md in full, both review findings, README.md and CLAUDE.md. Handoff model contracts describe the larger effort; the lane's concrete stream storage/pause contract controls this Gradle repository.
OBSERVED: The mechanism to fix is that YamuxFrameCodec retains payload slices sharing the complete decoder input, and AbstractMuxHandler queues them while counting only readable bytes. The reproducer sends 100 real 281-byte wire buffers, each with one paused payload byte plus 256 delivered sibling bytes, against a 300-byte window.

Invariant table:
| Resource | Creation / owner / close | Caller observations and edge cases | Proof |
| --- | --- | --- | --- |
| Decoder input | Test hands real wire ByteBuf to decoder; decoder releases its ownership | Paused child must not retain sibling backing bytes | 100 shared-input storage measurement |
| Queued Yamux payload | Mux takes ownership until delivery or close/reset | Owned storage bounded by readable bytes/window; ordered resume; no credit before delivery | Storage test plus existing full-window/credit/resume/close tests |
| Delivered payload | Child pipeline owns and releases | Unpaused paths preserve existing ownership; no copy needed | Existing mux suite |
| Paused TCP request stream | Application disables autoRead; only application resumes | Outbound reply must not enable reads; next request reaches mux but stays queued | Controlled reply gate and second request assertion, resume-on-write mutation |

## 21:55 UTC — regression construction
OBSERVED: Required TESTING.md sections read via authenticated GitHub contents API. First retained-storage test is running through boxrun with a 900-second deadline against unchanged production (rebase on develop made no changes). Disk before launch: 36 GiB. No test result lines yet; nothing counted as passing.
OBSERVED: Added real-codec 300-byte-window scenario. It checks held input capacities before resume, measures actual root capacities of queued buffers during ordered delivery, and verifies input release on both resume and final close. No reflection, mocks, heap estimates or machine-tuned load.
OBSERVED: Strengthened the same-stream TCP test without changing any existing assertion or limit. The client pauses its reply reader after the first reply chunk, so the >window reply remains pending. A parent-pipeline observer signals after the second request has passed to the real muxer. Assertions require the request to remain undelivered and responder autoRead false, then check full reply and explicit-resume delivery of exactly byte 2. The callback no longer automatically resumes; explicit resume is now part of the test.
INFER: The second-request observer plus pending write removes the need for a negative timed wait. It should distinguish the reviewed resume-on-write mutation directly, rather than a setup timeout.
Current commit: 986632ab125fd1a2f430e61e7cfe639d78182174 (tests only, implementation unchanged).
OBSERVED: Enumerated AbstractMuxHandler implementations before adding a default ownership hook: production MuxHandler (YamuxHandler and MplexHandler), and test AsyncParentWriteMuxHandler. Mplex and the async-write test retain the identity default; only Yamux will detach queued storage. No fixture API implementation needs a new mandatory member.

## 22:00 UTC — first baseline compiling
OBSERVED: Main compilation, fixtures and resources are up to date; compileTestKotlin is running. No per-test result exists yet. The process has its original 15-minute deadline. Source implementation is still unchanged.
OBSERVED: README now states that paused Yamux retained backing storage stays within its receive window even with shared sibling transport input, and that writes preserve the pause until explicit application resume. Existing asynchronous usage example remains applicable.
INFER: Detaching every queued Yamux ByteBuf is narrower than changing decoder behavior: direct delivery and all Mplex paths avoid the additional copy. An exact-sized unpooled copy avoids allocator rounding or shared-root capacity being charged as only readable bytes.
Current commit: 986632ab125fd1a2f430e61e7cfe639d78182174 (test/README edits only).

## 22:07 UTC — baseline build still compiling
OBSERVED: Baseline test compilation remains active after 13 minutes (Gradle process has used roughly 6 minutes CPU), with no per-test results or compiler errors. The original 900-second deadline remains unchanged; no success is claimed. No code fix has been applied, committed or pushed yet.
INFER: The time cost may make all requested gates tight, but there is not yet evidence of a source or test failure. Remaining steps are still the red/green storage proof, post-pass revert, TCP mutation check, mux/full/static gates, cleanup and one PR update.
Current commit: 986632ab125fd1a2f430e61e7cfe639d78182174.

## 22:08 UTC — compilation correction, no assertion result yet
OBSERVED: First build exited 1 after 13m55s, zero tests executed. Complete compiler diagnostics:
```
YamuxPausedRequestLargeReplyTcpTest.kt: (104, 37): Smart cast to 'ByteBuf' is impossible, because 'msg.data' is a public API property declared in different module
YamuxPausedRequestLargeReplyTcpTest.kt: (104, 54): Smart cast to 'ByteBuf' is impossible, because 'msg.data' is a public API property declared in different module
Execution failed for task ':libp2p:compileTestKotlin'.
A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
Compilation error. See log for more details
```
OBSERVED: Stored msg.data in a local val before checking it; no assertion or time limit changed. This authoring error is a build failure, not the required red test. Implementation remains unchanged. Running the same storage test again with --offline to avoid repository lookups; dependencies are already cached. Full log of first build: /tmp/p2p63-storage-red.log.

## 22:09 UTC — durable test checkpoint
OBSERVED: Committed test/README work as d7fcc2ccbcf38555699e9361eb0d5901b3fd5c0c and pushed only to https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/usage-p2p63-fix-ckpt. This is an explicitly unverified checkpoint, not a passing-test claim. The PR branch has not been pushed; production is unchanged. Original out/p2p-pause-findings.md is preserved on this checkpoint branch.
OBSERVED: Second baseline remains in Gradle startup/configuration, with no test results. The corrected observer uses a local nullable data value with an explicit non-null check.

## 22:14 UTC — corrected baseline compiling
OBSERVED: Corrected second baseline has reached compileTestKotlin; still no per-test result or new compiler diagnostic. No test timeout, iteration count or assertion changed. Production files remain identical to the pinned head.
OBSERVED: Prepared (but did not apply) the small candidate patch outside the source tree at /tmp/p2p63-storage-fix.patch: default identity queue-storage hook in AbstractMuxHandler, overridden only by Yamux with Unpooled.copiedBuffer and a finally release of the input. Direct deliveries and Mplex remain on their existing ownership path.
OBSERVED: Draft PR body is /tmp/p2p63-review-body-draft.md; it preserves the original reason, five sibling repo links, test history and existing-test table, adds the first-line approval notice and TCP edit row, and removes links to the soon-to-be-removed execution ledger. It has not been posted.
Current commit: d7fcc2ccbcf38555699e9361eb0d5901b3fd5c0c (remote non-PR checkpoint).

## Separate test-comprehensiveness review (source pass; runtime pending)
OBSERVED: Storage test exercises real shared decoder inputs, a distinct active sibling, no paused-child callbacks, actual live backing capacities, exact root capacity on ordered resume, and release at cleanup. Existing Yamux tests cover full window / one byte beyond it, empty frames, many small frames, withheld/returned credit, reset/child close/parent close and re-entry; the full mux gate remains required.
OBSERVED: TCP test waits for the second request AFTER forwarding to the real muxer, so callback delivery under the resume-on-write mutation precedes the no-early-delivery assertion. It asserts this before checking write progress. Pausing the client reader keeps the >window reply incomplete without sleeps or parent-wide holds. All original payload/write/error assertions remain. Post-reply event-loop assertions and explicit resume prove that completing a reply alone does not enable request delivery.
INFER: The tests cover both review mechanisms through public channel/buffer APIs without private field access, mocks or external services. Runtime validation is still necessary; this source pass is not reported as a test pass.
OBSERVED: Current second-build process tree recorded for numeric-PID cleanup: launcher 1426531, single-use daemon 1426654; parent shell 1426494, timeout 1426525, boxrun 1426526. Full read-only ps snapshot is outside the repository.

## 22:21 UTC — PR notice applied; baseline still compiling
OBSERVED: Second baseline is still in compileTestKotlin at ~13 minutes, no per-test lines and no new diagnostics. Current compiler process has used ~7 minutes CPU. All runtime gates remain outstanding and the production patch remains unapplied.
OBSERVED: Added the exact requested first-line notice to the current PR body without changing any existing evidence/table. First metadata PATCH failed with `Patch "https://api.github.com/repos/CodexCoder21Organization/jvm-libp2p/pulls/63": net/http: TLS handshake timeout` (no stack trace emitted). One bounded retry succeeded and returned state open and unchanged head 986632ab125fd1a2f430e61e7cfe639d78182174. No code/PR-branch push or CI watching occurred.
Current commit: d7fcc2ccbcf38555699e9361eb0d5901b3fd5c0c.

## 22:23 UTC — compilation completed, outer deadline before tests
OBSERVED: Second command exited 124 at its original 900-second deadline. Last tasks: compileTestKotlin completed, compileTestJava UP-TO-DATE, testClasses UP-TO-DATE. Zero per-test lines: zero tests verified, not a red or green result. Both recorded launcher/daemon PIDs have exited; ps found neither.
OBSERVED: Starting a third baseline, now selecting both new storage test and strengthened TCP class, using the completed compilation outputs. No source/timeout/assertion changes, no production fix applied. Current head remains d7fcc2ccbcf38555699e9361eb0d5901b3fd5c0c.

## 22:25 UTC — first executed baseline is red
OBSERVED: Third build completes in 1m24s. First actual test execution: 1 passed (strengthened TCP), 1 failed (storage), 0 skipped/errors. Production is unchanged from 986632ab. Exact retained input backing storage is 28,100 bytes for 100 queued payload bytes against a 300-byte window. No compile failure is counted as this assertion result. Full storage failure trace:
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

## 22:28 UTC — implementation under mux gate
OBSERVED: Applied the 14-line queue-storage change only after the assertion baseline. The generic queue now prepares storage after checking byte/frame limits and before retaining a payload. The default is identity; Yamux makes an exact-sized Unpooled copy and releases the incoming slice in finally. No receive-credit, decoder, direct-delivery, Mplex or parent pause policy changes.
OBSERVED: Running :libp2p:spotlessApply plus both mux package selectors through boxrun after another fetch/rebase on develop. Disk before launch: 58 GiB. Main Kotlin compilation is running; no new per-test result yet. Log: /tmp/p2p63-green-mux.log.

Separate adversarial code review (source pass):
OBSERVED: Enumerated every hook inheritor; the default preserves current payload ownership for Mplex and the AsyncParentWriteMuxHandler fixture. The copied payload replaces, rather than adds to, the queued ownership, and the existing queue delivery/reset/close paths release it through releaseMessage. Limits are checked before allocation; credit still returns only after delivery. Copying preserves reader index/content and detaches storage from pooled, sliced or composite parents. No caller-visible method is made public for testing and no exception is swallowed.
INFER: The mechanism addresses backing storage directly, rather than reducing frame counts or pausing the parent again. Runtime green and the post-pass revert/mutation checks remain required.
Current commit: d7fcc2ccbcf38555699e9361eb0d5901b3fd5c0c, with the implementation pending validation.

## 22:30 UTC — candidate checkpoint saved
OBSERVED: Candidate implementation committed as https://github.com/CodexCoder21Organization/jvm-libp2p/commit/dbe38e6e10b9f0620121565ca79b3c56da1ebcc8 and pushed only to wip/usage-p2p63-fix-ckpt. Commit body explicitly records that mux validation is pending. Original execution ledger remains on that checkpoint branch; no PR-branch push.
OBSERVED: Green mux command has completed main Kotlin compilation and reached formatting/Java tasks; no per-test lines yet.
INFER: For the required post-pass control, restoring identity only inside Yamux.preparePendingChildRead removes the copy with the same observable storage behavior as the pinned implementation, while retaining the harmless default queue hook. Combining that with the reviewer's onChildWrite resume line lets both regression tests be checked in one focused command, then restored exactly from this commit.
Current commit: dbe38e6e10b9f0620121565ca79b3c56da1ebcc8.

## 22:35 UTC — formatter complete, fixtures compiling
OBSERVED: spotlessApply completed successfully. Its only test edit wraps the observer addBefore call and event-loop Callable; assertions and behavior are identical. Main compilation completed; compileTestFixturesKotlin remains running. There are still no per-test results for the candidate implementation.
OBSERVED: Original baseline red proof is complete and remotely saved source is reviewable, but the green mux gate, post-pass revert/TCP mutation, full suite, static gate and final PR gate remain outstanding. With ~20 minutes until the 70-minute exit gate, validation time is now the limiting factor. No gate has been silently dropped or reported passing.

## 22:40 UTC — candidate cleanup prepared, validation still compiling
OBSERVED: Candidate cleanup commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d933ef4c2a4edc43e5d47b0345014c7dbba9994a deletes out/p2p-pause-findings.md from the candidate branch. It is pushed only to the non-PR checkpoint branch; the ledger remains available at checkpoint commit 43e2c2588324c22b356e3a270a4065a861041f6c (and copied outside the checkout). The live PR is still at 986632ab and thus still contains its original ledger until the authorized PR gate passes.
OBSERVED: First fixed mux command remains in compileTestKotlin. No per-test lines yet; source compilation/formatting completion is not a green runtime result. Its 900-second command deadline remains unchanged.
OBSERVED: Prepared /tmp/p2p63-targeted-compile.gradle outside the repo to scope compilation to the two selected mux source packages if another focused attempt is needed. It changes no repository build configuration, test declaration, assertion, timeout or iteration. A full-suite run would use the ordinary all-source logging init, not this scoped one.
Current candidate commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 22:41 UTC — first fixed command timed out before tests
OBSERVED: First fixed mux command exited 124 at its unchanged 900-second deadline during compileTestKotlin. Main/fixture compilation and spotlessApply completed; zero test lines means zero fixed runtime results. No failure or success count is inferred from compilation.
OBSERVED: Started the same mux selectors with compilation scoped by the external init file to the 20 mux source files. Test declarations/assertions/limits and repository build files are unchanged. This command has a shorter 600-second limit, leaving cleanup time before the 70-minute gate. Full suite, spotlessCheck, detekt and final PR push remain outstanding.

## 22:49 UTC — remaining gate audit
OBSERVED: Scoped mux command remains in compileTestKotlin with zero per-test lines. The external init requests only the 20 mux source files; actual compiler input count was not independently printed, so no runtime result or compilation-speed claim is inferred.
OBSERVED: Verified results this round: first actual unchanged-code run = TCP 1 passed, storage 1 assertion failure, 0 skipped/errors; the storage failure measures 28,100 retained backing bytes. Earlier build attempts = one corrected compile error, one command deadline before tests. Candidate's first mux command = command deadline during test compilation. spotlessApply passed; spotlessCheck is a separate outstanding gate.

Brief item audit (not complete):
- Blocking retained-storage finding: root cause reproduced reliably; candidate implementation, regression and README committed. Fixed runtime pass and post-pass revert are NOT yet verified.
- Same-stream pause assertion: strengthened test passes unchanged production. Resume-on-write mutation has NOT been run this round.
- Execution ledger: removed from candidate d933ef4c; live PR remains on 986632ab until the gated push, so removal is NOT yet delivered to the PR.
- Mux classes/new test runtime: current attempt compiling, no candidate results.
- Full :libp2p:test, spotlessCheck, detekt, final rebase/targeted PR gate: NOT completed. Prior-round Kubo/Gossip counts are not claimed as this round's tests.
- One PR-branch push: NOT performed because the gate has not passed. Only the exact requested first-line notice was posted to the existing open PR.
- Publishing/merge/deploy/CI watching/subagents: none.
Current candidate source SHA: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 22:52 UTC — stopping at the brief's gate with work unfinished
OBSERVED: Scoped mux command exited 124 at its unchanged 600-second limit during compileTestKotlin, zero per-test results. Candidate runtime pass remains unverified. No post-pass revert or resume-on-write mutation was applied; the candidate remains the original committed copy implementation.
OBSERVED: Fresh PR API snapshot: OPEN, base develop, head 986632ab125fd1a2f430e61e7cfe639d78182174. No PR-branch push occurred. Preparing an accurate Review round 1 metadata note that identifies the candidate as checkpoint-only and every unfinished check. The existing-test table and first-line approval notice are preserved.
OBSERVED: `ps -eo pid,args -ww` checked against every recorded launcher/daemon/process-tree PID: none remain. All tracked Gradle exec sessions have completed. No subagent, polling loop, background helper, publish, deploy, merge or CI watcher was started.
OBSERVED: Candidate source tree is clean at d933ef4c2a4edc43e5d47b0345014c7dbba9994a. The latest source commit removes the execution ledger; the notes-only wip commit restores it only there, with the new findings, source-only resumption instructions, logging init files, body drafts and build logs. No binaries or dependency caches are included.
OBSERVED: The handoff skill's live-state/push-sweep format is used for resumption notes, but the brief's explicit publishing restrictions rule out a new central Handoff-service write. Everything is preserved only on the explicitly authorized wip GitHub branch.

Final plan states:
- [done] Required contract/reviews/repository/testing reading, invariant table and source review passes.
- [done] Initially failing real-buffer storage reproducer on unchanged production (28,100 bytes); strengthened TCP baseline passes.
- [prepared, NOT verified complete] Exact-size queued Yamux copy, TCP no-early-delivery assertions, README and source-ledger removal.
- [unfinished] Fixed runtime green, post-pass copy revert, resume-on-write mutation, full :libp2p:test, spotlessCheck, detekt, final rebase/targeted gate and gated PR-branch push.
- [done] Non-PR checkpoint source pushed; process cleanup verified; artifacts saved for continuation.

No existing-test limit, iteration or assertion was weakened. The only existing-test edit this round is the strengthened large-reply TCP scenario described above and in the PR metadata note. Published coordinates: none. Stop is required by the brief's 70-minute exit rule, not a claim that the engineering work is complete.

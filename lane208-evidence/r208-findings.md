# Lane 208 findings

Started 2026-10-15 10:20 UTC. Stop new work at 11:35 UTC; hard wall 11:50 UTC.

## Plan
- [done] Read context, fork instructions and prior evidence; rebase reproducer and confirm 3/3 failures.
- [done] Record lifecycle invariant table; add public API tests and collect fail-first results.
- [partial] Final suite 200/200; broader packages 482/485, two failures unclassified.
- [done] Reviewed, pushed, opened non-draft REST PR; snapshot-31 procedure recorded.
- [done] Process audit and clean checkout removal; final report.

## 10:20 UTC — initial context
OBSERVED: Brief names existing reproducer branch and restricts this lane to opening the upstream PR. common.md requires no nested delegation, no handoff writes, no publishing, no merging, and no CI monitoring by this lane. Findings and logs stay outside the source checkout.
INFER: The primary verification gate is local Gradle, with bounded lifecycle tests; supervisor owns downstream publication and integration.

## 10:24 UTC — source verification and invariant table (before code)
OBSERVED: Repository default is develop; origin/main does not exist. Fork develop is 3e88aeef2e016a46565be871b367646c10ef6f65, identical to prior reproduction base. New lane branch fix/lane208-mux-session-activation starts from 7d3ac69f and rebases cleanly onto origin/develop. README and FORK.md read; no AGENTS.md exists. First native Gradle baseline is configuring dependencies, with one worker and 1536 MiB daemon heap, compiler in-process. Thread dump shows Gradle HTTP dependency metadata resolution, not a test failure. Source confirms MuxHandler publishes ready in handlerAdded; AbstractMuxHandler succeeds activeFuture only at channelActive and fails it only at channelUnregistered.

| Resource | Creator / owner / closer | Terminal state and caller surface | Reentry / mid-operation close / restart | Public API proof |
|---|---|---|---|---|
| Parent TCP connection | Transport creates; host/network owns; Connection.close or transport closes | Completed close is terminal; no new child on closed parent | Connection callback may close synchronously; no persisted state | Existing callback-close cases, plus close-after-success control |
| Mux session publication | StreamMuxer.initChannel creates future; mux handler owns completion | Published only after lifecycle activation; close before activation fails readiness once | Close may occur during handler addition or activation; no persisted state | Public debug visitor observes activation and closes before delivery |
| Activation gate | AbstractMuxHandler owns future; activation or parent close settles | Success or exceptional completion is final; close cannot leave gate pending | Close listener must cover missing lifecycle events; no persisted state | Existing queued-before-close cases and preactivation-close case |
| Stream/controller futures | Host.newStream or Session.createStream creates; caller observes; mux/negotiation settles | Pending opens terminate on parent close; completed streams retain existing behavior | Many queued opens, completion callback reentry and repeated close | Original four cases, many queued opens from callback, reentrant completion opens |

MECHANISM TO FIX: A session is exposed before it is active, and a close while its handler is still being added can bypass channelUnregistered, leaving activeFuture and all newStream continuations pending. REPRODUCER: the inherited real TCP/Noise Host connection callback closes its own connection, then stream/controller futures must report ConnectionClosedException rather than timeout. No implementation change until baseline is confirmed 3/3.

Documents read: common.md, full handoff snapshot; README.md and FORK.md; lane165 brief, lane165 findings, lane160 findings; lane165/RESULTS.md and invariants/evidence on source branch; Testing architecture (including TDD, public API, deterministic reproduction), Philosophy required sections, Code Review red flags; fix-flakey-test skill. The brief says base main, while live fork and FORK.md say develop: use develop for all rebases and PR base. Full prior findings are retained outside checkout as r208-prior-r165-findings.md and r208-prior-r160-findings.md.

## 10:26 UTC — publication premise changed
OBSERVED: Snapshot-28 POM probe returns HTTP 200, whereas the brief recorded 404. No publication was performed by this lane. Fork develop already declares snapshot-28. Next-free-version probe is in progress; the final recommendation must use live evidence instead of reusing snapshot-28.

## 10:28 UTC — live coordinate check and first remote checkpoint
OBSERVED: Maven metadata lists snapshots through 30; probes 28 and 29 return 200, 31 returns 404. Lowest next snapshot is therefore 1.3.0-codexcoder21-snapshot-31 as of this check. Snapshot-28 is already the source build version. Open fork PRs use develop as base. The lane branch is pushed at the inherited reproducer head so a fresh lane can resume source work. Native Gradle has compiled production and test fixtures and is now compiling tests. No implementation change yet.
INFER: Publishing must re-probe 31 immediately before release because parallel work can claim versions. Brief publication premise is stale; do not recommend 28.

## 10:31 UTC — baseline progress
OBSERVED: First two native current-develop reproducer runs each fail 4/4 cases, with TimeoutException instead of ExecutionException(ConnectionClosedException), for both before-close and after-close opens on Yamux and Mplex. The third run is in progress. Full stacks and XML: r208-baseline1.log, r208-baseline2.log, r208-baseline1-xml/, r208-baseline2-xml/. First build took 7m9s including downloads/compilation; cached second took 1m28s. All implementation source is unchanged from origin/develop.
PLAN: Additional lifecycle scenarios are drafted outside checkout, awaiting the 3/3 gate. They use real isolated TCP/Noise hosts and public StreamMuxerDebug visitors; no mocks, private fields, sleeps, retries, or timing calibration.

## 10:32 UTC — baseline gate complete
OBSERVED: All three unmodified-source Gradle runs fail 4/4 cases, 12/12 failures total. Every failure is the same pending controller future timing out after the parent closed. XML for each run is preserved outside checkout. Existing reproducer is unchanged. New tests are now committed and pushed before implementation: activation before publication, close before activation with no publication, 32 queued opens and completion-callback reentry under repeated close, and successful-ping/normal-close control, parameterized for both muxers. Native baseline of these added tests is running with cached dependencies in offline mode.
INFER: This confirms the exact current-fork mechanism, independent of the prior published-runtime evidence. Implementation may proceed only after new fail-first outcomes are recorded.

10:33 UTC CHECKPOINT CORRECTION: Initial test commit failed because this fresh checkout had no Git author identity; its following rebase refused staged changes. The prior paragraph claimed push too early. Configured identity only in this checkout, committed the test unchanged, then fetched/rebased origin/develop cleanly and pushed. The native offline test command had already started configuration; no base drift or production change occurred. Subsequent build invocations use checked sequential subprocess calls so a failed preparatory step stops execution.

## 10:36 UTC — invariant fail-first gate
OBSERVED: Added public API tests run on unchanged production: 8 scenarios, 6 failures, 2 successful controls.
- [1] yamux: PASS (normal-close control)
- [2] mplex: PASS (normal-close control)
- [1] yamux: FAIL: org.opentest4j.AssertionFailedError: The mux lifecycle must activate before publishing its connection ==> expected: <true> but was: <false>
- [2] mplex: FAIL: org.opentest4j.AssertionFailedError: The mux lifecycle must activate before publishing its connection ==> expected: <true> but was: <false>
- [1] yamux: FAIL: org.opentest4j.AssertionFailedError: Unexpected exception type thrown, expected: <java.util.concurrent.ExecutionException> but was: <java.util.concurrent.TimeoutException>
- [2] mplex: FAIL: org.opentest4j.AssertionFailedError: Unexpected exception type thrown, expected: <java.util.concurrent.ExecutionException> but was: <java.util.concurrent.TimeoutException>
- [1] yamux: FAIL: org.opentest4j.AssertionFailedError: Expected java.util.concurrent.ExecutionException to be thrown, but nothing was thrown.
- [2] mplex: FAIL: org.opentest4j.AssertionFailedError: Expected java.util.concurrent.ExecutionException to be thrown, but nothing was thrown.
Full stacks: r208-invariants-before.log and r208-invariants-before-xml/. Additional installation-close readiness scenario now pushed and running before production edits. Existing test bodies, counts and bounds remain unchanged.

## 10:39 UTC — scope and review checks while final baseline compiles
OBSERVED: Actions permissions API confirms allowed_actions=local_only. This matches the brief's explicit local Gradle gate; no repository settings will be changed. New close-during-installation test is on remote commit 1fab4c8e and production remains unchanged. The eight earlier scenarios provide six intended failures and two passing normal-close controls. No background worker has been delegated.
INFER/IMPLEMENTATION PLAN: Keep publication out of handlerAdded, publish only after channelActive settles the activation gate, register physical-close completion during handler addition so missed lifecycle events cannot leave activation/readiness pending, and reject stream opens by actual parent open state as well as the existing terminal flag. This changes the owning mux lifecycle rather than deadlines or consumer behavior. Additional interface audit found P2PChannelOverNetty as the production closeFuture adapter, ConnectionStub/StreamStub and TestStream as fixtures; planned close observers use the existing Netty Channel closeFuture so no new P2PChannel calls or fixture contract changes are required.

## 10:40 UTC — installation baseline and correction
OBSERVED: Installation-close readiness test fails 2/2 on unchanged production, returning a successful session after physical close (expected ExecutionException). Full stacks: r208-install-before.log and r208-install-before-xml/. Thus all bug-targeting new scenarios fail first; normal-close control passes for both muxers. A sampled Kotlin stack during compilation showed normal JVM code generation, not a stalled test.
IMPLEMENTED: MuxHandler publishes only after channelActive completes its inherited activation step; physical close observers settle both activation and pending session-readiness even when unregister delivery is missed. AbstractMuxHandler checks actual parent open state for new child opens and activation; the existing terminal flag is volatile for callers on other threads. README states the public lifecycle contract. No timeout, retry, sleep, iteration reduction, consumer patch or visibility increase.

## 10:45 UTC — dedicated test review identifies a stricter row (d) check
OBSERVED: Formatting applied only to the four touched Kotlin files. Existing reproducer edits are formatting only: wrapping ConnectionHandler.create, braces around else-null and cleanup blocks, and splitting arguments. No assertion, wait, iteration count, transport or scenario changed. First corrected native run is compiling tests. TLS early-mux path was read: handshake completion installs the mux before firing channelActive; no synchronous readiness wait is introduced by delaying publication.
INFER/REVIEW FINDING: The current queued-open test waits after close, so it proves eventual failure but not the brief's stronger requirement that pending opens have settled when close completes. With activeFuture already successful, thenApplyAsync tasks are queued behind the current connection callback; the initial correction may leave their futures pending until the next event-loop turn. Before final verification I will strengthen that exact public callback scenario with a snapshot taken by the close completion, run it red if this concern is real, then settle pending creation futures from the physical-close listener if required. This is a gap to resolve inside this lane, not a follow-up.

## 10:47 UTC — first corrected gate and pending-close test
OBSERVED: First corrected run passes all 14 lifecycle scenarios (original four plus ten new parameterized cases), zero failures. Formatting and cleanup are checkpointed with the implementation on the lane branch; lane165 scratch logs are removed from the PR diff but remain on the original evidence branch and under r208-prior-lane165/.
- io.libp2p.core.MuxCloseDuringPublicationTest: 4 tests, 0 failures, 0.452 seconds.
- io.libp2p.core.MuxSessionActivationTest: 10 tests, 0 failures, 5.38 seconds.
The queued-open scenario now additionally snapshots both futures at close completion and asserts exceptional settlement then. This is a strengthening: all existing type checks, 32 opens, two closes and reentry checks remain. Its fail-first run against the initial correction is in progress; no completion-tracking change has been written yet.

## 10:50 UTC — pending-open invariant refinement before implementation
| Resource | Creator / owner / closer | Terminal and caller contract | Reentry / concurrent close / restart | Public proof |
|---|---|---|---|---|
| Pending child-creation result | AbstractMuxHandler creates and privately registers before scheduling; caller observes through Session/Host stream promise; completion removes registration | Physical parent close fails every still-pending result before close completion; any successful earlier creation retains normal semantics | Concurrent register-versus-close needs a second closed-state check; callbacks can reenter safely without a held lock; terminal results cannot schedule new child work; no persisted state | 32 queued public Session.createStream calls, two closes and a controller-completion callback reopening the closed parent; snapshot at close completion requires both futures exceptional |

INFER: A private concurrent set of pending result futures plus register-before-schedule and a post-registration closed-state check covers the close/registration gap. Completing results on physical close also propagates into the public stream/controller futures synchronously. The queued operation should check whether its result already settled before creating a child, preserving cancellation/terminal behavior. The fail-first close-completion run is still compiling; no such implementation has been written yet.

## 10:53 UTC — row (d) fail-first confirmed and completed
OBSERVED: The strengthened close-completion snapshot fails 2/2 with the initial correction: "Pending stream opens must fail before close completes ==> expected: <true> but was: <false>". Full stacks are in r208-close-completion-before.log and XML directory. This confirms the review finding, rather than just a hypothetical ordering.
IMPLEMENTED: Each child-creation result is now registered before its event-loop work is scheduled; completion removes the registration. Physical close fails all pending results synchronously. A second closed-state check covers registration racing a close that already drained the set. Queued work checks whether its result is terminal before creating a child; operation errors still forward to that caller's result. No locks wrap callbacks and no public API or fixture contract changes.
VERSION: Declared snapshot-31 in build.gradle.kts because 28–30 already exist and no open fork PR reserves 31; it was probed 404. This lane will not publish. Root version change is preparation for the supervisor's next publication.

## 10:59 UTC — implementation gate and caller-owned future review
OBSERVED: Final pending-open correction is compiling the test sources for ten isolated Gradle test tasks, each using a 128 MiB test JVM. Root version 31 forced rebuilt module artifacts; no test result is claimed until XML exists. The source version bump, pending registry and close-completion assertion are committed locally; prior passing correction and fail-first strict test are already remote.
INFER/TEST REVIEW FINDING: MuxHandler currently returns the future produced by forwardException(controller). A caller can cancel or manually complete that observer future before physical close, potentially preventing its forwarding callback from ever completing the controller. I will exercise cancel, exceptional completion and successful-null completion through the public Session API for both muxers before changing that code. This is the caller-mutation column of the existing stream/controller invariant row, not a new external service task.
RELEASE COMPOSITION NOTE: git merge-base --is-ancestor confirms published-runtime source 62580dfc and current origin/wip/uO-single-parent-close are NOT ancestors of fork develop. The latter is still open as https://github.com/CodexCoder21Organization/jvm-libp2p/pull/39 . Supervisor must compose any still-required published-runtime fixes before publishing snapshot-31; this PR follows the brief's develop base and does not silently import another open PR. FORK.md's published-coordinate example is stale (25) and its free-version HTTP status example says 401; live free POM 31 returned 404.

## 11:02 UTC — ten-run gate passed, caller-mutation baseline started
OBSERVED: Pending-open lifecycle implementation passes all 10 isolated test tasks on 128 MiB test JVMs, 14 cases each = 140/140 passing. All ten copies of the strict close-completion assertion pass. Full log r208-fixed-ten.log; XML r208-fixed-repeat1-xml/ through r208-fixed-repeat10-xml/. Passing head is 8ed843f4, pushed to the lane branch.
- Repeat 1: 14 tests, 0 failures.
- Repeat 2: 14 tests, 0 failures.
- Repeat 3: 14 tests, 0 failures.
- Repeat 4: 14 tests, 0 failures.
- Repeat 5: 14 tests, 0 failures.
- Repeat 6: 14 tests, 0 failures.
- Repeat 7: 14 tests, 0 failures.
- Repeat 8: 14 tests, 0 failures.
- Repeat 9: 14 tests, 0 failures.
- Repeat 10: 14 tests, 0 failures.
New public caller-mutation cases cancel, fail or successfully complete the returned stream future before parent close, for both muxers. They require the untouched controller to report ConnectionClosedException after close while preserving the caller-owned stream future state. They are committed and baseline is running before an error-forwarding change.

## 11:08 UTC — caller-mutation baseline and owning-future correction
OBSERVED: Six of six public caller-mutation cases fail on the previously 140/140 passing lifecycle correction: after cancel/fail/success of the stream view, the controller remains pending after physical parent close. Full stacks: r208-caller-mutation-before.log and r208-caller-mutation-before-xml/. No timing wait is used for the verdict; the snapshot is taken at close completion.
MECHANISM: MuxHandler returned the forwardException observer future itself. Completing that future as a caller stops its error-forwarding action, leaving the sibling controller without the source creation failure. CORRECTION: observe the privately owned mux-channel creation future and hand the caller a separate mapped stream future. Caller mutation can no longer suppress the private observer. README scopes that behavior to Yamux/Mplex. The other forwarding call in QuicTransport uses its separate native stream-creation path; the brief targets the shared Yamux/Mplex handler, and no QUIC claim/change is made.
TEST INVENTORY: The six new cases add only assertions and public future mutations; the inherited four scenarios remain formatting-only. Strong queued-open test adds an assertion before its unchanged exact cause/type checks, with the same 32 opens, 2 closes and 5-second bounds. Every strengthened scenario was demonstrated failing before the corresponding change.

## 11:14 UTC — final 10/10 proof
OBSERVED: Exact head 1b4d3a10782f448cec5de1e7af35cc859bd7f518 passes ten isolated 128 MiB JVM runs, 20 tests each, 200/200 total, 0 failures/errors/skips. This includes all original cases and all final strengthened/new cases. r208-final-ten.log and r208-final-repeat1-xml/ through r208-final-repeat10-xml/ contain the evidence. Snapshot-31 POM re-probe at 11:12 still returns 404.
- Final repeat 1: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 2: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 3: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 4: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 5: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 6: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 7: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 8: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 9: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
- Final repeat 10: {'tests': 20, 'failures': 0, 'errors': 0, 'skipped': 0}
Implementation is pushed. Fresh fetch/rebase precedes the one broader package run now in progress: all mux (including Yamux/Mplex), etc.util.netty.mux, core, network, host, TCP, AbstractChildChannelCloseTest and PlainNettyTransport classes. Full project suite is not run, following the lane scope/shared-machine rule.

## 11:16 UTC — separate review passes at final head
Test-comprehensiveness review (self, separate pass; no nested agents per brief): Reviewed the tests against Testing architecture and the invariant table. Real isolated TCP/Noise hosts, port 0, Host/Connection/Session APIs and public debug visitors exercise both muxers. Fail-first records cover early publication, preactivation/installation close, queued opens, reentrant completion, repeated close and caller-owned future mutations. Healthy ping/normal-close controls passed before and after. The review found two gaps (close-completion timing, caller-mutated forwarding observer); each was reproduced red, corrected, then included in the 200/200 final gate. Bounds remain 5 seconds and 32 queued opens; no existing scenario was weakened. No remaining test review findings.

Adversarial implementation review (self, separate pass): Reviewed publication/activation ordering through Negotiator, ProtocolSelect, ConnectionBuilder, Noise finalization and TLS early-mux activation. No readiness wait cycle is added. Context is installed before physical-close observation; completed close promises invoke the same failure path. The pending-result set is concurrent because opens can originate off-loop; results register before work, settle/remove on every completion, and a second closed-state check covers close-before-register. Physical-close iteration holds no callback lock, reentrant opens reject the closed parent, terminal queued results skip creation, and asynchronous exceptions/rejected submissions still forward to the owned result. The controller observes the owned source future independently of the caller-mutated stream view. Existing child teardown and post-activation behavior stay in place. The volatile terminal flag and actual parent state guard cross-thread opens. No persisted state or restart migration exists for these per-connection resources. No remaining code review findings at 1b4d3a10782f448cec5de1e7af35cc859bd7f518; broader package verification is still running.

## 11:19 UTC — broader package gate failure; base comparison in progress
OBSERVED: Broader package run completes 485 tests: 482 pass, 3 fail, 0 errors, 0 skips. Failures are PlaintextTcpTest.multiplePingChannelsOnTheSameConnection (controller get, HostTranportsTest.kt:240), PlaintextTcpTest.echoOverSecureConnection (controller get, :285), and ChildReadPauseTcpTest.offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume mplex (controller get, :146). Full stacks and XML: r208-packages.log and r208-packages-xml/. The final targeted 20-case/200-repeat gate remains clean. These package failures are NOT claimed unrelated.
NEXT EXPERIMENT: Native run of the exact failed scenarios on unchanged fork develop 3e88aeef, in the same checkout detached at that commit, with the same Gradle/JVM settings. The tested/pushed correction stays safe on fix/lane208-mux-session-activation at 1b4d3a10. This discriminates an introduced plaintext ordering problem from a pre-existing failure. Deadline remains stop-new-work 11:35 / hard wall 11:50. No PR has yet been opened; broader gate is not green.

## 11:24 UTC — framing evidence from the broader failure
OBSERVED: In the failed PlaintextTcpTest connection 34270→4002, the client writes Mplex OPEN(16) and the substream multistream announcement at 11:16:33.362. Server at 11:16:33.363 reports Mplex CLOSE(resp), id=2, 47-byte payload whose bytes begin "multistream/1.0.0\n/mplex/6.7.0\n" followed by the first child frames. The encoded root announcement prefix (0x13 then '/') has been treated as an Mplex header/length. There is then no progress until cleanup closes the connection 5.009 seconds later. Same shape appears for echo connection 34306→4002. Captured class output: r208-failure-output-io.libp2p.core.PlaintextTcpTest.log; first malformed frame near lines 830–843. This is a packet/protocol-transition observation, not evidence that the host was overloaded.
INFER: Coalesced root mux negotiation and early child data appear to reach the Mplex decoder through the wrong framing stage. The exact source mechanism is not yet established, and introduced-versus-pre-existing status is still awaiting the unchanged-base experiment. No timeout, retry, skip or source change has been made to hide these failures.

## 11:31 UTC — base experiment result and final status
OBSERVED: Unchanged fork develop 3e88aeef initially passes all four targeted scenarios once. A registered ten-run diagnostic then executes 40 cases: 39 pass, 1 fails (PlaintextTcpTest.echoOverSecureConnection in repeat 9, same TimeoutException at HostTranportsTest.kt:285). Its server log contains the identical malformed Mplex CLOSE(resp), id=2, 47-byte payload beginning root multistream/mplex negotiation text. Thus the echo failure and this framing shape demonstrably predate this fix. The multiple-ping and child-read-pause failures have not independently failed on base; their status remains unclassified. No claim is made that all three package failures are unrelated, or that the patch cannot affect their frequency. Full logs: r208-base-package-failures.log, r208-base-ten.log, r208-base-repeat1-xml/ ... repeat10, r208-base-failed-repeat9-output.log.

The feature branch was restored and freshly fetched/rebased on develop; the rebase was a no-op, preserving exact tested head 1b4d3a10782f448cec5de1e7af35cc859bd7f518. The initial and final package/test evidence therefore still applies to this exact head. Code diff has six source/doc files and no investigation/build artifacts. Publication probe 31 still returns 404 at 11:31 UTC. The source PR will be non-draft as requested, with the broader failing gate explicitly stated. It is not approved for merge/publication by this lane. The broader validation item remains incomplete; next investigator must force coalesced plaintext/root-mux negotiation and early child data, compare both heads and trace why root protocol bytes reach MplexFrameCodec. Do not widen a timeout or rerun to green.

## Supervisor publication procedure (NOT executed)
Next reserved coordinate: community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-31. Re-probe before publication. Compose required existing runtime fixes first: source 62580dfc of published snapshot-27 is not an ancestor of develop; preserve the parent-close work on https://github.com/CodexCoder21Organization/jvm-libp2p/pull/39 when building the approved combined head. Resolve the broader test failures before release.

The fork is built with Gradle, then staged from Maven local and uploaded with the kotlin.directory publishing CLI, not the inherited Cloudsmith Gradle publication target. FORK.md documents this sequence:

```bash
./gradlew :libp2p:publishToMavenLocal -PmavenArtifactId=jvm-libp2p
release_version=1.3.0-codexcoder21-snapshot-31
release_m2="$HOME/.m2/repository/community/kotlin/libp2p/jvm-libp2p/$release_version"
release_stage=$(mktemp -d)
cp "$release_m2/jvm-libp2p-$release_version.jar" "$release_m2/jvm-libp2p-$release_version.pom" "$release_stage/"
/home/helena/bin/coursier launch community.kotlin.maven.artifact.publishing:community-kotlin-maven-artifact-publishing:0.0.5 -r https://kotlin.directory -- --artifact-dir "$release_stage" --api-url https://api.kotlin.directory/upload --force
```

Only stage main JAR and full transitive POM; exclude Gradle .module metadata. No Maven artifact, merge, enqueue or deployment was performed in this lane.

## PR artifact
https://github.com/CodexCoder21Organization/jvm-libp2p/pull/48 — OPEN, non-draft; head 1b4d3a10782f448cec5de1e7af35cc859bd7f518. Evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane208-mux-publication-20261015/lane208-evidence .

## 2026-10-15 11:36 UTC — terminal checkpoint
PR https://github.com/CodexCoder21Organization/jvm-libp2p/pull/48 is OPEN/non-draft at 1b4d3a10782f448cec5de1e7af35cc859bd7f518. Source: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/lane208-mux-session-activation . Final target proof 200/200. Broader validation INCOMPLETE: 482/485; echo timeout and malformed framing proven on unchanged base; other two failures unclassified. No merge, publication or deploy.
Cleanup: corrected the initial process filter, which matched its own shell source, to inspect the executable instead. ps -eo pid,args -ww confirms no own Java/test workers. Clean checkout deleted; no kompile cache created. Full process listing stays local. Source/evidence are pushed; no background task remains.
Brief audit: reproduction 3/3 DONE; invariants and fail-first cases DONE; owning correction/10-run gate DONE; broader green gate NOT DONE; non-draft PR/version/publishing procedure DONE; durable evidence/cleanup DONE. Next: force coalesced plaintext/root-mux negotiation and early child data, trace root bytes reaching MplexFrameCodec, classify multiple-ping and child-read-pause failures, fix deterministically and make broader verification green. No new source work was started after the cutoff.

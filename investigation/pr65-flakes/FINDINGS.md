# Lane pr65-flakes

Started 2026-10-22 04:10 UTC. Deadline: checkpoint and exit by 05:20 if not at push gate; absolute 05:35 UTC.

Plan:
- [in progress] Read both tests, traces, owning implementations, and compare fork develop b01e08e5.
- [pending] Reproduce each failure reliably, document exact mechanism and lifecycle invariants, add deterministic public-path regression tests.
- [pending] Apply fixes; verify red/green/reverted-red/restored-green; checkpoint passing steps to wip/pr65-flakes-ckpt.
- [pending] Run both complete classes and one full :libp2p:test suite; record first-attempt counts.
- [pending] Fetch/rebase on assigned integration branch base, rerun targeted tests, push integration branch once; append evidence to existing draft PR; stop processes.

OBSERVED: Read CONTRACT.md completely (Handoff usage design, unrelated to specific lane). Specific lane overrides generic usage-tracking/main branch instructions: work on integrate/upstream-develop-2026-10, update https://github.com/CodexCoder21Organization/jvm-libp2p/pull/65.
OBSERVED: Read TESTING.md sections Test-Driven Development and Tests Must Be Hermetically Sealed via GitHub API. Read named first-failure traces: GossipApiTest.java:92 and QuicServerTestJava.java:1073 both TimeoutException.
OBSERVED: Fresh clone is /code/workspace/lanes/pr65-flakes/jvm-libp2p; initial disk free 16 GiB.
INFER: No root cause established yet. No code changes or builds yet.

## Initial source trace
Current commit: fe273ee56a6c9917877ba2b46e71ef898e904b3a.
OBSERVED: GossipApiTest and GossipRouterBuilder are byte-identical to fork develop b01e08e5. P2PService.submitOnEventThread implementation is also unchanged; upstream adds outbound backpressure in PeerHandler, which this test constructs but never uses to send. Gossip test awaits two queued event tasks at line 92; neither uses network I/O.
OBSERVED: Corrected QUIC location: line 1073 awaits the outbound dial, BEFORE inboundConnection.get at line 1074 and before the exception handler is exercised. QuicServerTestJava.connectionLevelQuicExceptionIsHandledAndCleansUpOutboundResources does not exist on plain fork develop. The merge adds no-peer-id dialing, per-session certificate identity, QUIC configuration, native Netty updates, and switches old EpollDatagramChannel selection to NioDatagramChannel.
OBSERVED: Fork default is develop, no main branch. An ordinary rebase attempted to flatten merged history; aborted immediately. Rebase --rebase-merges origin/develop completed with identical head fe273ee5 and no tree changes. Future builds refresh onto the assigned PR branch and verify develop ancestry instead.
OBSERVED: Initial unmodified two-class run is compiling via boxrun (Gradle repository has no scripts/test.bash); no test verdict yet. One tracked timeout bounds it to 900 seconds including slot wait. nproc=8; no artificial workload used.
INFER: Need distinguish executor/handshake startup delays from a missed completion; no production edit justified yet.

## 04:14 UTC experiments prepared
Current commit: fe273ee56a6c9917877ba2b46e71ef898e904b3a.
OBSERVED: Added exploratory JUnit callers (no implementation edits): Gossip invokes the exact original test 256 times with eight genuine callers; QUIC invokes the exact original test 32 times with four genuine callers. Failures are collected with full traces and asserted absent; original assertions and waits remain unchanged. These are diagnostic probes until they identify a stable condition, not claimed regression coverage.
OBSERVED: Narrow JFR settings collect execution samples, monitor waits, thread parking and GC only. No process environment, thread dump or heap dump is read. All probe resources have bounded cleanup and overall Gradle command deadlines.
INFER: The first full run could expose cold initialization or global state left by preceding Java classes; probes will also run the earlier AutonatTestJava/HostTestJava/RelayTestJava classes if isolated classes do not reproduce.

## 04:17 UTC build progress
Current commit: fe273ee56a6c9917877ba2b46e71ef898e904b3a.
OBSERVED: Initial run has completed main and test-fixture compilation and is preparing test compilation. Still no per-test result lines, so no pass counted. Diagnostic source copies are installed but excluded by initial --tests filters. No existing test or implementation has changed.
OBSERVED: Full-suite ordering evidence puts AutonatTestJava, HostTestJava and RelayTestJava immediately before GossipApiTest. The QUIC failure is inside a class whose other 22 executed methods passed; the whole full-suite report counted 1385 passed, 5 failed, 3 pre-existing skipped.
INFER: Both waits may need separate mechanisms; their shared TimeoutException type is not evidence of a shared defect.

## 04:20 UTC first unmodified classes result
Current commit: fe273ee56a6c9917877ba2b46e71ef898e904b3a.
OBSERVED: First unchanged class run PASS: 25 passed / 0 failed / 1 pre-existing skipped; Gradle exit 0, 8m11s including fresh compilation. Both named failures passed. Exact log at jvm-libp2p/out/initial-classes.log; XML saved at jvm-libp2p/out/initial-class-results/.
OBSERVED: Started a distinct workload, not an unchanged rerun: the preceding Autonat/Host/Relay Java classes, original GossipApiTest, 256 concurrent exact Gossip test invocations, and 32 concurrent exact QUIC exception test invocations. Execution recording includes only samples/locks/parking/GC and slow class loads. Runtime is bounded to 900 seconds overall.
INFER: Isolated pass proves neither root cause nor fix. No implementation change made.

## 04:23 UTC concurrent/order first result
OBSERVED: Integration experiment PASS: 12 JUnit scenarios passed / 0 failed / 0 skipped, 2m34s, including 256 exact Gossip invocations (eight concurrent callers) and 32 exact QUIC invocations (four concurrent callers). All original assertions/timeouts preserved. The preceding Autonat/Host/Relay cases also passed. Evidence at jvm-libp2p/out/concurrent-probe-first.log and concurrent-first-results/; narrow execution recording concurrent-probe.jfr.
INFER: No reliable failure reproduced. This cannot justify an implementation change. Next: baseline source/runtime comparison and increased genuine callers if needed.

## 04:24 UTC checkpoint and profile evidence
Current commit: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/9736882975dd39ae1f08245a8637cf93f0fa832d.
OBSERVED: Passing exact-path probes committed and pushed ONLY to https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/pr65-flakes-ckpt . Existing PR branch is untouched.
OBSERVED: Profile includes 600 execution samples, 65 monitor entries, 123 slow class loads and 43 GC events. During first Gossip calls, router threads load protobuf builders and score classes, then AssertJ classes at GossipApiTest.java:72; multiple threads wait 80–95 ms inside Assertions.assertThat. The entire assertion block runs on the timed router event task. No failed invocation occurred in this recording, so this is a hypothesis about the one-second deadline, NOT proof of the reported failure's cause.
INFER: Cold test assertions and message construction are charged to the same one-second future wait as router work, but changing that arrangement without a failing reproducer would still be a guess. No such edit made.
OBSERVED: Increasing only newly added exploratory workload: 2048 Gossip invocations with 64 concurrent callers and 256 QUIC invocations with eight concurrent callers, fresh JVM, same original timeouts/assertions. No artificial work or machine-slowing process. QUIC concurrency remains bounded because each caller owns real channels/event groups.

## 04:26 UTC baseline contract difference
Current checkpoint: 9736882975dd39ae1f08245a8637cf93f0fa832d.
OBSERVED: Plain develop QuicTransport.kt:234 dereferences addr.getPeerId()!! after the handshake. The reported new test deliberately dials an address without /p2p, so plain develop cannot satisfy the identical test: its expected outcome is a post-handshake NullPointerException, rather than the integration branch's successful peerless dial. A runtime baseline probe is prepared to confirm this distinction. The original QUIC exception-cleanup method and handler are absent from develop.
OBSERVED: The larger experiment is currently waiting for a boxrun slot; its log is empty, so it has verified nothing yet. Deadline remains 900 seconds from its command launch.

## 04:27 UTC checkpoint detail
OBSERVED: Exact checkpoint SHA is 9736882975dd39ae1f08245a8637cf93f0fa832d. The increased caller counts are uncommitted diagnostic edits while the next first attempt waits at boxrun. A rebase with auto-stash preserved those edits and confirmed the PR branch base is unchanged before compilation.
OBSERVED: Kubo v0.34.1 archive downloaded into this lane and matches the repository's SHA-256 42045802fe60c64fb01350bc071190c534d600fe269759c06e27e22b2012fd3e. Its binary is extracted under ignored out/kubo-local; no daemon has been started and no root installation performed. This prepares the real dependency for the single final full suite if the fix gate is reached.

## 04:29 UTC progress
Plan audit:
- [completed] Read traces/tests/owning source and compare baseline source contracts.
- [in progress] Reliably reproduce each reported timeout and establish a deterministic condition.
- [pending] Baseline runtime probe; no reliable original failure yet.
- [pending] Fix, reversal, final class/full-suite and single PR push gate. None can honestly be claimed before reproduction.
OBSERVED: Checkpoint remains 9736882975dd39ae1f08245a8637cf93f0fa832d on wip/pr65-flakes-ckpt. The larger probe waited at boxrun from 04:25 until slot 1 was acquired at 04:27:50; Gradle preparation is running, with no per-test lines or pass claimed. Prepared baseline probe and verified local Kubo binary are independent work completed while waiting.
INFER: Static evidence makes test-fixture cold work a candidate for Gossip, but the original two failures remain unexplained. No production edit, timeout change, assertion change, publication, deployment, merge or CI rerun performed.

## 04:34 UTC reproduced Gossip mechanism; QUIC remains unresolved
Current checkpoint: 729cc4ce (full SHA recorded next checkpoint).
OBSERVED: Larger first attempt: 1 JUnit scenario passed / 1 failed / 0 skipped. Gossip aggregate fails on an assertion after 64/2048 original method invocations raised the exact TimeoutException at line 92. All 256 QUIC original invocations passed with eight concurrent callers. Logs and complete XML/stdout are saved in out/amplified-first-results/ and out/amplified-probe-first.log.
OBSERVED: The failed Gossip recording shows newly started router threads loading protobuf/score/Kotlin classes and then contending inside AssertJ at GossipApiTest.java:72; each original test puts message construction AND assertions into the one-second wall-clock task. The scenario's first burst fails, while later warmed calls succeed. The router API itself has no one-second completion guarantee.
MECHANISM TO FIX (test fixture): GossipApiTest.java:46-92 uses a newly started real scheduled executor for a pure cache scenario, so cold message/assertion initialization is included in its wall-clock completion wait. This violates the required controlled-clock test setup. Fix the owning fixture by supplying the repository's real ControlledExecutorServiceImpl and TimeControllerImpl; preserve every original assertion, message, and the get(1, SECONDS) call.
DETERMINISTIC REPRODUCER: a new test executes the original public test method and uses JDK Flight Recorder ThreadStart events (no stacks) to assert it creates no wall-clock GossipRouter worker. Old fixture always creates one. This forces/checks the named condition directly, independent of a host's speed or concurrency count. It will run red before the fixture edit.
INVARIANT TABLE before edit:
| Resource | Creator / owner / closer | States / caller behavior | Public scenario |
| --- | --- | --- | --- |
| Scenario executor and clock | Each test creates and owns its controller/executor; finally closes executor | All router work is serialized at controlled time; no autonomous worker or elapsed wall-time dependency | ControlledClockTest calls the original scenario; original assertions unchanged |
| Message snapshots/cache | Original scenario owns messages; only event task inspects and clears them | First delivered message has canonical id; duplicate is filtered without canonical calculation | Exact original scenario plus 2048 independent callers |
| QUIC transports | Existing scenario owns client/server and finally closes them | Dial must finish before exception-close assertions; dedicated UDP resources close after error | Original class and 256 exact concurrent calls (all pass; no fix inferred) |
INFER: Evidence supports a fixture defect for Gossip, not a demonstrated FastIdSeenCache implementation defect. QUIC's reported first-attempt timeout still has no reproduced mechanism, so no change is justified there.

## 04:37 UTC deterministic first red
OBSERVED: Controlled-clock regression first attempt is 0 passed / 1 failed / 0 skipped, exit 1, 1m58s. Failure is an assertion: expected no autonomous GossipRouter workers, actual [GossipRouter-event-thread-0]. The original scenario completed, so this failure is the exact named fixture condition, not a compile error or an elapsed-time assertion. Full trace/XML saved in out/controlled-red-results/.
OBSERVED: No fix applied yet. Regression checkpoint is being pushed to wip/pr65-flakes-ckpt, then exact b01e08e5 is checked out in this same lane for runtime comparison. Baseline run includes the 2048-caller Gossip probe, the deterministic fixture regression, and a peerless QUIC dial probe which expects the baseline's post-handshake NullPointerException.
EXISTING TEST EDIT PLANNED: GossipApiTest.java:testFastMessageId only gains explicit ControlledExecutorServiceImpl/TimeControllerImpl and currentTimeSupplier wiring. Every existing assertion, message, one-second wait, and finally cleanup stays unchanged. No other original test will be edited. No new public library behavior, so README public API text has no new contract to document.

## 04:41 UTC review while baseline compiles
Current HEAD: b01e08e5e40daecd68a529bc4c07a03999397657 (detached runtime comparison). Integration checkpoint: 287b6c7ca31e48803ebd0d2df9f3101edaafe969.
OBSERVED: Baseline main compilation still running; no result claimed. Local Kubo test-only repo initialized with the test profile, no bootstrap peers, loopback-only TCP/QUIC ports, and no daemon yet. Ports 4001/5001 were not accepting connections at setup time.
INDEPENDENT TEST REVIEW (separate pass): Deterministic regression records only JDK ThreadStart names with stacks disabled, invokes the unchanged public scenario, and closes recording/deletes its private temporary file in finally. Thread startup is committed before the original scenario's future returns; the assertion therefore observes the actual worker rather than sampling a scheduler race. The original cache assertions remain the behavior test; the added scenario checks the fixture's timing/resource condition. Each invocation owns separate router, queue, cache and clock state. The concurrency probes intentionally invoke that public scenario to test the fixture itself; they do not share its mutable data.
ADVERSARIAL REVIEW (separate pass): ControlledExecutorServiceImpl uses the real TimeController task queue. Tasks due now run serially, including zero-delay validation continuations, while later refresh tasks stay queued until controlled time advances. This preserves the original event ordering and all canonical-id assertions. Its shutdownNow opens no thread or port and there is no external owner retaining the controller. The replacement builder default executor has not scheduled anything before it is replaced, so no unused live worker is left behind. No public library code, shared interface, persisted record, timeout, limit, iteration count or existing assertion changes.

## 04:45 UTC exact develop comparison completed
Current baseline tested: b01e08e5e40daecd68a529bc4c07a03999397657.
OBSERVED: Baseline first attempt: 2 passed / 1 failed / 0 skipped. Deterministic Gossip fixture regression fails on the same assertion and same one worker. The 2048-call Gossip probe passes in this run (it ran after the fixture regression had warmed the code). This confirms the uncontrolled fixture condition is inherited, while timing incidence still depends on cold startup.
OBSERVED: Peerless QUIC baseline probe passes by observing the expected NullPointerException from QuicTransport.kt:234 after the handshake. Therefore the exact new integration test does not have the same supported behavior on develop; its no-/p2p dial was unsupported there. This is a contract comparison, not a reproduction of the integration timeout.
OBSERVED: Saved complete baseline XML/logs under out/develop-baseline-results/ and develop-baseline-first.log. Removed only the temporary baseline source copies, returned to integration checkpoint 287b6c7ca31e48803ebd0d2df9f3101edaafe969 and applied the three-line clock/executor fixture wiring. Every original assertion and get(1, SECONDS) remains byte-for-byte unchanged. First fixed run also formats Java and exercises both original classes, the deterministic clock regression, and all 2048 Gossip callers.
INFER: Baseline source/runtime evidence supports an inherited Gossip fixture defect. The integration QUIC timeout remains unresolved after 288 concurrent exact calls passed; no QUIC implementation edit is made.

## 04:49 UTC qualification preparation
Current integration checkpoint: 287b6c7ca31e48803ebd0d2df9f3101edaafe969; five-line existing-test diff (two imports, three wiring statements) is the only intended original-file edit.
OBSERVED: Fixed run acquired box slot 2 at 04:47:23; preparation is running. No fixed test result yet. Prepared the single full-suite run's narrow 150 MB-capped execution recording and per-test UTC start markers to locate a repeated QUIC failure if one occurs. No machine-load process or external test service is used.
INFER: The 64 failures likely belong to the cold initial burst; the first diagnostic did not label caller iterations, so their exact iteration positions were not observed. A reversal may add iteration labels to confirm that detail. The deterministic worker-creation condition is already proven independently of timing on both integration and develop.

## 04:54 UTC final qualification sequence
OBSERVED: Java formatting affected only the two new diagnostic probes; the deterministic regression already matches formatting. No pre-existing source except the five added GossipApiTest setup/import lines changed. Main and fixture compilation have completed; test compilation is next.
PLAN: Once first fixed class run passes, checkpoint it; temporarily restore original GossipApiTest; rerun the deterministic clock regression (and original caller probe) red; restore HEAD. Then fetch/rebase and run the restored targeted gate followed by the sole full suite in ONE Gradle invocation, using a temporary init-defined Test task with its own results directory. The normal :libp2p:test task has no filter, must run after the restored targeted task, and will not start if that task fails. This avoids a duplicate Gradle preparation while preserving separate test JVMs and first-attempt counts. Head/source remain unchanged between restored qualification and full suite.

## 04:56 UTC first fixed pass; durable checkpoint
OBSERVED: First fixed attempt PASS: 27 passed / 0 failed / 1 pre-existing skipped, Gradle exit 0, 8m30s including integration recompilation and Java formatting. Both complete original classes passed; deterministic controlled-clock regression passed; 2048 exact Gossip invocations passed with zero failures. Full XML/logs at out/controlled-green-first-results/ and controlled-green-first.log.
OBSERVED: Committing/pushing the passing fixture fix to wip/pr65-flakes-ckpt before reversal. Existing-test edit is precisely five added lines in GossipApiTest.java (two imports; TimeControllerImpl creation; controlled executor; current-time supplier). No existing assertion, message, timeout, limit or count changed. Two new probe files only received formatting.
OBSERVED: Temporarily restoring exact pre-fix GossipApiTest source from 287b6c7c and rerunning the deterministic clock regression plus 2048-call probe. This is the required controlled reversal; restore HEAD after collecting the first result. The PR branch is still untouched.
INFER: Fixed cache scenario is now driven by controlled time and creates no autonomous router worker; the 2048-call check confirms its original behavior assertions remain intact. QUIC original class passed again without any QUIC code change, so its initial timeout still has no demonstrated mechanism.

## 05:02 UTC queue and exact source state
Current passing checkpoint: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/0e68f1bfd000884bc0a6dca03b0ff7e8c63a5dbc on https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/pr65-flakes-ckpt .
OBSERVED: Reversal has waited for a box slot since 04:56 and has no output/result lines. Its 600-second overall deadline is tracked. WORKING TREE INTENTIONALLY HAS OLD GossipApiTest.java (five-line reverse diff); restore from HEAD after this run, or before resuming normal qualification if interrupted. The complete passing fixture fix is already remote.
OBSERVED: Owned Kubo daemon started at 04:56:57 with --routing=none and a test-profile repo, loopback-only TCP/QUIC 4001 and API 5001, no bootstrap peers. Readiness response's public peer ID matches this lane's repo. PID and start time recorded in out/kubo-owned.pid and owned-java-pids.json for bounded numeric-PID cleanup.
INFER: Shared capacity is delaying the final sequence. The required fetch/rebase plus restored targeted pre-push gate must pass before 70 minutes; as in the preceding lane, the mandatory full-suite qualification may finish afterwards within the lane's explicit 85-minute hard limit. No PR push before full results. If the targeted gate is not reached by 05:20 UTC, stop at checkpoint and report exactly what remains.
PLAN REVIEW: Retire the newly added QUIC diagnostic before any PR push (its original random-port helper is useful for exact-path investigation but should not add another unsealed random-port scenario to the permanent suite). Its passing source remains in the remote evidence checkpoints. Keep the deterministic Gossip regression and 2048-call controlled scenario; no pre-existing test is removed or weakened. QUIC original failure still needs evidence and remains explicitly unresolved.

## 05:07 UTC reversal red; restored full qualification starting
OBSERVED: Reversal first attempt completed with 1 passed / 1 failed / 0 skipped, Gradle exit 1, 2m03s after slot acquisition (the preceding queue wait was about eight minutes). The deterministic clock regression again fails on the same one-worker assertion. The warmed 2048-call diagnostic passes; this does not change the deterministic result. Full reversal XML/logs saved in out/controlled-reversal-first-results/.
OBSERVED: Restored the previously passing five-line fixture fix from HEAD. Renamed only the new Gossip caller probe to GossipApiConcurrentTest (same 64 callers × 32 iterations; same assertions). Retired only the newly introduced QUIC research source, which reused the original random-port helper; permanent suite should not gain that unsealed duplicate. Original QuicServerTestJava and every pre-existing test remain unchanged. The research source/results remain available at checkpoint 0e68f1bfd000884bc0a6dca03b0ff7e8c63a5dbc.
OBSERVED: Committing this final source layout and checkpointing it before the restored targeted + full run. The temporary init-defined verifyRestoredGossip task runs original GossipApiTest, controlled-clock regression, 2048 callers, and exact original QUIC cleanup method; :libp2p:test then runs without a filter in a separate test JVM. If the targeted task fails, normal Gradle failure handling prevents the full task from starting. Full command is bounded to 1500 seconds, before the 85-minute hard stop.
INFER: Gossip has now shown deterministic old-red, fixed-green, and reversed-red. The restored gate is still pending. QUIC's initial timeout remains unresolved; no guessed implementation change or success claim is made.


## 05:09 UTC restored gate PASS; full suite running
Final source head: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/608b7f3415e625146814a013baadf021ed49b464 . Remote checkpoint updated; PR branch unchanged.
OBSERVED: Fetch/rebase on actual integration branch succeeded, and develop is confirmed an ancestor. Restored targeted task passed all five scenarios (5 passed / 0 failed / 0 skipped): original Gossip class, deterministic clock regression, 2048 exact controlled scenario calls, and original QUIC exception-cleanup method. This completes old-red / fixed-green / reversed-red / restored-green. This targeted pre-push gate was reached before the 70-minute threshold. Source HEAD is clean and fixed.
OBSERVED: Normal unfiltered :libp2p:test has started its single full first attempt in a separate test JVM, --max-workers=2. No source edits or pushes to the PR branch during qualification. Full profile and method start times are being captured without thread or heap dumps. Full suite already passed the original Gossip method and the 2048-call scenario; real owned Kubo ping is now running.
OBSERVED: Final net diff from fe273ee5 is three test files only: 89 added lines, no production source change, no deletion or weakening of pre-existing tests. New QUIC research probe is absent from final net diff.

## 05:14 UTC additional sealed QUIC experiment
OBSERVED: Full suite has passed the original QUIC cleanup method and both Kubo cases; its result is not yet complete. The original timeout remains unexplained. No additional original logs have been supplied through the pending optional question.
OBSERVED: Prepared a separate scratch-only Java diagnostic using the same public listen/dial/connection-error/cleanup path and assertions, OS-assigned port 0, 16 bounded callers × 32 iterations (512 calls), original five-second operation budgets, and a narrow execution recording. It compiles into out/quic-sealed-classes and reads this lane's existing published-dependency/compiled-module classpath; it never writes the checked-out source or normal test output. All failures and cleanup failures are printed and counted; an assertion fails the process if any occur. Overall command is bounded to 480 seconds including its box slot.
INFER: This removes random-port selection as an input while increasing genuine concurrency. A successful run is additional evidence only, not a claimed explanation or fix for the original QUIC failure. The full-suite source remains exact clean head 608b7f3415e625146814a013baadf021ed49b464.

## 05:23 UTC full first attempt complete; all failures preserved
Final full-tested source head: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/608b7f3415e625146814a013baadf021ed49b464 .
OBSERVED: Single unfiltered full first attempt finished: 1386 passed / 6 failed / 3 pre-existing skipped, 1395 total, --max-workers=2. Gradle exit 1, 12m08s including restored targeted gate/preparation. Both named lane cases and both real Kubo cases passed. All new Gossip regression cases passed. Structured counts are out/final-full-summary.json; all six full traces are out/final-full-failure-traces.txt; failure/owned-case XML are out/final-full-owned-and-failure-results/. No unchanged full rerun or CI rerun was used.
OBSERVED: Three known failures remain: NetworkMultiAddressConnectTest cancellation expectation; NoiseHandshakeOffEventLoopTest inline child and established-connection child cases. Three NEW first-attempt failures are TcpTransportTest dial-then-close server/client and WsTransportTest dial-then-close client. Each fails BEFORE close at TransportTests.kt:50, awaiting all 50 dials for the unchanged 20 seconds.
OBSERVED: Narrow full execution recording shows shared NIO workers spending the failing windows in BigInteger RSA generation. The active tests use testFixtures/NullConnectionUpgrader.establishSecureChannel, whose source runs generateKeyPair(KeyType.RSA).second for every inbound and outbound connection; the sampled stacks do not extend to that fixture frame. Tcp window has 589 execution samples; leading methods include MutableBigInteger.difference (193), primitiveRightShift (101), mulsub (92), normalize (46). This is actual per-connection fixture work on the event loops, not evidence of a close-operation failure. No transport source or fixture edit made; these are newly exposed failures and remain recorded, not dismissed.
INFER: The NullConnectionUpgrader's repeated RSA preparation during 50 parallel dials is the mechanism behind this new timeout class; this fixture path is separate from the original QUIC ED25519/ECDSA test and does not explain it. A dedicated owner should reproduce this fixture condition deterministically before changing it. It was not one of this lane's two assigned failures.
OBSERVED: Separate sealed QUIC first attempt PASS: 512 calls passed / 0 failed, 16 callers × 32 iterations, ephemeral ports, all original connection-error and cleanup assertions retained. Combined with 288 exact-method diagnostic calls, 800 diagnostic calls passed. The original QUIC timeout is still UNRESOLVED; there is no justified QUIC code fix. Original handshake output beyond the provided stack remains missing; optional text question is pending.
OBSERVED: Final fetch/rebase still preserves the exact full-tested head. A final targeted rerun is now queued/running with normal full task excluded; no second full suite will run. It has a 240-second deadline. No PR branch push has occurred yet.


## Final gate and item-by-item result
Source head remains 608b7f3415e625146814a013baadf021ed49b464, identical to the successful restored 5/5 gate and the completed full suite.
OBSERVED: Extra final targeted repeat was interrupted by its 240-second overall deadline after queue/preparation: four scenario results PASS; original QUIC case started but has no result line from that repeat. Exit 124. This verified four cases, not five; it does not replace the earlier complete restored 5/5 gate. No source changed between any of these runs, and no test timeout was altered.
OBSERVED: All tracked owned JVM/Kubo helpers have been stopped by numeric PID with process-start guards. Final ps -eo pid,args -ww audit reports no owned background processes remain.
Brief audit:
- DONE with deterministic proof: inherited Gossip fixture condition, exact five-line clock/executor change, old-red/fixed-green/reversed-red/restored-green; all original cache assertions preserved.
- DONE: fork develop comparison. Same Gossip fixture condition; peerless QUIC baseline fails with expected NullPointerException at QuicTransport.kt:234.
- INCOMPLETE: original integration QUIC dial timeout mechanism. 288 exact-method and 512 sealed-path calls passed; original full case also passed here. No guessed QUIC fix. Original handshake logs remain requested.
- DONE: both original classes rerun, one full suite only, all first-attempt failures/counts saved. Full suite 1386/6/3; three known cases and three new pre-close dial failures remain.
- DONE: separate test/code reviews, no pre-existing assertion/timeout/limit/count weakened, no new public API or README contract to document.
- Publishing only the tested Gossip fixture fix to the existing draft; all incomplete items will be explicit in its body. No Maven coordinates published, no merge/enqueue/deploy, no CI watching.

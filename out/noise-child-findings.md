# Noise child-process qualification

Plan
- [completed] Read harness/evidence; measure child startup, work and shutdown.
- [completed] State mechanism and invariants; failing ordering regression.
- [in progress] Task-drain fix passes and reverses red; startup regression queued after original deadlines still fail. Independent test/code review passes complete for task-drain change.
- [pending] First class and Noise-package qualification, full libp2p suite once, format/static checks.
- [pending] Rebase develop, targeted verification, checkpoint and PR.

2026-10-22 02:49 UTC
OBSERVED: Fresh clone at b01e08e5 on fix/noise-child-process-qualification. README and FORK.md read; no AGENTS.md in clone. CONTRACT.md read fully; it describes Handoff usage, while lane-specific brief assigns this Gradle/JUnit Noise qualification and develop target. The lane-specific instructions govern. No sibling checkout edited.
OBSERVED: Required testing documentation sections Test-Driven Development and Tests Must Be Hermetically Sealed read through authenticated GitHub API (web page returned 404). Baseline findings, child harness all 738 lines, baseline/profile logs and transport shutdown read. Transport already uses zero quiet period, daemon threads, shared workers. The inline proof precedes finally/stop; captured output uses readText to EOF.
OBSERVED: rg unavailable; using grep/find. df reports 29 GiB free. Gradle rather than kompile scripts; boxrun wraps Gradle directly.
INFER: Cold class loading precedes first harness output. JFR and live measurements needed to identify an actionable mechanism; neither a shutdown defect nor buffering is established yet.

2026-10-22 02:52 UTC
OBSERVED: Baseline JFR has 112 execution/wait events, with stack depth truncated before harness callers. Main samples include manifest parse/verification between 01:57:43.895 and 01:57:45.133, Ed25519 initial table work at 01:57:45.300–45.364, Noise Curve25519 work at 01:57:48.679. The supplied proof print was at 10.475 seconds and process exit at 11.202 seconds. This is sampled evidence, not a complete stage timeline.
OBSERVED: getconf reports 8 online processors and cgroup cpu.max is unlimited; the source computes 4 Noise workers on this machine. No evidence yet of an oversized pool. Build preparation has reached compileKotlin.

2026-10-22 02:55 UTC — checkpoint df1f4208f2304b22c28527cae937293922c80cf1
OBSERVED: Preparation is compiling test Kotlin. Scratch-only instrumented copy compiled successfully through boxrun; it adds timestamps at main, recorder, host build/start/stop. No production or test source edited. Diagnostic child is running with periodic jstack summaries and a CPU/wait-only JFR at stackdepth 128. Diagnostic overhead means this run is not a test qualification.
OBSERVED: Both parent paths start whole-process deadlines immediately after ProcessBuilder.start (test lines 51–64 and 699–710), before any host/classpath initialization. Output is read to EOF, but child println writes to the pipe during execution; no evidence yet that output buffering causes the empty baseline capture.
INFER: HandshakeState creation (NoiseXXSecureChannel.kt:188) precedes admission to the computation executor (line 528 onward), so observer counts do not account for construction. Whether this contributed to measured p95 is not established.

2026-10-22 02:57 UTC — checkpoint fb1e89b9
OBSERVED: Scratch inline child exited 0 at 7.976 seconds (JFR + jstack overhead included). Its monotonic stage markers show first host build 3.653 s, second host build 0.006 s, host startup 0.542 s, connection/proof about 0.582 s, cleanup 0.082 s. Both hosts closed; zero quiet-period delay. Print arrived before process exit. Live main samples captured URL/class lookup at 1.012 s, signed manifest verification at 3.030 s, Log4j setup/method handles at 5.046 s.
OBSERVED: jfr print defaults to five stack frames; re-exporting with --stack-depth 128 recovers full stacks in BOTH recordings. This corrects the earlier inference that the file itself had truncated stacks. Baseline main work includes BouncyCastle signed-JAR checks during Ed25519Kt.generateEd25519KeyPair:68, Log4j plugin discovery/configuration reached via NoiseXXSecureChannelKt:51, transport class startup and actual connect.
OBSERVED: testClasses build completed in 6m56s; this is compilation only, zero tests executed. Fresh unchanged class qualification is now running. A fetch/rebase attempt was initially refused by the modified findings file; findings were committed and fetch/rebase succeeded during configuration, before test execution. No source conflict or code change.
INFER: Neither output buffering nor Netty quiet shutdown accounts for these observed child runs. Startup is a sum of legitimate first-use initialization steps; a specific avoidable defect is not established by this profile.

2026-10-22 02:59 UTC — source b01e08e5 (notes head fb1e89b9)
OBSERVED: First fresh unchanged class run, --max-workers=2: 9 cases, 8 pass / 1 fail / 0 skipped. Fairness fails on actual p95=542ms; child reports elapsed=12717ms, 322 offloaded handshakes, 644 offloaded tasks, zero inline tasks. Inline/sequential both pass. Complete first failure and counts in out/baseline-class-first.log and XML preserved. No assertion/limit/iteration edited.
OBSERVED: Failure trace is java.lang.AssertionError from NoiseHandshakeOffEventLoopTest fairness assertion at line 77; child IllegalStateException originates at NoiseHandshakeStarvationHarness.main line 408: Established ping p95 was 542ms during 160 concurrent Noise handshakes; handshake crypto must not block the shared Netty worker.

2026-10-22 03:05 UTC — checkpoint fb1e89b9
OBSERVED: Scratch fairness child reproduces p95 failure too: p95 510ms, all 644 observed crypto tasks off-loop; JFR shows the shared event loop running transport registration, multistream pipeline setup, handshake construction and ready-publication/mux work. No JavaMonitorEnter >20ms in this recording. A live sample captured NoiseHandshakeReadTimeoutHandler construction; cleanup sample captured starting unused per-client boss threads from PlainNettyTransport.close:180. Cleanup happens after the p95 measurement and cannot explain it.
OBSERVED: javap of the exact netty-transport 4.2.10.Final shows SingleThreadIoEventLoop default maxTaskProcessingQuantumNs = millis(max(100, property io.netty.eventLoop.maxTaskProcessingQuantumMs default 1000)); run() polls I/O, then runAllTasks(quantum), then polls I/O again. PlainNettyTransport.kt:338 accepts that 1000ms default. This is a specific mechanism that can defer an established connection's read while non-crypto callbacks drain. A controlled diagnostic with the supported property set to 100ms is running; no source/limit changed.

2026-10-22 03:06 UTC — mechanism and planned regression
OBSERVED: Same unchanged fairness child with Netty task quantum=100ms completes with p95=219ms, elapsed=8345ms, all 644 tasks off-loop. This supports (but alone does not prove) queued-task/I-O scheduling as the source of the fairness failure.
MECHANISM: PlainNettyTransport.kt:338 builds its shared worker with Netty's default 1000ms task-drain quantum; SingleThreadIoEventLoop.run polls I/O only between those drains, so queued dial/registration/publication work can delay established reads beyond the fairness test's 500ms contract even when every Noise crypto task is offloaded. The separate empty-output child failures charged first-use JAR/crypto/logging startup before the first proof print (test:699–710); measured shutdown is short and no output-buffering loss was observed.
PLAN: add a real TCP/Noise Ping ordering regression that holds one event-loop callback across the explicit 100ms budget but below the old 1000ms budget, sends a real established ping before release, and queues 64 further real ping calls plus a marker; I/O must be observed before the queued marker. This forces the exact task-drain condition rather than tuning load to this machine. Then bound only the transport worker task-drain quantum explicitly at 100ms; retain daemon ownership, task queue configuration and per-host close behavior.
INVARIANTS before code: shared worker is created lazily once, owned by process, daemon, never closed by one host; per-channel callback ordering remains serial; queued tasks yield to established I/O after the worker quantum (individual callbacks still own their runtime); callback re-entry/close continue using the same event loop; no persisted state and no new caller-mutable resource. Regression covers I/O versus queued callbacks and cleanup of a held callback in finally; existing transport lifecycle tests remain untouched and will be included in verification.

2026-10-22 03:08 UTC — checkpoint 43d78070
OBSERVED: New NoiseEstablishedIoQueueTest written against existing public Host/Ping/Netty channel APIs, no production changes. Its child explicitly selects one worker and the old 1000ms ambient default, writes an established ping, holds one callback for 200ms (between the known policy budgets), queues 64 further genuine ping calls and a final marker, and asserts that the relay read preceded that marker. No CPU load or volume tuning. Child host start/stop use real dependencies and finally releases the callback. The unchanged-source first test attempt is running through boxrun.
OBSERVED: At 03:08 UTC status RUNNING: no PR, no source fix yet, no subagents. Next gates are assertion-red, bounded worker quantum, green/reverse/red/restore, targeted/package/full tests and PR.

2026-10-22 03:09 UTC — FIRST NEW TEST RED
OBSERVED: NoiseEstablishedIoQueueTest first attempt: 1 case, 0 passed / 1 failed / 0 skipped, compiling successfully and failing the intended child assertion (not a timeout): An established TCP read must run before the next queued callback batch after the transport task quantum ==> expected true but was false. Full stack trace in out/queue-red-first.log and XML copied to out/queue-red-first.xml. The parent correctly observes child exit 1. This establishes the exact queued-task/I-O ordering defect before any production edit.

2026-10-22 03:10 UTC — implementation under test
OBSERVED: Explicit transport-only 100ms quantum implemented using Netty public SingleThreadIoEventLoop constructor, retaining inherited DEFAULT_MAX_PENDING_TASKS, default rejection handling, shared daemon group and all transport lifecycle paths. README adds public scheduling behavior and an asynchronous Ping usage example. No existing test edited. New regression is compiling/running for its first fixed attempt.

2026-10-22 03:11 UTC — FIRST FIXED TEST GREEN
OBSERVED: Same NoiseEstablishedIoQueueTest passes first fixed attempt, 1/0/0, after successful compilation. No test/body/hold duration altered. XML and log preserved. The callback queue now yields to the real established read before the marker. Saving exact fixed source, then restoring old worker construction for the required reversal.

2026-10-22 03:13 UTC — dedicated review passes
REVIEW (test coverage, independent pass): New regression uses real TCP/Noise hosts, a real established Ping and 64 genuine further Ping calls; gates force the named task-budget interval, not CPU load. First callback writes before the gate; all queued callbacks precede the marker; marker completion safely publishes the ping list. Failure, callback failure, child deadline and host cleanup all release the gate and close owned resources. Existing inline/sequential, computation-order, timeout and transport close/daemon suites remain the neighboring checks; no existing test/fake/fixture/golden changed.
REVIEW (code, independent adversarial pass): explicit constructor retains the inherited max-pending-task setting and default rejection handling; worker group remains one lazy daemon singleton; boss group, close, channel registration and cancellation paths unchanged. newChild only uses constructor arguments during superclass construction, so no uninitialized subclass fields. Factory helper and private worker subtype are package-level; no new public API, reflection, swallowed exception or dependency. Application callbacks remain non-preemptible, documented in README.
OBSERVED: Reversal build has been waiting for the shared box gate since 03:11; no source changes are made while it waits.

2026-10-22 03:16 UTC — REVERSAL RED, EXACT FIX RESTORED
OBSERVED: Replaced only PlainNettyTransport with origin/develop source, retaining the identical new test. Reversal: 1 case, 0 passed / 1 failed / 0 skipped, the same established-read-before-marker assertion failed. Log/XML preserved. Restored out/plain-netty-fixed.kt; cmp confirms byte-for-byte identity with the first passing implementation. No deadline/iteration/assertion changed.

2026-10-22 03:20 UTC — restored qualification running
OBSERVED: First restored selected run includes complete NoiseHandshakeOffEventLoopTest, new queue regression and all three PlainNettyTransport lifecycle classes, --max-workers=2. spotlessApply changed only import order in new production imports; no existing tests or assertions edited. New queue regression already reports SUCCESS. Remaining per-test results are still being collected, so no total claimed yet.

2026-10-22 03:23 UTC — FIRST FIXED CLASS STILL FAILS STARTUP
OBSERVED: First fixed broader selection: 15 cases, 13 passed / 2 failed / 0 skipped. Original Noise class 7/2/0: inline child exceeds unchanged 10s with empty output; fairness child exceeds unchanged 15s with empty output. New ordering regression passes; all five transport lifecycle tests pass. Full traces in out/fixed-class-first-failure-traces.txt. This refutes the claim that the transport scheduling fix alone resolves qualification.
OBSERVED: Both baseline JFR and fresh child profile name Log4j plugin discovery/configuration during first-host setup before any proof. New NoiseChildStartupTest launches the real inline harness with the JVM public class-load trace and asserts the proof completes without loading Log4j PluginManager. This tests removal of the identified startup work, not a machine-dependent timing threshold. It is being run before a logging change.
INFER: Qualification children need no plugin/configuration discovery to prove crypto scheduling; choosing the API lightweight logging context before host initialization should remove that measured startup path without changing any existing deadline or assertion. This inference is not counted as fixed until the new test flips and original cases pass.

2026-10-22 03:25 UTC — startup check queued
OBSERVED: The new startup regression is queued at the box gate; no result claimed. Exact Log4j API dependency is 2.25.4. Its ProviderUtil supports selecting the API SimpleProvider through the documented provider property; selecting it avoids constructing the Core LoggerContext/PluginManager path identified in the profile. Official property documentation read at https://logging.apache.org/log4j/2.x/manual/systemproperties.html (provider available since 2.24.0). The main library logging configuration will not change; the policy is confined to dedicated child-JVM harness entry points.

2026-10-22 03:31 UTC — STARTUP REGRESSION FIRST RED
OBSERVED: New NoiseChildStartupTest compiled and failed on its first intended assertion after the real inline proof completed: Noise qualification children must not discover logging plugins before exercising their handshake contract. Counts 1 case, 0 passed / 1 failed / 0 skipped. Full trace preserved in out/startup-red-first-failure-traces.txt. This is the profiled avoidable class-loading path, not a compile failure or timing assertion.
PLAN: The understood remaining stages run sequentially in one tracked boxrun slot, with a hard 03:53 UTC deadline and per-stage deadlines: apply child-only logging selection, new-test green, logging-only reversal red, restore, original class + lifecycle + static gates, Noise package, full libp2p suite exactly once. Each stage rebases, checks disk, records fresh per-test counts and checkpoints only to wip/usage-noise-child-ckpt.

2026-10-22 03:31:51 UTC OBSERVED: Only both dedicated child entry points now select the API lightweight logging provider before host setup; INFO/WARN/ERROR remain enabled. Existing test edits: NoiseHandshakeOffEventLoopTest.kt, two main entry points add configureNoiseChildLogging; a private package helper sets provider/INFO. No existing assertion, limit, iteration, fake, fixture or golden changed.

2026-10-22 03:31:56 UTC OBSERVED: starting startup-green-first

2026-10-22 03:40:50 UTC OBSERVED: startup-green-first first attempt: 1 cases, 1 passed / 0 failed / 0 skipped, exit 0. Full traces in out/startup-green-first-failure-traces.txt.

2026-10-22 03:40:53 UTC OBSERVED: checkpoint a8c1bb3ed16f5d23fd7141495c60bd9053b68dfa

2026-10-22 03:40:54 UTC OBSERVED: starting startup-reversal

2026-10-22 03:41 UTC — review of child logging change
REVIEW (test coverage, independent pass): startup regression launches the public harness main through the JVM class-loading trace, first confirms the real Noise proof and normal exit, then rejects the specific plugin-discovery class named by the profile. Its old-code failure is an assertion after successful work, so it discriminates on the avoidable dependency path rather than elapsed machine time. The unchanged original inline, sequential, saturation and fairness assertions still exercise all child modes after the common setup call.
REVIEW (code, independent adversarial pass): the two entry points select SimpleProvider before host/recorder initialization can access the library logger; strings in the helper do not initialize LogManager early. INFO/WARN/ERROR remain enabled. Properties are confined to these already separate JVMs, so parent tests and library consumers retain their logging setup. New helper is private and package-level; no dependency, reflection, production logging or error handling changed. Documents read: CONTRACT.md, README.md, FORK.md, Testing Architecture (the two required sections), Engineering Philosophy, exact Netty/Log4j bytecode and official Log4j provider documentation.

Invariant table (renders the ownership rules recorded before the transport edit)
| Resource | Create / own / close | Terminal / caller observations | Callback, in-flight close, restart | Public test |
| --- | --- | --- | --- | --- |
| Shared worker group | Lazy transport factory / process / JVM exit | Daemon, shared; host close cannot close siblings | Same serial loop on re-entry; per-host close unchanged; no persistence | Existing daemon and listen/close tests; Noise class |
| Queued callback batch and established socket reads | Netty worker / same worker / drained as work finishes | Worker task budget 100ms; one callback runs to completion | After held callback returns, ready I/O precedes another callback batch; no persistence | New queue-order regression, red/green/reversal |
| Qualification child and output pipe | Parent test / parent until exit / bounded wait then close | Proof and child status observed; reader closes at EOF | Finally stops an unfinished child; no persisted state | Existing child cases and both new child regressions |
| Child logger selection | Child main / child / JVM exit | Lightweight provider, INFO/WARN/ERROR; no plugin discovery | Set before logger access; parent unaffected; new process sets again | New startup regression, first red confirmed; green/reversal running |

2026-10-22 03:42:12 UTC OBSERVED: startup-reversal first attempt: 1 cases, 0 passed / 1 failed / 0 skipped, exit 1. Full traces in out/startup-reversal-failure-traces.txt.

2026-10-22 03:42:12 UTC OBSERVED: startup reversal failed on the intended plugin-discovery assertion; exact logging fix restored.

2026-10-22 03:42:14 UTC OBSERVED: checkpoint 4023ab7f67c626ef093a053fa0a043ab060ff909

2026-10-22 03:42:15 UTC OBSERVED: starting qualification-class-first

2026-10-22 03:45:39 UTC OBSERVED: qualification-class-first first attempt: 16 cases, 15 passed / 1 failed / 0 skipped, exit 1. Full traces in out/qualification-class-first-failure-traces.txt.

2026-10-22 03:45:42 UTC OBSERVED: checkpoint 6601371f4ba1f0902543203be18645e69a351dc2

2026-10-22 03:45:45 UTC OBSERVED: starting noise-package-first

2026-10-22 03:49 UTC — cleanup mechanism still open
OBSERVED: After both fixes the original inline case passes and fairness prints p95=147ms, elapsed=13409ms, but the 15s process-exit assertion still fails with proof output present. Class+transport total 16 cases, 15 passed / 1 failed / 0 skipped. This isolates remaining time after successful workload. Earlier JFR/live stack already captured PlainNettyTransport.close:180 initializing and starting unused client boss loops.
OBSERVED: Stopped the tracked qualification helper and its current package Gradle client by numeric PID before the scheduled full suite. Package attempt is INTERRUPTED, with no complete total; full suite NOT STARTED. Continuing the known cleanup mechanism rather than running a full suite against a known unfinished fix.
INVARIANT before cleanup edit: only listening transports create/own an accept group; closing a client transport must not create one. listen/server creation and close already share the transport monitor, so close snapshots only an initialized group under that monitor; repeated close uses the same group, and no post-close listen may create one. New public real TCP/Noise/JFR test records only ThreadStart names during client.stop and must initially observe the unwanted accept loop. No thread or heap output is produced.

2026-10-22 03:53 UTC — exact remaining work before the time gate
OBSERVED: No PR branch has been pushed. Current source/test head 7f4607af8e2c0ca410850aacce9df9f503b4745c. Remote wip/usage-noise-child-ckpt currently has 6601371f; this update and the new cleanup test will be pushed before stopping. No artifact published; no merge/enqueue/deploy/upstream repository change.
OBSERVED: Completed proofs: queue regression old 0/1, fixed 1/0, reversal 0/1, restored passed again; startup regression old 0/1, fixed 1/0, reversal 0/1, restored passed again. First unchanged Noise class 8/1/0. Queue-only broader selection 13/2/0 (class 7/2). After both fixes broader selection 15/1/0 (class 8/1): only fairness process deadline fails, after successful p95=147ms and elapsed=13409ms proof. spotlessCheck and detekt pass. Existing test edits: ONLY NoiseHandshakeOffEventLoopTest.kt adds a private logging helper and calls it first in its two dedicated mains; before there was no provider choice, after child-only SimpleProvider with INFO is selected. No existing test function/assertion/deadline/limit/count/fake/fixture/golden changed.
OBSERVED: Noise package first attempt was interrupted after five successful case lines to investigate the remaining known cleanup defect; no complete package count is claimed. Full suite has NOT run. Cleanup test has been queued since about 03:50, with no CASE output; compilation/behavior not yet verified.
MECHANISM STILL TO FIX: PlainNettyTransport.kt:70 lazily creates a per-instance accept group. close() unconditionally reads bossGroup at :186 (original :180) even for clients that never listened, constructing and starting one daemon accept thread per client just to shut it down. This occurs after the fairness proof, matching the live Thread.start sample and the remaining post-proof process deadline.
NEXT EXACT PATCH, only AFTER the cleanup test fails on its accept-thread assertion: replace private bossGroup lazyVar with a private Kotlin lazy delegate plus a private delegated val; under the existing synchronized close snapshot, capture bossGroup only if that delegate isInitialized. In thenCompose, shut down that captured group with unchanged quietPeriod=0 and timeout=5s, or return a completed Unit future when absent. Listen/server initialization already occurs under the same transport monitor, and closed=true rejects later listen, so there is no late group to miss. Keep the shared worker untouched. Add callback/close rows to review as needed; existing daemon/listen-close/timing cases must remain green.
REMAINING GATES: cleanup regression assertion-red; implementation green; cleanup-only reversal red then restore; original class and transport cases; first complete Noise-package and full :libp2p:test --max-workers=2 runs; final review, fetch/rebase develop and targeted verification; one push of fix/noise-child-process-qualification and PR against develop. Preserve all first-attempt failures in the PR, and keep draft status if any qualification remains incomplete. Do not claim the p95 proof alone is a process-exit pass.

2026-10-22 03:54 UTC — CLEANUP FIRST RED
OBSERVED: Cleanup regression compiled and failed on its exact assertion: closing a client-only host started an unused libp2p-nio-boss thread. 1 case, 0 passed / 1 failed / 0 skipped. JFR records only ThreadStart names, no stacks. Full trace in out/cleanup-red-first-failure-traces.txt. Applying only the previously described initialized-boss snapshot fix; no limit changes.

2026-10-22 03:57 UTC — STOPPED AT THE 70-MINUTE GATE, NOT COMPLETE
OBSERVED: Cleanup regression old code: 0 passed / 1 failed, exact new boss name libp2p-nio-boss-4-1. Cleanup implementation is committed at 38c112bde9990a09153e98116843d7dad4c043cd, but its first fixed run remained queued with no CASE lines and was stopped at the time gate. Therefore cleanup GREEN/REVERSAL are NOT VERIFIED; do not infer them from the source. Source uses a lazy boss delegate, snapshots an initialized boss under the existing close/listen monitor and skips absent boss shutdown.
OBSERVED: The tracked sequence and interrupted package Gradle client were already stopped; stopped the final queued helper and this session's owned Gradle daemon by numeric PID. Process audit follows in this findings file. PR NOT CREATED; PR branch NEVER PUSHED. Only wip/usage-noise-child-ckpt carries checkpoints. Published coordinates: NONE.
REMAINING: cleanup green then logging/queue-preserving cleanup-only reversal and restore; original Noise class and transport lifecycle checks; COMPLETE Noise package first run; full :libp2p:test exactly once (not started yet); final review and spotless/detekt on cleanup change; remove scratch findings from the eventual PR diff; fetch/rebase develop and targeted verification; one PR-branch push and PR against develop with all first-attempt failures and existing-test edits disclosed. The prior spotless/detekt pass predates cleanup.

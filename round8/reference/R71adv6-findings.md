# Exact-head adversarial review
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71
HEAD: 01c16ae9db8c1aa09a5b1cd0fa040c4bbd32f018
OBSERVED: Initial gh pr view matches required head; base is develop. Review uses origin/develop per brief override. No push, merge, approval, PR comment or delegation authorized.

## Plan
- DONE: Fresh clone, exact-head checkout, immediate targeted background test.
- DONE: Read required documents, full diff and changed functions; trace callers and lifecycle invariants.
- DONE: Probe shutdown hooks, result immutability, repeated drain/refill, and Noise readiness path.
- DONE: Audit description, existing test edits, artifacts and runtime records.
- DONE: Stop all started processes and report demonstrated findings.

OBSERVED: Review start 2026-10-25 (UTC; exact command timestamps recorded with runtime logs).

OBSERVED 03:53 UTC: Exact detached checkout complete. Requested scripts/test.bash command started at 03:50:31 UTC and immediately exited 127: bash: scripts/test.bash: No such file or directory. README/FORK confirm Gradle-only repository. Gradle targeted converter command started at 03:51:44 UTC after copying dependency/wrapper caches into this workspace's private gradle-home; --offline --max-workers=2 --no-daemon, bounded at 2400 seconds. No per-test record yet. No AGENTS.md exists in clone. rg unavailable; using grep/find.
OBSERVED: Read README.md, FORK.md, PHILOSOPHY.md and TESTING.md (remaining rereads underway where output truncation cut paragraphs). Read full production diff and all changed production files plus AbstractChildChannel and mux entry points. Round-four final's only unrun class is NoiseHandshakeOffEventLoopTest; it will receive its own first-attempt command after compilation.
OBSERVED: Confirmed requested SHOULD_FIX diagnostic print at libp2p/src/test/java/io/libp2p/transport/UnflushedWriteTerminationTest.java:101. It prints the final state before asserting that state. Remove the print; existing assertions already diagnose failures.
OBSERVED: No existing test is modified against origin/develop: every changed test file is added. The description opens with the observed downstream failure, links the handoff, explicitly says no existing test edits, and ends with the Claude Code line. No investigation/ or .evidence/ files remain at HEAD.

## Lifecycle review invariants
| Resource | Owner and terminal cleanup | Callback/re-entry/termination expectation |
|---|---|---|
| Accepted outbound entries | Child event loop owns buffer; completion removes/releases entries; close or owner shutdown settles all | Promise observes payload already released; cancellation retains buffer ownership until actual release |
| Accepted-write shutdown hook | Child loop installs before write enqueue; removes on drain or close | Retired copy in shutdown batch must be harmless; refill requires a fresh owner |
| Parent result token | Child loop owns pending slot and shutdown hook; off-loop callback only dispatches | Completed outcome immutable; stale runnable cannot remove another write; cancellation stops retained dispatch |
| Retry task | Global executor owns bounded scheduled delivery attempt | Child close marks inactive and cancels scheduled retry; no buffer mutation off-loop |
| Public connection close observation | Physical close result plus depth counter across callbacks | Earlier registered physical listeners precede reentrant close completion |

OBSERVED 03:57 UTC: Static review has found no additional production defect so far. Hook additions/removals in accounting, flush, doWrite, doClose, and shutdown run on the child loop; the foreign parent listener only enqueues or schedules delivery. Completion cancellation holds the token monitor only around internal retry cancellation; payload removal and public completion happen outside that monitor. Parent-result settlement clears ownership before notifying callers. Stale completion tasks check the pending-parent slot before touching its buffer.
OBSERVED: Artifact audit saved to ws/R71adv6/tree-audit.json: 29 changed paths, zero added non-source files, zero existing test edits, no whitespace errors. Largest added file is ReviewTerminalWriteConversionProbe.java, 15,316 bytes (test source, not evidence/output). Deleted .evidence files are not present at HEAD.
OBSERVED: NoiseBindingReadinessTest, NoiseAlgorithmReadinessTest/Probe, NoiseProbePhases, PlainNettyTransport readiness/preparation, Builders startup preparation registration, and Noise identity/algorithm setup were traced. READY is emitted before host construction or any connection; the new shutdown hooks and retained delivery cannot execute in that phase. BODY_COMPLETE follows startup, first connection/stream negotiation, ping, stream close and event parsing. It reaches ordinary changed mux/converter paths, but shutdown settlement runs only on loop shutdown and retained dispatch only after actual queue rejection. This harness does not shut down its shared worker loop during the body or deliberately fill its queue.
INFER: PRE_EXISTING (not a PR finding): one 30-second aggregate process budget charges JVM startup, class loading/agent startup and the entire operation body together. A longer READY consumes the body allowance even when each operation is within its own unchanged 10-second bound. This explains the design weakness in the historical READY=19.7s/body-pending=10.3s record, but does not identify the historical delay's mechanism or prove the PR cannot change runtime by ordinary overhead. No evidence establishes a new readiness wait from this PR.
OBSERVED: Prepared a standalone public-Netty-API probe outside the repository: 64 drain/refill cycles with foreign-executor results, exact hook ownership and thread checks, then a genuine shutdown batch in which an earlier hook flushes the buffer and retires a hook already copied into the current batch, with/without refill. Will run after compilation; no production or existing test source was changed.

OBSERVED 04:02 UTC: Demonstrated a second issue through the public channel write API in standalone ZeroByteTerminationProbe: with Netty's supported io.netty.transport.outboundBufferEntrySizeOverhead=0 setting, an unflushed zero-readable-byte ByteBuf is accepted with refCnt=1, but the new byte-total emptiness check retires its shutdown hook. After parent close is refused and the owner terminates, output is terminated=true writeDone=false payloadRefCnt=1 trackedHooks=0. Assertion: An accepted zero-byte write must settle before owner termination. The payload is reclaimed in probe finally after recording failure. No production source change.
INFER: Cause is MuxChannel.kt:109 and :128: zero pending accounting bytes does not prove no accepted entries exist. Both hook retirement and the shutdown early return use that assumption. Fix should base termination ownership and emptiness on retained entries rather than accounted bytes; add this zero-byte/zero-overhead regression alongside the ordinary configuration control.
OBSERVED: First standalone lifecycle probe reached a fixture assertion before the batch scenario because it counted Netty LocalChannel's own public shutdown-hook registrations as child hooks. This is a probe setup error, not a PR defect. Corrected it to snapshot the existing hook count and assert the exact incremental count; first log retained as hook-empty-first.log. No production/test parameter, timeout or iteration count changed.

OBSERVED 04:03 UTC: Default-accounting zero-byte control passes: terminated=true writeDone=true payloadRefCnt=0 trackedHooks=0 (zero-default-control.log). Corrected lifecycle probe passes both genuine shutdown-batch scenarios, each after 64 foreign-executor drain/refill cycles. Every cycle asserts exact incremental hook counts and completion on the owner after payload release. Empty stale hook leaves the child write-side state unchanged; positive-byte refill settles before termination. Logs: hook-empty-corrected.log and hook-refill-first.log. Hook adds/removals all ran on owner thread. First erroneous fixture attempt remains recorded separately.
OBSERVED: Line corrections after numbered-source check: zero-byte hook retirement is MuxChannel.kt:109; shutdown early return is :125; requested diagnostic print starts at UnflushedWriteTerminationTest.java:96 (not :101).
OBSERVED: Main Kotlin/Java and fixture compilation finished; test Kotlin compilation is underway. Public standalone probes already ran against this clone's exact-head main jar, using the Gradle-resolved runtime classpath. The initial Gradle converter test has not yet emitted XML. A second Gradle command only resolved the runtime classpath and completed successfully; at most two Gradle commands overlapped and at most one was a test command.

## Demonstrated failure record
OBSERVED: ZeroByteTerminationProbe, first attempt, exit 1; complete failure output:
```text
terminated=true writeDone=false payloadRefCnt=1 trackedHooks=0
Exception in thread "main" java.lang.AssertionError: An accepted zero-byte write must settle before owner termination
	at io.libp2p.transport.HookLifecycleProbe.require(HookLifecycleProbe.java:17)
	at io.libp2p.transport.ZeroByteTerminationProbe.main(ZeroByteTerminationProbe.java:43)
```

OBSERVED 04:05 UTC: A second, distinct deterministic ordering also exposes the same byte-count defect: 64 positive-byte drain/refill cycles pass with zero configured overhead; then during the real shutdown batch an earlier hook flushes a positive-byte entry, retires the accepted-write hook already copied into the batch, and refills with a zero-readable-byte buffer. The retired batch entry runs but MuxChannel.kt:125 returns on zero accounted bytes, leaving the refill pending. hook-zero-refill-first.log fails Refill must settle before termination. This confirms the shutdown early-return issue independently of removal preventing the hook from running. No timeout or iteration change.

Reproduction commands from ws/R71adv6 (all probe sources and outputs remain outside the PR tree):
```bash
javac -proc:none -cp "$(cat runtime-classpath.txt)" -d probe-classes probe-src/HookLifecycleProbe.java probe-src/ZeroByteTerminationProbe.java
java -Xmx128m -Dio.netty.transport.outboundBufferEntrySizeOverhead=0 -cp "probe-classes:$(cat runtime-classpath.txt)" io.libp2p.transport.ZeroByteTerminationProbe
java -Xmx128m -cp "probe-classes:$(cat runtime-classpath.txt)" io.libp2p.transport.ZeroByteTerminationProbe
java -Xmx128m -Dio.netty.transport.outboundBufferEntrySizeOverhead=0 -cp "probe-classes:$(cat runtime-classpath.txt)" io.libp2p.transport.HookLifecycleProbe refill-zero
```
OBSERVED: No code fix was attempted; this is a review only. The zero-byte issue is an uncovered edge of the PR's new accepted-write settlement guarantee, not a claim that this PR introduced the original pre-existing unflushed-write leak.

OBSERVED 04:06 UTC: Review's remaining runtime plan is the initial converter class, the round-four omitted NoiseHandshakeOffEventLoopTest (29 checks), NoiseBindingReadinessTest, UnflushedWriteTerminationTest and the completion/shutdown regression classes, each in its own command. Compiler process remains active (12m process elapsed, 7m51s CPU at observation); no test body/XML from the initial command yet. Main-source standalone runtime is already available, so the no-runtime cutoff condition does not apply to the whole review; any incomplete Gradle coverage will be stated explicitly.
OBSERVED: The supplied orchestrator review path was not present at the PR head or at that path on evidence/pr71-20261024 (read-only lookup returned HTTP 404). Its specific diagnostic-print item was independently confirmed directly in numbered test source; no reliance on an unavailable report.

OBSERVED 04:09 UTC: Complete test-source compilation finished; initial converter command reached testClasses. To overlap validation with the final compile phase, unchanged Java PR test classes were also compiled externally with javac against the exact-head main/fixture jars and Gradle runtime classpath. A reviewJava Gradle Test task in an outside-tree init script uses those classes, the same JUnit platform/interop exclusion/default heap, one class filter per command, and separate output directories. The original initial Gradle test task remains active. Two Gradle test commands now overlap, respecting the cap. First UnflushedWriteTerminationTest attempt started 04:08:30 UTC. No repository source or existing build/test setting changed.

OBSERVED 04:09 UTC: Initial converter command finished successfully after 17m11s. CompletedNettyFutureConversionTest: 6 tests, 0 failures/errors/skips, XML runtime 2.923s. First per-test XML record was written 2026-10-25 04:08:55.473944913 UTC (suite start 04:08:52.083 UTC); initial Gradle command began 03:51:44 UTC. XML preserved in ws/R71adv6/converter-results/. Compilation finished well before minute 45. Required omitted-class run NoiseHandshakeOffEventLoopTest started 04:09:20 UTC, first attempt, original Gradle :libp2p:test task, one class filter. Unflushed Java-class first attempt remains active in the second slot.

OBSERVED 04:12 UTC: Mandatory round-four omitted class ran first attempt and FAILED: NoiseHandshakeOffEventLoopTest 29 checks, 1 failure, 0 errors/skips. The failing check is established connection stays responsive during concurrent Noise handshakes. Child evidence: 160 peers, ping latencies [1,1,4,5,5,6,7,7,8,8,12,18,20,20,21,67,68,408,687,713] ms, p95=687ms, elapsed=9864ms; all 322 counted handshakes and 644 crypto tasks were offloaded, with zero crypto tasks on the event loop. First attempt preserved in noise-offloop-first.log, noise-offloop-results/XML, noise-offloop-full-failure.txt. No rerun was requested or started. Failure classification under investigation; no attribution to the PR yet.
OBSERVED: ReviewCompletedShutdownTest passed 6/6 first attempt, preserving original parent success, original failure/cancellation, and explicit-close variants. UnflushedWriteTerminationTest passed 4/4 first attempt. ReviewTerminalWriteConversionTest first attempt is next in the Java slot.

OBSERVED 04:14 UTC: ReviewTerminalWriteConversionTest passes 4/4 first attempt. Noise failure stack and harness inspected: its unchanged fixed p95 <500ms assertion fails while its direct crypto ownership assertions pass (all crypto off-loop). That proves the p95 assertion fired, not why ping delivery took 687ms. Neither host load nor this PR is claimed as the cause.
OBSERVED: Preparing one explicit baseline control, not an unchanged-head retry. origin/develop and merge base both equal 7f9f06c275d99db89e04506351dc09446ab40f55. All five changed production source files are extracted from that exact base into baseline-src outside the repo and compiled into an outside-tree classpath overlay; every other production file is byte-identical between base and PR. The Noise test is also byte-identical against base. Criterion set before the control: the same p95 failure on the base overlay establishes that the observed threshold failure can occur without this PR; a baseline pass does not establish causation or dismiss the first failure. No bound, iteration count, host setting or test source changes. NoiseBindingReadinessTest is concurrently on its first original Gradle-task attempt.

OBSERVED 04:16 UTC: Baseline extraction source is https://github.com/CodexCoder21Organization/jvm-libp2p/commit/7f9f06c275d99db89e04506351dc09446ab40f55. The initial direct Kotlin control compilation succeeded; before executing any control I noticed the direct compiler's default module name would differ from Gradle's libp2p name (and therefore internal JVM method names). A second, matched-module baseline overlay is being compiled with -module-name libp2p. The first overlay was never used for a runtime result. This correction keeps the baseline control's emitted signatures aligned with the original build. PR source and original runtime artifacts are untouched.

OBSERVED 04:17 UTC: NoiseBindingReadinessTest passes 3/3 first attempt, XML 57.249 seconds for the class. ReviewTerminalWriteConversionTest 4/4, XML 51.761s; ReviewCompletedShutdownTest 6/6, XML 25.969s. The original shared-budget design weakness remains PRE_EXISTING and is not a finding against this PR. Historical delay mechanism remains unknown.
OBSERVED: Matched-module base overlay compiled successfully and its MuxChannel internal reset method has the expected libp2p module suffix. The one base control of NoiseHandshakeStarvationHarness is now running, with the identical 160 peers, 20 ping samples, one Netty worker and <500ms p95 guard; no bound lifted or assertion changed. ReentrantChildWriteCloseTest is on its first one-class Gradle command in the other slot.

OBSERVED 04:18 UTC: ReentrantChildWriteCloseTest passes 1/1 first attempt. The direct base-code Noise harness also fails its unchanged p95 guard: p95=1001ms, elapsed=20080ms, all 322 handshakes and 644 crypto tasks offloaded; log noise-baseline-control.log contains the complete assertion/stack. This direct diagnostic command used a 45-second command bound; that does not retain the original wrapper's 15-second process bound, so I am NOT treating it as an equivalent original-test result. No source timeout was changed. To make the actual comparison valid, the unchanged original JUnit test method is now being run once with the baseline overlay, retaining its exact 15-second child wait and every assertion. Only its main-code classpath differs. This result is separate from the mandatory 29-check first attempt; none of that first failure is erased. ReviewChildLoopAffinityTest first attempt runs in the second slot.

OBSERVED 04:20 UTC: Exact original Noise JUnit wrapper on the base overlay PASSES 1/1 with its unchanged 15-second wait. Per the pre-registered criterion, this single baseline pass does NOT establish that the PR caused the original failure and does NOT dismiss it. The direct base harness's p95 failure is useful diagnostic evidence that off-loop crypto and a >500ms ping can coexist without this PR, but its longer outer command bound prevents treating it as an equivalent wrapper attempt. Classification of the original PR-head latency failure remains mechanism unknown / attribution unproven; it is reported separately from the two demonstrated PR findings, not silently relabeled PRE_EXISTING. No PR-head test rerun took place.
OBSERVED: OffLoopParentWriteNotificationTest first attempt starts in the available slot; ReviewChildLoopAffinityTest is still running. These complete the planned focused register. No wider suite is needed for this review.

## Mandatory Noise class first failure (complete stack)
OBSERVED: The first PR-head class attempt remains failed; no unchanged-head retry was run.
```text
established connection stays responsive during concurrent Noise handshakes()
java.lang.AssertionError: Noise handshake starvation child JVM failed:
Noise handshake storm: peers=160 pingLatenciesMs=[1, 1, 4, 5, 5, 6, 7, 7, 8, 8, 12, 18, 20, 20, 21, 67, 68, 408, 687, 713] p95Ms=687 elapsedMs=9864 execution=NoiseHandshakeExecutionSnapshot(inlineHandshakes=0, offloadedHandshakes=322, inlineCryptoTasks=0, inlineCryptoTasksOffEventLoop=0, offloadedCryptoTasks=644, offloadedCryptoTasksOnEventLoop=0)
Exception in thread "main" java.lang.IllegalStateException: Established ping p95 was 687ms during 160 concurrent Noise handshakes; handshake crypto must not block the shared Netty worker
	at io.libp2p.security.noise.NoiseHandshakeStarvationHarness.main(NoiseHandshakeOffEventLoopTest.kt:505)

	at io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes(NoiseHandshakeOffEventLoopTest.kt:86)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

```

OBSERVED: Valid base-wrapper control XML: 1/1 pass, runtime 14.066s, child elapsed 10543ms, p95=454ms. This is a distinct base-code control, excluded from PR-head pass counts.

## Final review findings
OBSERVED: BLOCKING — libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/MuxChannel.kt:109 and :125. The new termination-owner retirement and shutdown early return equate zero pending accounting bytes with no retained entries. With -Dio.netty.transport.outboundBufferEntrySizeOverhead=0, child.write(Unpooled.buffer(1, 1)) accepts an unflushed buffer with zero readable bytes. A close handler refusing closure and owner-loop termination leave the promise pending and refCnt=1. Default-overhead control settles and releases it. The real current-batch zero-byte-refill scenario independently fails even when a retired hook executes. Base termination ownership/emptiness on retained entries rather than byte totals, including cancelled unflushed entries; add these exact regressions. This is a remaining edge of the PR's new guarantee, not a claim that the original unflushed-write leak began with the PR.
OBSERVED: SHOULD_FIX — libp2p/src/test/java/io/libp2p/transport/UnflushedWriteTerminationTest.java:96. The diagnostic System.out.println remains immediately before the final assertions. Confirmed directly against exact-head numbered source. Remove it; use assertion messages for failure details.

## Final PR-head register (each class first attempt, one class per command)
| Class | Tests | Failures | Errors | Skipped | XML seconds |
|---|---:|---:|---:|---:|---:|
| CompletedNettyFutureConversionTest | 6 | 0 | 0 | 0 | 2.923 |
| UnflushedWriteTerminationTest | 4 | 0 | 0 | 0 | 2.364 |
| NoiseHandshakeOffEventLoopTest | 29 | 1 | 0 | 0 | 66.062 |
| ReviewCompletedShutdownTest | 6 | 0 | 0 | 0 | 25.969 |
| ReviewTerminalWriteConversionTest | 4 | 0 | 0 | 0 | 51.761 |
| NoiseBindingReadinessTest | 3 | 0 | 0 | 0 | 57.249 |
| ReentrantChildWriteCloseTest | 1 | 0 | 0 | 0 | 5.473 |
| ReviewChildLoopAffinityTest | 3 | 0 | 0 | 0 | 16.686 |
| OffLoopParentWriteNotificationTest | 6 | 0 | 0 | 0 | 28.455 |

OBSERVED: Total 61/62 passed, 1 failure, 0 errors, 0 skips. The mandatory previously unrun class NoiseHandshakeOffEventLoopTest was run and its first failure is preserved. Its p95 guard failed at 687ms versus <500ms; the cause and attribution to this PR remain unknown. Its exact unchanged wrapper on the base overlay passed (p95 454ms); the separate direct base-harness diagnostic failed p95 at 1001ms but ran beyond the original wrapper's 15-second guard, so it is not counted as an equivalent wrapper result. No unchanged-head test was rerun into green.
OBSERVED: Standalone default controls passed 64 foreign-executor drain/refill cycles each for empty and refilled real shutdown-batch cases. The zero-byte/zero-overhead probe and real-batch zero-byte refill both failed at their intended assertions. Full logs and source are outside the PR tree under ws/R71adv6/.
OBSERVED: PRE_EXISTING, not a PR finding: NoiseBindingReadinessTest's shared 30-second JVM-startup/body/cleanup budget can leave the operation body only the remainder after READY. Its first attempt here passes 3/3. No mechanism for the earlier readiness timeout is established.
OBSERVED: Description opens with motivation, links the handoff, explicitly reports no edits to existing tests, and ends with the Claude Code line. Against origin/develop, zero pre-existing assertions/bounds/timeouts/iteration counts were edited; all test changes are additions. No investigation/ directory, added binaries, caches, logs, or temporary evidence files at the reviewed head. No newly introduced reflection, mocks, fat-jar dependencies, or widened testing visibility. README describes changed behavior.
OBSERVED: MAX_CONCURRENT_COMMANDS_OBSERVED: 2. This cap held both for Gradle test commands and for active test/control workloads. No bld-build check was expected or used; all runtime evidence is local.
OBSERVED 04:22 UTC: Final live PR-head read still matches 01c16ae9db8c1aa09a5b1cd0fa040c4bbd32f018. git status is clean and detached HEAD is unchanged. Private-home Gradle --stop completed (one daemon stopped); final /proc scan finds zero owned Java processes. Every tracked exec/test/control/compiler process is terminal. No push, PR comment, approval, merge, queue, publish or deployment occurred.

## Brief completion audit
- Exact-head gate and fresh clone: DONE; initial and final live head checks match.
- Immediate targeted build and overlap: DONE; missing kompile script recorded; Gradle fallback started before static review; compile and first XML timestamps recorded.
- Required reading and complete diff/function/caller trace: DONE; README, FORK, PHILOSOPHY, TESTING, relevant CODE_REVIEW guidance; production stream/mux/transport/close paths and Noise preparation/readiness traced. No repository AGENTS.md exists.
- Lifecycle/ownership/error/quality checks and requested probes: DONE; invariant table and public-API runtime proofs above.
- Retired current-batch hook and repeated drain/refill: DONE; positive-byte controls green; zero-byte edge red.
- Parent completion token, immutable result and payload release: DONE; completed/shutdown, public termination, affinity and off-loop classes all green on first attempts.
- Historical Noise readiness classification: DONE as PRE_EXISTING budget design weakness, without claiming the historical delay is solved.
- Round-four omitted runtime class: DONE, 29 checks with one explicit unresolved latency failure; no omission hidden.
- Description/test-change/artifact audit: DONE; no outstanding audit item.
- Cleanup and final report: DONE; zero owned Java processes, clean PR checkout, two findings.

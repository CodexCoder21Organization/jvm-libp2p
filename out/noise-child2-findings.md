# Noise child-process qualification, continuation

Plan
- [in progress] Verify cleanup regression with fix, reversed fix, then restored fix.
- [pending] First complete NoiseHandshakeOffEventLoopTest, Noise package, full :libp2p:test once (--max-workers=2).
- [pending] Independent test/code review, formatting/static checks and README cleanup contract.
- [pending] Rebase develop, targeted final verification, one PR branch push and PR against develop; process audit.

2026-10-22 04:11 UTC
OBSERVED: Fresh clone in assigned lane, checkpoint 70ea29e79b83acf44ea8786cb337dade505ed21f checked out on fix/noise-child-process-qualification and rebased onto origin/develop without changes. Read CONTRACT.md fully and previous lane findings fully. The Handoff contract describes a separate effort; this lane's explicit jvm-libp2p/develop task governs. README and FORK.md read; no repository AGENTS.md present. Required testing documentation sections read via authenticated GitHub API (web link returns 404). This is Gradle/JUnit, with no scripts/test.bash or tests/*.kts; boxrun wraps Gradle directly as prior lane did. No sibling checkout edited.
OBSERVED: Inherited cleanup regression is real TCP host/client close with JFR ThreadStart names only; no stacks. Existing tests changed only for the inherited child logging helper in NoiseHandshakeOffEventLoopTest.kt. Inherited findings establish prior queue/startup red-green-red; cleanup first red was 0 passed / 1 failed but first fixed attempt never ran.
INVARIANT TABLE before verification:
| Resource | Creates / owns / closes | Terminal and caller behavior | Public test |
|---|---|---|---|
| Client-only accept group | Nobody; no listener exists | Closing (including repeated close) must not create a group | PlainNettyTransportUnusedBossTest |
| Listening accept group | Transport via listen/server, under transport monitor | close snapshots initialized group under same monitor; post-close listen rejected; close during bind includes listener | Existing concurrent listen-close, daemon and shutdown timing tests |
| Shared worker | Process singleton, daemon | Per-host close leaves other hosts usable; callbacks retain serial ordering | New established I/O queue regression and original multi-host Noise fairness |
| Child logger | Dedicated main before first logging call | Process-only SimpleProvider; parent configuration unchanged | New child startup regression and original child cases |
INFER: Avoiding unused accept-loop creation removes the measured cleanup work, but process-exit qualification is unproven until the original class passes.

2026-10-22 04:12 UTC — independent review passes while compilation runs
OBSERVED: Enumerated every bossGroup/server access: only listen initializes server under the transport monitor; close checks the lazy delegate under that same monitor. closed is set there before the check. There is no late group creation path after close. Existing concurrent-listen-close tests cover both in-flight bind and parallel entry, shutdown timing covers actual listeners and repeated transports, daemon test covers thread ownership. The new cleanup regression closes a real connected client and finally repeats stop; records names only and deletes its temporary recording even on failure.
REVIEW (tests): public Host/network/stop path uses real TCP dependencies and ephemeral ports. Regression discriminates on creation of a named accept thread rather than elapsed runtime. Gradle tests run serially within their worker (parallel forks disabled), so no sibling test can start such a thread while this recording runs. No existing tests weakened or modified beyond inherited child logging setup.
REVIEW (code): shared worker remains process-owned and daemon; only worker task policy changes. Constructor retains Netty max pending tasks and rejection policy. Listening boss shutdown retains zero quiet period and 5s bound. Nullable snapshot avoids initializing an unused group and retains actual close completion for initialized groups. Helpers/subclass remain private at package level; no reflection, new dependency or swallowed exception. README now states client-only close behavior alongside existing lifecycle contract and scheduling example.
OBSERVED: Removed inherited scratch findings from the staged PR tree (left local file intact); new findings and local test-event script remain ignored scratch files. Initial git note commit failed because local author identity was unset; set repository-local identity matching inherited commits. No source/test result was lost.

2026-10-22 04:17 UTC
OBSERVED: Current commit 70ea29e79b83acf44ea8786cb337dade505ed21f. First fixed cleanup run acquired boxrun slot 3 at 04:11:26, has completed production and fixture compilation and is compiling tests. No per-test CASE lines yet; zero tests verified so far. Disk remains above 15 GiB free. No source changes made while compiling; README and scratch exclusion staged for the next passing checkpoint.

2026-10-22 04:18 UTC — first fixed cleanup GREEN
OBSERVED: PlainNettyTransportUnusedBossTest first fixed attempt: 1 passed / 0 failed / 0 skipped, BUILD SUCCESSFUL, 7m20s including initial complete compilation. Real test CASE output confirms execution. Fixed source saved byte-for-byte for the cleanup-only reversal; queue and logging changes stay in place. No deadline, iteration, limit or assertion changed.

2026-10-22 04:22 UTC — cleanup reversal RED, fixed source restored
OBSERVED: Cleanup-only reversal, preserving queue/logging fixes: 0 passed / 1 failed / 0 skipped, exit 1. Failed the intended assertion, not compilation or a deadline. Exact fixed transport restored; cmp confirms byte identity with first green. Full trace:
```
io.libp2p.transport.PlainNettyTransportUnusedBossTest :: closing a client connection does not start an unused accept loop()
org.opentest4j.AssertionFailedError: Closing a client-only host must not create an accept loop, but started [libp2p-nio-boss-4-1] ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.transport.PlainNettyTransportUnusedBossTest.closing a client connection does not start an unused accept loop(PlainNettyTransportUnusedBossTest.kt:35)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)

```

2026-10-22 04:24 UTC
OBSERVED: Current committed checkpoint efa6ef72 (remote wip/usage-noise-child2-ckpt). Cleanup red-green-red proof now complete: prior old implementation 0/1, first fixed 1/0, cleanup-only reversal 0/1 with exact unused accept-thread assertion. Fixed source restored byte-for-byte. Restored regression plus existing lifecycle classes is running; boxrun slot 1 acquired at 04:23:11 after a short queue. No original Noise class/package/full run started in this lane yet.

2026-10-22 04:29 UTC — restored cleanup GREEN, neighboring failure
OBSERVED: Restored transport selection first attempt: 5 passed / 1 failed / 0 skipped. The new cleanup regression passed again; both shutdown timing checks, daemon check and serial listen-close test passed. Parallel listen-close failed on port 33713, 83/200 iterations beginning at 108. Log also records rejected future-listener notification at iterations 108 and 191 after the accept event loop closed. Full assertion trace:
```
io.libp2p.transport.PlainNettyTransportConcurrentListenCloseTest :: parallel listen and close from separate threads must not leak the listener channel()
org.opentest4j.AssertionFailedError: Port 33713 leaked after 83/200 parallel listen-close races (first at iter 108). iter=108: port 33713 not released. BindException: Address already in use ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.transport.PlainNettyTransportConcurrentListenCloseTest.parallel listen and close from separate threads must not leak the listener channel(PlainNettyTransportConcurrentListenCloseTest.kt:189)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)

```
INFER: The port assertion alone does not identify which process held that shared ephemeral port; the rejected notification names a separate listen-future conversion after leaving the lock. No evidence yet distinguishes a transport leak from another process using the temporarily unbound port. This remains an OPEN qualification finding, not a claimed baseline issue or a passing lifecycle suite. No existing test/assertion changed or retried. Proceeding to the explicitly requested first Noise class attempt; inspect precise port ownership if lifecycle failure recurs in the one full run.

2026-10-22 04:35 UTC — first original Noise class: 8/1
OBSERVED: First NoiseHandshakeOffEventLoopTest attempt in this lane: 8 passed / 1 failed / 0 skipped. Fairness child completed (not process deadline failure), p95=584ms, elapsed=9499ms, all 644 crypto tasks off-loop. Inline/sequential and all other original cases passed. Full trace:
```
io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest :: established connection stays responsive during concurrent Noise handshakes()
java.lang.AssertionError: Noise handshake starvation child JVM failed:
Noise handshake storm: peers=160 pingLatenciesMs=[1, 1, 1, 2, 2, 3, 3, 3, 4, 8, 9, 11, 14, 39, 50, 66, 453, 510, 584, 592] p95Ms=584 elapsedMs=9499 execution=NoiseHandshakeExecutionSnapshot(inlineHandshakes=0, offloadedHandshakes=322, inlineCryptoTasks=0, inlineCryptoTasksOffEventLoop=0, offloadedCryptoTasks=644, offloadedCryptoTasksOnEventLoop=0)
Exception in thread "main" java.lang.IllegalStateException: Established ping p95 was 584ms during 160 concurrent Noise handshakes; handshake crypto must not block the shared Netty worker
	at io.libp2p.security.noise.NoiseHandshakeStarvationHarness.main(NoiseHandshakeOffEventLoopTest.kt:409)

	at io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest.established connection stays responsive during concurrent Noise handshakes(NoiseHandshakeOffEventLoopTest.kt:77)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

```
INFER: Cleanup process-exit issue is resolved for this run, but 100ms queued-task policy does not by itself ensure the original fairness contract. Need profile event-loop work outside the observed crypto tasks, including I/O-ready batches versus queued callback batches; no further code change until mechanism and a fail-first public reproducer are established. Package/full qualification paused for this known unfinished issue.

2026-10-22 04:39 UTC — exact remaining scheduling mechanism and fail-first plan
OBSERVED: Diagnostic real child reproduced p95 753ms; this was a JFR/jstack run, not qualification. Main/loop summaries and aggregate JFR events saved in out/; no thread/heap output printed. During workload both ordinary task draining and I/O callbacks run non-crypto negotiation/publication work. Exact netty-common 4.2.10.Final bytecode SingleThreadEventExecutor.runAllTasks(long) only reads the clock after (runTasks & 63) == 0; SingleThreadIoEventLoop passes its configured quantum to this method.
MECHANISM: Our worker's 100ms policy delegates to a timed drain that checks every 64 callbacks. A callback that crosses that budget followed by fewer than 64 queued callbacks still drains them before polling ready established sockets. The previous regression queued 64 pings and therefore only exercised the point where Netty does check.
REPRODUCER: New NoiseEstablishedIoCheckpointTest retains the real Host/TCP/Noise/Ping path and held 200ms callback, but queues just one further genuine ping and a marker. The established read must precede that marker. Existing queue test remains byte-for-byte unchanged; no existing assertion, limit, deadline or iteration edited.
INVARIANTS before policy refinement: worker owns per-drain budget state on its event-loop thread only; check deadline before taking each subsequent ordinary queued callback, leaving unpolled work in FIFO. Always allow a first task, including after a long scheduled-queue transfer. Restore previous budget state in finally so callback close/re-entry cannot leave a stale deadline. Delegate task execution, scheduled task transfer, counters, tail tasks and shutdown to Netty unchanged; inactive outside a timed drain. New short-queue ordering test plus existing 64-ping queue, lifecycle and original Noise class cover these paths. No public mutable resource or persisted state added.
PLAN: new test assertion-red with current implementation, precision refinement green, precision-only reversal red, restore; original class verification; first complete package/full gates and final PR if time permits. The p95 result still needs verification after the proposed refinement; this is not claimed as solved yet.

2026-10-22 04:41 UTC — short queue FIRST RED
OBSERVED: New NoiseEstablishedIoCheckpointTest: 0 passed / 1 failed / 0 skipped. Compiled and failed on the established-read-before-marker assertion with the current 100ms policy; not a compile/deadline failure. Full trace:
```
io.libp2p.security.noise.NoiseEstablishedIoCheckpointTest :: established reads run after the budget with fewer than sixty four queued callbacks()
org.opentest4j.AssertionFailedError: Exception in thread "main" org.opentest4j.AssertionFailedError: An established TCP read must run before the next queued callback batch after the transport task quantum ==> expected: <true> but was: <false>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at io.libp2p.security.noise.NoiseEstablishedIoCheckpointHarness.main(NoiseEstablishedIoCheckpointTest.kt:99)
 ==> expected: <0> but was: <1>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at app//org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at app//io.libp2p.security.noise.NoiseEstablishedIoCheckpointTest.established reads run after the budget with fewer than sixty four queued callbacks(NoiseEstablishedIoCheckpointTest.kt:35)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)

```

2026-10-22 04:45 UTC — policy review and current verification
OBSERVED: Current committed/pushed source 95455997. The short-queue first red is in the checkpoint history with its full trace. First fixed run acquired boxrun slot 2 at 04:43:51 after a queue; no CASE lines yet. Using Gradle --offline now that initial builds have resolved every test dependency; this changes build resolution only, with identical selectors, JVM settings and test contracts.
REVIEW (tests, independent pass): new short-queue case is separate; old 64-ping case is unchanged. Both force a callback across the existing 100ms policy with a latch await, real TCP writes and real Ping, then discriminate on read/marker order rather than a performance threshold. A short queue must expose the missing between-callback check, whereas the 64-item case reaches Netty's periodic check. Finally always releases the callback and closes both real hosts. First failure was the intended assertion.
REVIEW (code, independent pass): private worker's budget is confined to its event-loop thread. First task always runs; later polls check the inherited Netty monotonic clock before removing work, preserving queued FIFO. A finally restores the prior budget even for nested entry or exceptions. Delegate still owns scheduled transfer, execution, counters, tail tasks and shutdown; polling is unchanged outside a positive timed drain. No new public API, dependency, reflection, exception handling, limit change or global mutable state. Existing Noise close/re-entry tests and transport lifecycle checks remain verification gates.
OBSERVED: The earlier parallel-listen port failure is still unresolved. A bounded external observation helper is ready to capture socket rows and numeric owner/ancestor PIDs only while that test runs in the next broader selection/full suite. No process environments or thread/heap outputs are read/printed.

2026-10-22 04:48 UTC — first implementation build: zero tests
OBSERVED: short-queue-green-first exited 1 during compileKotlin, no CASE lines, 0 tests verified. Exact compiler output:
```
e: warnings found and -Werror specified
w: PlainNettyTransport.kt: (380, 64): 'getCurrentTimeNanos(): Long' is deprecated. Deprecated in Java
w: PlainNettyTransport.kt: (392, 38): 'getCurrentTimeNanos(): Long' is deprecated. Deprecated in Java
Execution failed for task ':libp2p:compileKotlin'.
A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
Compilation error. See log for more details
```
OBSERVED: Replaced the deprecated accessor with inherited ticker().nanoTime() for both deadline creation and comparison. No suppression, changed budget or dependency. The first executed fixed test remains pending. Full build log in out/short-queue-green-first.log.

2026-10-22 04:52:03 UTC
OBSERVED: short-queue-green-clock, source head f96f08c4efc33cdab24c51f00e1bd78d41124e0d: 1 passed / 0 failed / 0 skipped; 1 CASE lines, process exit 0.

2026-10-22 04:54:04 UTC
OBSERVED: short-queue-reversal, source head d8b632936a957e07e1353e2f6d79b5dfc40c59fe: 0 passed / 1 failed / 0 skipped; 1 CASE lines, process exit 1.
Full failure traces:
```
io.libp2p.security.noise.NoiseEstablishedIoCheckpointTest :: established reads run after the budget with fewer than sixty four queued callbacks()
org.opentest4j.AssertionFailedError: Exception in thread "main" org.opentest4j.AssertionFailedError: An established TCP read must run before the next queued callback batch after the transport task quantum ==> expected: <true> but was: <false>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at io.libp2p.security.noise.NoiseEstablishedIoCheckpointHarness.main(NoiseEstablishedIoCheckpointTest.kt:99)
 ==> expected: <0> but was: <1>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at app//org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:150)
	at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
	at app//io.libp2p.security.noise.NoiseEstablishedIoCheckpointTest.established reads run after the budget with fewer than sixty four queued callbacks(NoiseEstablishedIoCheckpointTest.kt:35)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)

```

2026-10-22 04:54:04 UTC
OBSERVED: Precision-only reversal failed the intended ordering assertion; exact fixed bytes restored. Queue budget, logging and cleanup changes retained throughout.

2026-10-22 04:55:49 UTC
OBSERVED: precision-restored-targeted, source head 5638106fa2eecfe3211fa4e5dfef2b55cac29bd0: 16 passed / 0 failed / 0 skipped; 16 CASE lines, process exit 0.

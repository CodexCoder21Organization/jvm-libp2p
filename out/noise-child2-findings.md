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

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

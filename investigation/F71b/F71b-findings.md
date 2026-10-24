FIX: PR71-ROUND-2
Started 2026-10-24 15:02 UTC. Push cutoff 16:02 UTC; final cutoff 16:10 UTC.
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71

Plan:
- [done] Disk check, repository lookup, fresh requested checkout, fetch/rebase develop, required review and source readings.
- [in progress] Add real peer-close/full-queue/recovery regression; run red, commit and push; fix, green, reversal, commit and push.
- [pending] Audit all Netty listener sites and add missing guards with controls.
- [pending] Correct FORK source coordinate and PR provenance/CI/review facts.
- [pending] First-attempt requested class set, XML counts, independent review passes, final push, PR PATCH, process shutdown.

OBSERVED: 24 GB free on /. Fork PR is OPEN at requested head 8eabea8649ac5bc301615dbea6a5f06ac8c477bc, base develop; fetch/rebase made no changes. Initial unqualified gh checkout selected the upstream repository and failed on its missing ref; explicit --repo corrected checkout before any edit/build.
OBSERVED: Read README, FORK, CLAUDE (no AGENTS), FL635-final, R71adv-final and complete R71adv-findings, reviewer Java demonstration. Downloaded current TESTING.md and PHILOSOPHY.md via authenticated GitHub API and read guidance. No nested agents, handoff changes, merge, queue, deploy or publish allowed.
INFER: The mechanism to fix is requestPhysicalClose adding a listener to an already completed channel close future when its executor cannot accept dispatch. The reproducer is two real hosts, peer close, event loop held, all 2048 queue slots filled, public Connection.close, drain plus fresh barrier, then assert close completion. Netty clamps the modern NIO queue minimum to 2048; child JVM configuration isolates that resource limit from other tests.

Invariant table before code:
| Resource | Creator/owner | Terminal states/close | Caller mutation/re-entry/overlap | Guard |
| Netty physical channel | transport/Netty | closed success once; failed request preserves cause | early close listener may re-enter close; terminal result independent of external dispatch | new completed-close test; existing pending-connect/retry guards |
| Owned close operation | first Connection/transport caller | original attempt retained; explicit retry only after failure | publish owner before callback; derived futures isolate caller changes | ParentCloseOwnershipTest, ConnectionCloseRetryTest, RetryCloseInactiveReviewTest |
| Completed converter result | converter, source owned by Netty | immutable success/failure/cancel source | duplicate transfer no-op; no blocking | CompletedNettyFutureConversionTest |
| Bounded event loop | isolated probe host, process owns daemon workers | queue recovers before assertion, hosts stopped finally | latches hold exact ordering, no artificial load | new completed-close test |

OBSERVED: 15:09 UTC first close-regression Gradle invocation remains in compileKotlin, bounded by timeout600. No result yet, no retry. Main path sweep found 20 direct addListener sites plus converter call paths. StreamOverNetty.writeAndFlushWithFuture is a second off-loop listener-only terminal-result path; resetForCapacity dispatches its operation on the channel event loop and catches rejected operation submission. Most handshake/mux observers are installed and completed on the same loop; audit will state that scope explicitly rather than claim arbitrary executor termination is covered.

OBSERVED: First Gradle command failed with exit124 at its unchanged600-second outer bound in compileTestKotlin, before any test/XML report. Full invocation/log: F71b-close-red.log. Full daemon record: F71b-first-build-daemon.log (client disconnection caused build cancellation; MessageIOException caused by java.io.IOException: Broken pipe). This is retained as a first-attempt build failure, not a regression assertion verdict and not silently rerun into green. Kotlin daemon2772711 remains owned and will be stopped. No production change has been made.

OBSERVED: 15:18 UTC. Owned Kotlin compiler2772711 exited after the cancelled build. A diagnostic attach raced that exit and reported java.io.IOException: non existent JVM pid:2772711 (full trace in tool record); no code conclusion derives from it. A second assertion-red command is now running with the same600-second bound, unchanged production, after the first command never reached tests. No failed test has been repeated into green.
OBSERVED: Complete registration sweep enumerated23 sites:20 explicit addListener registrations and3 PromiseCombiner constructions. Two are corrected converters; the inherited close branch, StreamOverNetty write conversion, and MuxChannel's redundant same-loop completion task need focused guards. Cleanup-only listeners are distinguished from result transfer; connect/bind/shutdown/quic write operations use the two corrected converters. Reentrant close guard has been added as a new test file; stream-write guard drafted outside checkout while the close red result is pending.

OBSERVED: WIP checkpoint pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/5dbd2dc8 (findings, test drafts, bounded runner), followed by https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d8365b5a (complete first-build failure records). No PR has been opened for WIP. Primary PR branch remains unchanged pending assertion-red.
OBSERVED: Independent test-design pass while compilation runs: close guard uses real peer EOF and physical-close observation before exact queue saturation, releases the loop, observes all2048 real tasks and a new barrier, then checks Java completion. No sleeps, load spinners, mocks, reflection, changed existing tests, weakened assertions or increased timeouts. Reentrant guard installs an early close observer before security setup and records the order relative to a later physical listener. New child45-second bound is below the total of separately bounded fixture operations and comfortably above the reviewer's11,436ms healthy-fixture/probe measurement; each individual wait remains bounded.

OBSERVED: Assertion-level red on unchanged production: {'name': 'io.libp2p.transport.CompletedConnectionCloseNotificationTest', 'tests': '1', 'skipped': '0', 'failures': '1', 'errors': '0', 'timestamp': '2026-10-24T15:24:14.907Z', 'hostname': '8bf01af06d2e', 'time': '9.608'}
Exact text: "A peer-closed connection must complete close after the event loop recovers". Full rejected listener stack points at NettyChannelClose.kt:48; recovery queue/barrier completed before this assertion. Native XML retained as F71b-close-red-assertion.xml and full log as F71b-close-red-assertion.log. Parent JUnit assertion reports child exit1 with complete child failure text.

OBSERVED: Test record {"label": "close-green", "class": "io.libp2p.transport.CompletedConnectionCloseNotificationTest", "exit": 0, "wall_ms": 53503, "xml": "F71b-close-green.xml", "total": 1, "failures": 0, "errors": 0, "skipped": 0, "test_ms": 8359}

OBSERVED: Test record {"label": "close-reversal", "class": "io.libp2p.transport.CompletedConnectionCloseNotificationTest", "exit": 1, "wall_ms": 41978, "xml": "F71b-close-reversal.xml", "total": 1, "failures": 1, "errors": 0, "skipped": 0, "test_ms": 9865}
Full failure record: F71b-close-reversal.xml and F71b-close-reversal.log

OBSERVED: Close test checkpoint https://github.com/CodexCoder21Organization/jvm-libp2p/commit/93f0180b757b4338aac4dd66ac05f9cd5118e276 pushed. Fix checkpoint https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ecafb3ab4b321b1b24be6c26b0c39356865f87e0 pushed after assertion-red9608ms and first fixed green8359ms. Reversing only NettyChannelClose restored the same failure9865ms. Reentrant same-loop ordering remains on the original listener branch; external terminal reads complete directly. Temporary controls are not committed.

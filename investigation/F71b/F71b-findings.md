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

# libp2p-fix progress

Start: 2026-10-21 17:37 UTC. Deadline: 18:57 UTC; checkpoint exit gate: 18:47 UTC.
Findings path was not supplied in the lane brief; this file is the lane's findings.

Plan:
- IN PROGRESS: read contract/source and port the real-host remote-reset reproducer; demonstrate assertion failure.
- TODO: implement terminal/repeated write-close handling in the shared child path; add all lifecycle cases for Yamux and Mplex.
- TODO: green/red/green verification, relevant mux/host packages, formatting and static analysis.
- TODO: probe snapshot version, update public docs, checkpoint; rebase on develop, targeted tests, single PR push/open against develop; stop without CI watch.

OBSERVED: read usage CONTRACT.md fully, FORK.md, CLAUDE.md, README.md, and Testing Architecture's Test-Driven Development and Tests Must Be Hermetically Sealed sections (via authenticated GitHub API; web fetch returned 404). Read the linked UrlProtocol findings and standalone reproducer. No skill directories accessed, no subagents, no other checkout used.
OBSERVED: Gradle/JUnit is this repository's native test suite; the lane's explicit Gradle instruction takes precedence over the general kompile script layout. Builds run through boxrun with Gradle commands. Develop, not main, is the requested integration base.
OBSERVED: root has 31 GiB free. Develop starts at b912e75c1028030bcb8427ef09703d724a972a2b.
OBSERVED: StreamOverNetty.closeWrite calls Channel.disconnect. MuxChannel.doDisconnect unconditionally visits parent.localDisconnect even after doClose removes the Yamux entry through onClosed/onChildClosed. Mplex shares MuxChannel and emits a CLOSE frame unconditionally.
INFER: the mechanism to fix is a child write-close transition that revisits mux state after full close or repeats an already-completed write-side close. The reproducer forces remote reset -> receiver closeFuture -> receiver closeWrite, without timing calibration.

## Invariants before implementation

| Resource | Creates / owns / closes | Terminal states and observable behavior | Public API test |
| --- | --- | --- | --- |
| TCP + Noise host pair | test / host / finally stop both | root close closes children; child close leaves root usable | real port-0 hosts, reopen stream on same connection after reset |
| Yamux/Mplex child | muxer / muxer / either peer | full close/reset terminal; closeWrite afterward succeeds without mux lookup or frame | remote reset, remote close, local close, parent close then closeWrite |
| Local write side | stream / child / closeWrite | first live call half-closes; repeated call has no extra transition; opposite direction stays readable | observe RemoteWriteClosed once, receive reply after local closeWrite, repeated call succeeds |
| Close callback | pipeline / child event loop / full close | callback re-entry must not revisit removed mux state | closeWrite in closeFuture callback |
| Buffers | caller transfers to Netty / channel / close or delivery | real writes still report their outcome; closing does not claim delivery | existing Yamux write-future and reset tests unchanged |

Contract choice: closed/repeated closeWrite is idempotent success, matching Netty close's terminal behavior and this Stream API's resource-close meaning. No claim of successful data delivery is added.

## 17:43 UTC
OBSERVED: snapshot-32, -33 and -34 POM probes returned 200; snapshot-35 returned 404. No publication authorized or attempted.
OBSERVED: first Gradle preparation is still running (new Gradle 8.14.3 distribution/cache in this checkout); no test result yet. Added lifecycle tests before any implementation change. All build tasks hold boxrun slot 3. Added an external init script for future runs to print passing/skipped test cases; the first run uses the repository's failed-only logging and XML will be reported explicitly.
OBSERVED: tests now include both muxers for remote reset, remote close, local close, parent close, remote half-close, live half-close with payload/reply, callback re-entry, and frame-count checks for repeated write-close/full close. Helpers are package-level. Every host test creates real TCP+Noise hosts on port 0, waits for explicit lifecycle signals, checks child-close leaves the same connection reusable, and stops both hosts preserving cleanup failures.
Current commit: b912e75c1028030bcb8427ef09703d724a972a2b (no implementation change yet).


## 17:46 UTC: initial baseline
OBSERVED: unchanged develop implementation compiled successfully. First run of StreamCloseWriteTest: 4 passed / 10 failed / 0 skipped (14 cases). Six failures were assertions: Yamux full-close/callback/remote-close/final half-close missing-entry errors; Yamux repeated half-close writeOnce failure; Mplex inactive-callback re-entry recursed through deactivate until StackOverflowError. Four failures were setup/lifecycle waits and are NOT counted as reproductions: remote-reset cases timed out obtaining the controller, parent-close cases need stack inspection. Full exception output: /tmp/libp2p-close-baseline.log; per-case XML was explicitly rendered by /tmp/libp2p-report-tests.py to /tmp/libp2p-close-baseline-results.log.
INFER: Mplex's callback re-entry is a second observable result of the same missing terminal/write-side guard: deactivate fires channelInactive before changing its state; closeWrite from that callback re-enters doDisconnect and deactivate. Setting the write side terminal before full-close callbacks should prevent that recursion.
NEXT: run only the exact remote-reset reproducer plus frame assertions against unchanged implementation, before adding the fix. This avoids treating setup waits as proof of the reset defect. No existing test edits.
Current implementation commit: b912e75c1028030bcb8427ef09703d724a972a2b.

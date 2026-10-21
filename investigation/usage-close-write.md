# libp2p-fix progress

Start: 2026-10-21 17:37 UTC. Deadline: 18:57 UTC; checkpoint exit gate: 18:47 UTC.
Findings path was not supplied in the lane brief; this file is the lane's findings.

Plan:
- DONE: read contract/source and port the real-host remote-reset reproducer; demonstrate assertion failure.
- DONE: implement terminal/repeated write-close handling in the shared child path; add all lifecycle cases for Yamux and Mplex.
- IN PROGRESS: green/red/green verification, relevant mux/host packages, formatting and static analysis.
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


## 17:49 UTC: focused baseline and cause confirmed
OBSERVED: focused unchanged-implementation run: 1 passed / 5 assertion failures / 0 skipped (6 cases), 51 seconds including Gradle preparation. Exact remote-reset Yamux reproducer now fails at assertDoesNotThrow with UnknownStreamIdMuxerException after receiver closeFuture completed; Mplex future succeeds. Frame tests fail for both muxers: Yamux repeated closeWrite sets writeOnce twice and closed closeWrite looks up the missing id; Mplex emits an extra close frame for both repeated and full-close cases. No setup failures in this run. Full log: /tmp/libp2p-close-reset-baseline.log.
OBSERVED: all four initial wait failures were obtaining the initial controller (same fixture line 171), not expiry of closeWrite. They remain reported separately from actual defect assertions.
OBSERVED: initial checkpoint commit failed due to missing clone-local author identity; set local Codex identity, committed 52269a52, fetched/rebased develop, and pushed https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/usage-libp2p-fix-ckpt. The shell had continued into the focused build after the failed commit/rebase; this was a process error. Develop remained b912e75c throughout and the successful rebase occurred while that build was preparing. Subsequent build scripts use set -e to prevent continuation after any failed prerequisite.

Remote-reset first assertion's full exception stack (JUnit XML):

```text
org.opentest4j.AssertionFailedError: Unexpected exception thrown: java.util.concurrent.ExecutionException: io.libp2p.mux.UnknownStreamIdMuxerException: Stream with id ae1a66fffe6bce35-00128fe5-00000003-4b067298e5f5f049-b979ef80/1 not found
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:152)
	at app//org.junit.jupiter.api.AssertDoesNotThrow.createAssertionFailedError(AssertDoesNotThrow.java:84)
	at app//org.junit.jupiter.api.AssertDoesNotThrow.assertDoesNotThrow(AssertDoesNotThrow.java:53)
	at app//org.junit.jupiter.api.AssertDoesNotThrow.assertDoesNotThrow(AssertDoesNotThrow.java:36)
	at app//org.junit.jupiter.api.Assertions.assertDoesNotThrow(Assertions.java:3199)
	at app//io.libp2p.mux.StreamCloseWriteTest$remoteResetThenCloseWrite$1.invoke(StreamCloseWriteTest.kt:36)
	at app//io.libp2p.mux.StreamCloseWriteTest$remoteResetThenCloseWrite$1.invoke(StreamCloseWriteTest.kt:33)
	at app//io.libp2p.mux.StreamCloseWriteTestKt.withCloseWriteStreams(StreamCloseWriteTest.kt:173)
	at app//io.libp2p.mux.StreamCloseWriteTestKt.withCloseWriteStreams$default(StreamCloseWriteTest.kt:137)
	at app//io.libp2p.mux.StreamCloseWriteTest.remoteResetThenCloseWrite(StreamCloseWriteTest.kt:33)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.Optional.ifPresent(Optional.java:178)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base@21.0.12.1/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base@21.0.12.1/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
Caused by: java.util.concurrent.ExecutionException: io.libp2p.mux.UnknownStreamIdMuxerException: Stream with id ae1a66fffe6bce35-00128fe5-00000003-4b067298e5f5f049-b979ef80/1 not found
	at java.base/java.util.concurrent.CompletableFuture.reportGet(CompletableFuture.java:396)
	at java.base/java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2096)
	at io.libp2p.mux.StreamCloseWriteTest$remoteResetThenCloseWrite$1.invoke$lambda-0(StreamCloseWriteTest.kt:37)
	at org.junit.jupiter.api.AssertDoesNotThrow.assertDoesNotThrow(AssertDoesNotThrow.java:49)
	... 42 more
Caused by: io.libp2p.mux.UnknownStreamIdMuxerException: Stream with id ae1a66fffe6bce35-00128fe5-00000003-4b067298e5f5f049-b979ef80/1 not found
	at app//io.libp2p.mux.yamux.YamuxHandler.getStreamHandlerOrReleaseAndThrow(YamuxHandler.kt:362)
	at app//io.libp2p.mux.yamux.YamuxHandler.getStreamHandlerOrThrow(YamuxHandler.kt:355)
	at app//io.libp2p.mux.yamux.YamuxHandler.onLocalDisconnect(YamuxHandler.kt:549)
	at app//io.libp2p.etc.util.netty.mux.AbstractMuxHandler.localDisconnect(AbstractMuxHandler.kt:354)
	at app//io.libp2p.etc.util.netty.mux.MuxChannel.doDisconnect(MuxChannel.kt:125)
	at app//io.netty.channel.AbstractChannel$AbstractUnsafe.disconnect(AbstractChannel.java:460)
	at app//io.netty.channel.DefaultChannelPipeline$HeadContext.disconnect(DefaultChannelPipeline.java:1366)
	at app//io.netty.channel.AbstractChannelHandlerContext.disconnect(AbstractChannelHandlerContext.java:593)
	at app//io.netty.channel.AbstractChannelHandlerContext.lambda$disconnect$5(AbstractChannelHandlerContext.java:609)
	at app//io.netty.util.concurrent.AbstractEventExecutor.runTask(AbstractEventExecutor.java:148)
	at app//io.netty.util.concurrent.AbstractEventExecutor.safeExecute(AbstractEventExecutor.java:141)
	at app//io.netty.util.concurrent.SingleThreadEventExecutor.runAllTasks(SingleThreadEventExecutor.java:535)
	at app//io.netty.channel.SingleThreadIoEventLoop.run(SingleThreadIoEventLoop.java:201)
	at app//io.netty.util.concurrent.SingleThreadEventExecutor$5.run(SingleThreadEventExecutor.java:1195)
	at app//io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
	at app//io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
	at java.base@21.0.12.1/java.lang.Thread.run(Thread.java:1583)

```

Mechanism to fix: MuxChannel.doDisconnect visits mux state even after full close or repeated local write-close; full-close callbacks may also re-enter disconnect before AbstractChildChannel marks itself closed. Reproducer: real TCP+Noise+Yamux remote reset, receiver closeFuture, then receiver closeWrite; two mux frame-count assertions cover the shared Mplex path.


## 17:52 UTC: fixed targeted run
OBSERVED: first fixed run passed all 18 new parameterized cases (18 passed / 0 failed / 0 skipped), including both real-host remote-reset tests, live payload/reply half-close, full-close callbacks and frame-count cases. No test retries. Full output: /tmp/libp2p-close-fixed.log.
OBSERVED: four-line shared MuxChannel change: return before onLocalDisconnect for !isOpen or localDisconnected, and set localDisconnected before full-close callbacks. No Yamux/Mplex protocol handler lookup or framing logic changed. Snapshot assigned: 35. Stream KDoc and README document the public behavior, with usage example.
OBSERVED: module-wide spotlessKotlinApply also corrected existing formatting in the files listed below. Verified identical source after normalizing whitespace and import order, and reviewed the diffs. These are formatting-only: before = existing indentation/line wrapping/import order/blank lines; after = repository ktlint output. No assertion, fixture call, timeout, iteration, or test behavior changed. Retained these mechanical corrections so the requested whole-module spotlessCheck can pass.

Existing production formatting-only edit:
- libp2p/src/main/kotlin/io/libp2p/host/HostImpl.kt

Existing-test formatting-only edits (before/after as above; required by module-wide formatting gate):
- libp2p/src/test/kotlin/io/libp2p/core/HostClosedStreamMultistreamReviewTest.kt
- libp2p/src/test/kotlin/io/libp2p/core/HostHandlerAddedCloseReviewTest.kt
- libp2p/src/test/kotlin/io/libp2p/core/HostNegotiationCoverageReviewTest.kt
- libp2p/src/test/kotlin/io/libp2p/core/HostStreamVisitorCloseTest.kt
- libp2p/src/test/kotlin/io/libp2p/core/LateControllerCompletionReviewTest.kt
- libp2p/src/test/kotlin/io/libp2p/core/NormalStreamLifetimeReviewTest.kt
- libp2p/src/test/kotlin/io/libp2p/core/ParentCloseListenerReviewTest.kt
- libp2p/src/test/kotlin/io/libp2p/etc/util/netty/ChildChannelTeardownIsUnconditionalTest.kt
- libp2p/src/test/kotlin/io/libp2p/host/NewStreamSurvivesStalePooledConnectionTest.kt
- libp2p/src/test/kotlin/io/libp2p/mux/yamux/YamuxNegotiationControllerOnStreamCloseTest.kt

NEXT: checkpoint fixed passing step; temporarily remove only the four implementation lines, then rerun focused remote-reset/frame tests to prove red again. Restore the fixed implementation before relevant package checks.
Current commit before passing checkpoint: e066669e.

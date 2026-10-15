# Lane 269 findings
Started 2026-10-15T17:37:55.041885+00:00

Plan:
1. IN PROGRESS: Read instructions, reference documentation, test and evidence; record mechanism before editing.
2. TODO: Run unchanged off-loop scenario 20 times; force its exact read-boundary condition and show old assertion failing.
3. TODO: Correct ordered-record assertion, retain forced-condition coverage and every paused assertion; push checkpoints.
4. TODO: Run fixed off-loop 20 times, class 5 times, mux package once; independent test and code review passes.
5. TODO: Rebase develop, open PR, record SHA and counts, stop own processes and remove checkout/cache.

OBSERVED: Read the complete brief and common.md, plus the shared handoff snapshot. Specific lane rules override global main-branch, full-suite, CI-watching and handoff-writing defaults. Base is develop; no nested agents; no handoff-cli; no publishing or merging; no CI watching.

## 17:39Z evidence reading milestone
OBSERVED: Fresh develop at current fork snapshot; README.md and FORK.md read. No repository AGENTS.md found. Read evidence branches lane246 (findings, fixture, task harness and XML) and lane237 (logging patch). The coalesced-record fixture is a DIFFERENT initialization failure: security negotiation activates twice when plaintext handshake records coalesce. It does not reproduce the payload-stage assumption. The brief conflates those two cases; I will force separate payload transport reads instead.
OBSERVED: Original off-loop test waits for all payload frames and FIN BEFORE resuming the child; first callback blocks its I/O loop, then child pause stops subsequent transport reads. Thus all frames cannot reach the muxer unless they arrived in that first read. Existing final assertion already compares the complete ordered payload list and FIN.
INFER (before editing): The mechanism I am about to fix is a test ordering error: after the first in-flight payload is paused, it waits for frames that may still need a parent TCP read, but parent reads are intentionally disabled until child resume. Evidence is lane246 raw trace (first payload only; later payloads written but unread) and this test's await-before-resume ordering. No evidence of dropped/reordered payloads.
Plan item 1 completed; item 2 in progress. Baseline batch uses 20 separate native Gradle Test tasks, both muxers each, unchanged source. Gradle one worker, in-process Kotlin compilation, JVM cap 2 GiB.

## Test resource invariants (before changing concurrency-related fixture code)
| Resource | Creation/ownership/close | Caller-visible contract and interleavings | Existing/new test coverage |
| --- | --- | --- | --- |
| Client/server hosts and TCP parents | Test creates two isolated hosts on port 0; nested finally always stops both. Parent shutdown terminal. No persisted state or restart. | Parent reads stop once queued off-loop child pause runs, restore after child resume. Callback blocks only on a bounded latch, released in finally. | Existing real-NIO assertions and parent-read assertions retained; event-loop barrier proves pause applied before snapshot. |
| Child inbound payloads and remote-end event | Real mux creates child; test callback consumes buffers through SimpleChannelInboundHandler. Parent teardown owns child. | At most in-flight/already-dispatched payload before pause reconciles; no later payload or FIN until resume; exact ordered full list then FIN. | Existing before-resume prefix/end-marker assertions and final equality retained; forced one-byte transport read variant added. |
| Test outbound buffers | Test allocates, transfers to sender write path, keeps current cleanup unchanged. | Sender writes all three distinct messages and FIN; failed writes fail the test. | Existing write-future checks retained. No production ownership change. |
| Synchronization latches/queues | Per invocation, test owns; I/O callbacks publish counts and ordered records. | Test thread never waits for future socket reads while parent reads are intentionally paused. Barrier after releasing callback observes completed pause task. | Move frame-count waits after resume; retain all delivery latches and exact list checks. |

OBSERVED: Reference reading: README.md, FORK.md; common.md and shared handoff; lane246 findings + fixture + trace patch + example XML and lane237 logging patch; PHILOSOPHY.md named reproduction/upstream/refutation sections; TESTING.md TDD, hermetic/public-API/real-dependency rules and full flake-policy/timeout sections; CODE_REVIEW.md relevant test bounds, assertions, artificial load, and work-in-progress file rules. Durable initial analysis: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane269-readpause-20261015 .

## 2026-10-15T17:42:58.543114+00:00 baseline progress
OBSERVED: Initial Gradle invocation remains active and has compiled production Kotlin/Java and test fixtures; unchanged ChildReadPauseTcpTest has not yet run. Source tree remains unchanged at ec74179a589bfb7137122f93906e03a24ca19714. Initial analysis checkpoint pushed as a22bdd6b to the evidence branch.
INFER: Exact forcing mechanism will be FixedRecvByteBufAllocator(1).maxMessagesPerRead(1), installed on the existing TCP parent after handshake completion. A single-byte NIO read that completes the first mux payload cannot contain any byte of a later frame, and the queued pause task runs before the next read. This uses real sockets/decoders with no sleep, load, mock or private API. The permanent parameter matrix will include native and fragmented transport reads for both muxers.

## 2026-10-15T17:47:36.460356+00:00 unchanged baseline milestone
OBSERVED: Unchanged develop ec74179a589bfb7137122f93906e03a24ca19714: 20/20 passed for mplex and 20/20 passed for yamux; zero failures/skips, Gradle exit 0, 8m08s including clean compilation. This does not negate prior lane246's recorded payload-stage failures: that trace forced no read boundary. XML and JSON archived under out/r269-xml/baseline and out/r269-baseline-result.json.
Next: add only the forced transport-read parameters and allocator; leave the old await-before-resume assertion intact, and run 10 repetitions before applying the prepared ordering correction.

## 2026-10-15T17:50:00.532555+00:00 forced-case setup
OBSERVED: Committed source-only forced-read case as 5f351a95 (old await-before-resume still intact). CsvSource includes original native reads plus fragmented reads for each muxer; only new allocator/configuration path added. Running 10 native Gradle test tasks against that commit after successful fetch/rebase origin/develop. The first launch was stopped during Gradle configuration because I had attempted rebase with uncommitted changes; no tests ran in that discarded launch. Corrected by committing the fail-first fixture and restarting after a successful rebase.
OBSERVED: Existing baseline XML also contains ReferenceCountUtil.safeRelease warnings from the unchanged outbound-buffer cleanup; they are not JUnit failures and this work leaves that cleanup untouched.

## 2026-10-15T17:52:44.920706+00:00 forcing experiment refuted and corrected
OBSERVED: First allocator attempt (configured after negotiation) passed 10/10 native and 10/10 purported fragmented cases for each muxer. It is NOT a reproducer. Netty's actual 4.2.10.Final AbstractChannel.AbstractUnsafe.recvBufAllocHandle() bytecode assigns config.getRecvByteBufAllocator().newHandle() only when recvHandle is null; subsequent reads keep the first handle. Thus changing config after handshake does not change read boundaries. Archived under out/r269-xml/late-allocator.
OBSERVED: ConnectionBuilder.initChannel installs secure multistream pre-handlers before negotiation; using the existing beforeSecureHandler visitor allows configuring the real TCP parent before its first socket read. Moved the allocator there; restored the original parent auto-read setup unchanged. No test-contract correction applied yet.
INFER: This corrected setup forces one-byte reads throughout this one receiver connection; the original await-before-resume must now fail on the payload stage.

## 2026-10-15T17:57:28.755297+00:00 first reliable failing reproduction
OBSERVED: Corrected before-first-read allocator fixture fails both muxers at the original payload-stage await in completed repetitions 1 and 2; native parameters pass in those same runs. Failure text: `not every payload frame reached the muxer while the child was paused ==> expected: <true> but was: <false>` at ChildReadPauseTcpTest.kt:199. Full stack traces in the Gradle log and XML; no initialization failure. Ten-run measurement still active. Fail-first source pushed at b77a4c97 on https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/lane269-readpause-test-contract .
INFER: The forced condition matches the earlier raw trace: first frame delivered, later frames require transport reads that the child's pause stops. This is the await-before-resume test ordering error, not dropped/reordered records. Final proof will require the exact complete ordered list on unchanged production after resume.

## 2026-10-15T17:59:56.870644+00:00 deterministic reproduction complete
OBSERVED: Corrected allocator fixture b77a4c97, unchanged production: ten runs per parameter completed, counts:
```json
{
  "io.libp2p.mux.ChildReadPauseTcpTest mplex, fragmented transport reads=false": {
    "pass": 10,
    "fail": 0,
    "skip": 0
  },
  "io.libp2p.mux.ChildReadPauseTcpTest yamux, fragmented transport reads=false": {
    "pass": 9,
    "fail": 1,
    "skip": 0
  },
  "io.libp2p.mux.ChildReadPauseTcpTest mplex, fragmented transport reads=true": {
    "pass": 0,
    "fail": 10,
    "skip": 0
  },
  "io.libp2p.mux.ChildReadPauseTcpTest yamux, fragmented transport reads=true": {
    "pass": 0,
    "fail": 10,
    "skip": 0
  }
}
```
All failures are the original payload-stage await: `org.opentest4j.AssertionFailedError: not every payload frame reached the muxer while the child was paused ==> expected: <true> but was: <false>`; no setup/negotiation errors or skipped cases. Full stack traces are in out/r269-forced-old-result.json and out/r269-xml/forced-old.
Plan item 2 complete; item 3 in progress. The test-contract fix is still unapplied.

### Full first failure stack trace
```
org.opentest4j.AssertionFailedError: not every payload frame reached the muxer while the child was paused ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.mux.ChildReadPauseTcpTest.offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume(ChildReadPauseTcpTest.kt:199)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.Optional.ifPresent(Optional.java:178)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base@21.0.12.1/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base@21.0.12.1/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base@21.0.12.1/java.util.stream.ReferencePipeline$Head.forEach(ReferencePipeline.java:762)
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

```

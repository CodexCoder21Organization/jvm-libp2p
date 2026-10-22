# p2p63-fix3 findings

PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63
Start: 2026-10-22 00:12 UTC; limit 60 minutes.

Plan:
- [complete] Read contract, review report, README, repository guidance, and requested testing guidance.
- [in progress] Add copy-release observations through the existing public releaseMessage override; compile test classes once.
- [pending] Demonstrate cleanup mutation fails, restore, run mux classes, repeat mutation and restore.
- [pending] Run spotlessCheck/detekt, review diff, checkpoint, rebase develop, rerun targeted gate, push existing PR once, append Review round 2.

OBSERVED: Initial HEAD d933ef4c2a4edc43e5d47b0345014c7dbba9994a; clean branch fix/yamux-per-stream-pause. Existing PR is open against develop. Fetch/rebase origin/develop completed without changing HEAD. Disk 48 GiB free.
OBSERVED: Read CONTRACT.md fully, out/rv-p2p63-tests-r2-findings.md, README.md, CLAUDE.md, FORK.md, and TESTING.md sections Test-Driven Development and Tests Must Be Hermetically Sealed. Lane-specific scope is test-only cleanup coverage, not the five-repository feature.
OBSERVED: Yamux queues Unpooled.copiedBuffer(data) and releases input immediately. Existing assertions on allocatedBufs therefore do not observe queued copies. The current cleanup correctly calls public releaseMessage for pending copies.
INFER: A delegating override of public releaseMessage can record actual queued copies without accessing private state or changing production code. Assert the release count, identities distinct from every input, and refCnt zero after each public close/reset operation. Missing cleanup must fail the count assertion; missing buffer release must fail refCnt.

Ownership invariants:
| Resource | Creator / owner | Close rule | Observation / public test |
| --- | --- | --- | --- |
| Original input | fixture allocates, fixture holds one reference | Yamux releases its reference while copying | existing allocatedBufs assertions stay unchanged |
| Queued copy | Yamux copy, paused child queue owns it | remote reset releases every copy without delivery | new parameterized reset case |
| Queued copy | Yamux copy, paused child queue owns it | local child close releases every copy without delivery | new parameterized child-close case |
| Queued copy | Yamux copy, paused child queue owns it | parent close releases every copy without delivery | new parameterized parent-close case |
| Copy observation | test records references without retain | cleanup finally releases leftover buffers on deliberate mutation | no production ownership changed |

Current commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## 00:15 UTC — test design ready
OBSERVED: Added a delegating public releaseMessage override in YamuxHandlerTest plus one three-case parameterized test. Each case fills two paused children with three copies each, then resets both, closes both children, or closes the parent. Assertions require six release observations, no identity shared with any original, refCnt zero on every copy, unchanged original-reference count, no delivery, and closed children. No existing assertion edited; Mplex fixture/source untouched. README unchanged because public behavior is unchanged (test-only scope).
INFER: This tests cleanup through public channel/reset actions and a public override; it does not inspect private queue state. Missing cleanup fails the exact release count even when original inputs already look released.
Correction to the initial ownership table: observations do not retain buffers; deliberately omitted releases leave six small copies only in the short-lived test worker, without a test-owned reference to release. Normal paths release every copy in production.
Current commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a; test edits uncommitted pending first result.

## 00:18 UTC — compilation progress
OBSERVED: One owned warm Gradle daemon, PID 1452038. Production and fixture compilation completed; compileTestKotlin active. No tests executed and no pass claimed. Disk 47 GiB.
INFER: In addition to the exact review mutation (skip both queue release loops for Yamux), a second mutation omitting msg.release inside public releaseMessage for Yamux can directly establish the refCnt-zero assertion fails even when all six release observations exist. Both will be restored; no production diff will remain.
Current commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## First test execution — cleanup omission is red
OBSERVED: Test classes compiled successfully on first attempt (3m48s). Saved hashes of 1,166 compiled Kotlin test/fixture classes. No per-test result was claimed for compilation.
OBSERVED: Applied the exact review two-line mutation, skipping both AbstractMuxHandler queued-release loops only for Yamux. First mux-class execution: {'passed': 184, 'failed': 3}. All three new close modes fail their exact queued-copy release-count assertion (expected six, observed zero); Mplex coverage passes. Actual per-test results and full failure details follow. No compile error counted as red.

```text
cleanup-control {'passed': 184, 'failed': 3} total 187
Mplex {'passed': 45}
failed io.libp2p.mux.yamux.YamuxHandlerTest [1] reset
java.lang.AssertionError: [Queued copies released on reset] 
Expected size: 6 but was: 0 in:
[]
	at io.libp2p.mux.yamux.YamuxHandlerTest.closingPausedStreamsReleasesEveryQueuedCopy(YamuxHandlerTest.kt:179)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

failed io.libp2p.mux.yamux.YamuxHandlerTest [2] child close
java.lang.AssertionError: [Queued copies released on child close] 
Expected size: 6 but was: 0 in:
[]
	at io.libp2p.mux.yamux.YamuxHandlerTest.closingPausedStreamsReleasesEveryQueuedCopy(YamuxHandlerTest.kt:179)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

failed io.libp2p.mux.yamux.YamuxHandlerTest [3] parent close
java.lang.AssertionError: [Queued copies released on parent close] 
Expected size: 6 but was: 0 in:
[]
	at io.libp2p.mux.yamux.YamuxHandlerTest.closingPausedStreamsReleasesEveryQueuedCopy(YamuxHandlerTest.kt:179)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.Optional.ifPresent(Optional.java:178)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.accept(ForEachOps.java:184)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.Spliterators$ArraySpliterator.forEachRemaining(Spliterators.java:1024)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.stream.ReferencePipeline$7$1.accept(ReferencePipeline.java:276)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.stream.ReferencePipeline$3$1.accept(ReferencePipeline.java:197)
	at java.base/java.util.ArrayList$ArrayListSpliterator.forEachRemaining(ArrayList.java:1708)
	at java.base/java.util.stream.AbstractPipeline.copyInto(AbstractPipeline.java:509)
	at java.base/java.util.stream.AbstractPipeline.wrapAndCopyInto(AbstractPipeline.java:499)
	at java.base/java.util.stream.ForEachOps$ForEachOp.evaluateSequential(ForEachOps.java:151)
	at java.base/java.util.stream.ForEachOps$ForEachOp$OfRef.evaluateSequential(ForEachOps.java:174)
	at java.base/java.util.stream.AbstractPipeline.evaluate(AbstractPipeline.java:234)
	at java.base/java.util.stream.ReferencePipeline.forEach(ReferencePipeline.java:596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

```
OBSERVED: All 1,166 compiled test/fixture hashes unchanged during control. Restoring the exact production source before the green run.
Current commit: d933ef4c2a4edc43e5d47b0345014c7dbba9994a.

## First restored run — green
OBSERVED: Exact production cleanup restored. First restored mux gate: 187 passed / 0 failed / 0 skipped, including all three new cases and 45 MplexHandlerTest cases. Per-test lines and XML agree. Compiled test/fixture hashes unchanged (1,166). Only test source differs from starting HEAD.
OBSERVED: Dedicated test review and code review pass: public releaseMessage override delegates unchanged, uses no retain; real muxer and embedded child channels, no private-state access/reflection/service dependency; six distinct input-vs-copy checks plus refCnt assertions; no resume before close; no existing assertion, iteration, timeout, or limit changed. README public contract unchanged, so no README edit needed.
Plan: compile and first red/green complete; checkpoint next, then refCnt control and restored gate, static checks, final develop rebase/gate and one PR push.

Current passing test commit: 237761a3743a0dd30119fea983e46f95a97ccdd9.

## Direct reference-count control — red again
OBSERVED: Passing test-only commit checkpointed to wip/usage-p2p63-fix3-ckpt with findings, without updating the PR. Then changed only MuxHandler.releaseMessage body temporarily: release only when pauseParentReadsForPausedChild is true. For Yamux the delegating public test hook still sees all six actual copies, but none is released. Mplex release is unchanged.
OBSERVED: First direct-refCnt control: 45 passed / 3 failed / 0 skipped. All three new cases pass count and identity checks, then fail refCnt zero with actual 1. All 45 MplexHandlerTest tests pass. Full failures below. All compiled test/fixture hashes unchanged (1,166).
```text
refcount-control {'passed': 45, 'failed': 3} total 48
Mplex {'passed': 45}
failed io.libp2p.mux.yamux.YamuxHandlerTest [1] reset
org.opentest4j.AssertionFailedError: [Queued copy after reset] 
expected: 0
 but was: 1
	at app//io.libp2p.mux.yamux.YamuxHandlerTest.closingPausedStreamsReleasesEveryQueuedCopy(YamuxHandlerTest.kt:182)
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

failed io.libp2p.mux.yamux.YamuxHandlerTest [2] child close
org.opentest4j.AssertionFailedError: [Queued copy after child close] 
expected: 0
 but was: 1
	at app//io.libp2p.mux.yamux.YamuxHandlerTest.closingPausedStreamsReleasesEveryQueuedCopy(YamuxHandlerTest.kt:182)
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

failed io.libp2p.mux.yamux.YamuxHandlerTest [3] parent close
org.opentest4j.AssertionFailedError: [Queued copy after parent close] 
expected: 0
 but was: 1
	at app//io.libp2p.mux.yamux.YamuxHandlerTest.closingPausedStreamsReleasesEveryQueuedCopy(YamuxHandlerTest.kt:182)
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

```
OBSERVED: Restoring exact production source now. No production edit will be committed.
Current commit: 237761a3743a0dd30119fea983e46f95a97ccdd9.

## Second restored gate and static checks — pass
OBSERVED: First restored run after direct-refCnt control: 187 passed / 0 failed / 0 skipped; all 45 Mplex cases pass. spotlessCheck and detekt both pass on first attempt. Entire command BUILD SUCCESSFUL (2m26s). All 1,166 compiled test/fixture hashes unchanged. Production restored exactly, working tree clean. No full suite run.
OBSERVED: Only round change to existing fixture: YamuxHandlerTest.createMuxHandler inherits releaseMessage before; after, its public delegating override records actual release buffers. No existing test method/assertion edited, skipped, weakened; no timeout/iteration/limit changes.
Plan: test gap, both red controls, both green restorations, dedicated reviews, static checks complete. Remaining: develop rebase, requested pre-push mux gate, one existing PR branch push, body update, final process cleanup.
Current commit: 237761a3743a0dd30119fea983e46f95a97ccdd9.

## Pre-push gate — cached invocation verifies nothing
OBSERVED: Fetch/rebase origin/develop completed; HEAD remains 237761a3743a0dd30119fea983e46f95a97ccdd9 and origin/develop is an ancestor. First pre-push test invocation reports test UP-TO-DATE with zero per-test result lines. This verifies no tests and is not counted as a pass. Running again with Gradle task-specific --rerun (without rebuilding test classes) to execute all selected cases.

## Final pre-push execution — pass
OBSERVED: Explicit task-specific --rerun executed 187 tests: 187 passed / 0 failed / 0 skipped, including all three new cleanup cases and 45 Mplex cases. Actual per-test log counts match XML. All 1,166 compiled Kotlin test/fixture hashes unchanged since the one successful compilation. Same owned warm daemon 1452038 throughout. No full suite run.
OBSERVED: Final diff from pinned head contains only 39 added test lines in YamuxHandlerTest.kt. Working tree clean, no added artifacts. Production sources unchanged. Static checks passed first attempt.
Current commit: 237761a3743a0dd30119fea983e46f95a97ccdd9.

## Final delivery and cleanup
OBSERVED: Existing PR branch pushed exactly once, d933ef4c → 237761a3743a0dd30119fea983e46f95a97ccdd9. git ls-remote independently confirms that final head. PR body patched through REST with Review round 2, first-attempt counts, full findings link, all fixture edits, and required footer. Its first line remains exactly `do not merge: awaiting owner approval of the existing-test edits listed below`. The PATCH response still carried the old head metadata; the remote Git branch itself is verified at the new head. No CI status was requested or watched.
OBSERVED: Owned Gradle daemon 1452038 stopped by numeric PID after every tracked build/test command completed. ps -eo pid,args -ww confirms it is absent; before/after snapshots saved in /tmp/p2p63-fix3-*-cleanup.ps. No background process started by this task remains. Working tree clean; no build artifacts tracked.

Brief audit:
- DONE: queued-copy release asserted for reset, child close, parent close, two paused streams with three copies each.
- DONE: exact review cleanup mutation fails all three new cases; restore passes 187/187.
- DONE: additional direct-release omission fails all three refCnt-zero assertions; restore passes 187/187.
- DONE: Mplex coverage unaffected, 45/45 in both controls and every green gate.
- DONE: test-only diff, existing assertions/limits/timeouts/iterations unchanged; the only fixture change is a delegating public releaseMessage observation.
- DONE: one warm Gradle daemon; test classes compiled once; unchanged 1,166 class hashes throughout.
- DONE: spotlessCheck and detekt first attempts pass; final develop rebase and actual 187-case rerun pass (first cached invocation verified zero tests, explicitly recorded).
- DONE: single existing-PR push and Review round 2 body update, preserving do-not-merge first line.
- DONE: findings checkpointed outside PR diff and final process cleanup verified.
Published coordinates: none. No merge, queue entry, deployment, production change, or CI watch. Unfinished: none within the requested lane scope.
Final head: 237761a3743a0dd30119fea983e46f95a97ccdd9.
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63

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

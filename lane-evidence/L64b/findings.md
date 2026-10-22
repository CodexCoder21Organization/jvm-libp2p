# L64b — Yamux resume at frame bound

WHY: The owner asked for end-to-end tests that find bugs, then fixes. A UrlResolver test exposed connection-wide stalls when one Yamux stream paused; https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63 (merged) made pausing per-stream; https://github.com/CodexCoder21Organization/jvm-libp2p/pull/64 bounds what a paused stream may hold while the connection keeps reading control frames.

## Plan
- [in progress] Read required guidance, review evidence, and queue/lifecycle implementation; audit off-loop access.
- [pending] Add the real TCP resume-order regression alone, confirm failure at original head, commit and push red checkpoint.
- [pending] Drain readable FIFO before admission decisions, preserve bounds and lifecycle behavior; review tests and code independently.
- [pending] Five first-attempt mux-family runs, full suite once under machine lock, compare exact 20 baseline failures with develop evidence.
- [pending] Push final commits, update PR body through API and read back; monitor permitted CI for at most 90 minutes; final process cleanup.

## Required reading
Read REV2.md and its new numbered Yamux finding, plus supplied YamuxResumeAtFrameBoundTcpReviewTest.kt. Read repository README.md and FORK.md. Read DocumentationRepository architecture/TESTING.md (including extreme conditions, deterministic reproducer, public API and test isolation) and PHILOSOPHY.md. Copies saved in out/L64b-evidence. Repository has no AGENTS.md.

OBSERVED 2026-10-22 05:04 UTC: Fresh clone in workspace/L64b; branch head 1d31d81c0c19c0e378ceae4d0136c3a9708c658f matches brief. PR is OPEN, targets develop, kotlin.build (remote) and kotlin.build (kompile-remote-build) both SUCCESS at start. No merging, enqueuing, artifact publishing, deployment, service restart or remote-run changes authorized.

OBSERVED: This is a Gradle repository; scripts/build.bash, scripts/test.bash and build.kts do not exist. The literal runner commands in the common brief cannot execute. Use the repository's actual Gradle test task, no retry layer, under the same machine-wide flock for full suites. The repository default branch is develop, not main; fetch/rebase targets origin/develop.

## Invariants before implementation
| Resource | Creator/owner | Release/terminal | Re-entry, close and public caller behavior | Public verification |
| --- | --- | --- | --- | --- |
| Pending payload FIFO | parent event loop, per child | delivered ByteBuf belongs to child pipeline; close/reset releases every held payload | Off-loop config may become readable before scheduled drain; old FIFO must precede new payload; callback re-pause stops drain | New real TCP frame-65 gate; existing FIFO and callback re-pause/FIN cases |
| Paused retention budget | parent event loop | 64 frames and receive-window bytes; overflow resets only child | Bound checked only for retained paused data after any readable FIFO drains; overflow release precedes failure callbacks | Existing exact 63/64/65 storage, coalesced overflow, many-full-window and failure-callback cases |
| Child lifecycle | parent loop / child | full close/reset terminal; FIN ordered after data | Close during drain must prevent newly-arrived message delivery and release its ownership; reset still processed while paused | Existing saturated-close and remote-reset TCP cases; review new drain-before-admission path |
| Parent reads | mux connection | parent close terminal | Yamux never pauses parent for a child; Mplex restores saved parent setting; off-loop config setter schedules bookkeeping | Existing TCP reply/control/reset cases and Mplex mux family |

INFER: The mechanism to fix is queue-limit admission before the readable FIFO drain. The reproducer forces the exact ordering: 64 queued one-byte frames; decoded frame 65 held before mux; off-loop autoRead=true; release frame 65 before scheduled drain. It should fail with actual=0 and closed child on the requested head and deliver all 65 bytes FIFO after the fix.

OBSERVED 2026-10-22 05:07 UTC: Test-only real TCP reproducer fails on first and only attempt at original implementation, with exact expected message: `Resuming before the next frame reaches Yamux must deliver all 65 bytes; actual=0 childOpen=false`. Full stack in L64b-evidence/red.log and red-xml XML. Gradle test task has no retry plugin; tests=1, failures=1, errors=0, skipped=0, time=5.507s. Added FIFO byte identity assertion as well as count. First test-only commit pushed to https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64b-resume-frame-bound-red before implementation changes.

OBSERVED: local git identity was unset on fresh clone. Set repository-local identity to existing project commit identity, then committed and pushed the test checkpoint; no global configuration changed.

OBSERVED 2026-10-22 05:10 UTC: Both additional real TCP scenarios failed on their first and only attempts before implementation changes (callback re-pause:5.064s; callback close:6.116s), each because no held payload reached the callback. Their full stacks and XML are in adjacent-red.log and adjacent-red-xml. Both tests committed separately at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/402bf7e1 and pushed to the red WIP branch. The close case also checks the gated arriving ByteBuf is released after callback close.

OBSERVED: Initial drain-before-admission fix made all three scenarios pass on attempt1; formatting applied. During the code review pass, strengthened the flag/queue decision into a loop: sample one autoRead flag and queue; if readable with a pending FIFO, drain, then re-evaluate callback pause/close before admission. This avoids a second off-loop flag read selecting a still-full queue after an earlier false sample. A close in the drain releases the not-yet-admitted frame. No storage limit, parent-read behavior, error message, timeout or iteration count changed.

## Off-loop autoRead / queue audit
OBSERVED: Searched all production netty/mux and mux sources for isAutoRead, pendingInbound and messages.size. AbstractChildChannel.childConfig.setAutoRead is the sole off-loop read-setting entry; Netty DefaultChannelConfig owns the atomic autoRead value. MuxChannel.onAutoReadChanged only calls parent.childAutoReadChanged. childAutoReadChanged reads flags and queues inside its Runnable on the parent executor; its outer off-loop path does not inspect queue length or mutate the queue. Both child and parent share the registered event loop.

OBSERVED: AbstractMuxHandler.childRead reads flag/queue/count while processing a decoded parent frame. drainPendingInbound reads flag on every payload and owns pop/byte accounting on that loop; its draining guard handles callback re-entry. onRemoteDisconnect reads flag/queue on parent-frame handling and drains before FIN delivery. pauseChild reads child and parent autoRead inside parentReadLock, called only from childRead or the scheduled config Runnable. restoreParentReadsIfPossible reads no queue and restores parent configuration under that lock. channelUnregistered and onClosed release queues on the channel event loop. The parent's close fallback during rejected registration is inherited shutdown behavior, not a new normal off-loop admission path. No public API exposes the queue or its length. No isAutoRead read exists in YamuxHandler or MplexHandler themselves. The only queue-length comparison in this inbound surface is childRead's admission limit; its error text uses the same parent-loop-owned queue.

INFER: No other normal TCP path reads queue length off-loop; the bug is ordering between Netty's public atomic flag and later parent-loop reconciliation, not simultaneous mutation of the FIFO from two normal TCP threads. EmbeddedChannel test loops can report inEventLoop on their callers, so the real TCP gate is necessary to test this ordering.

## Takeover by Opus (2026-10-22, after sol run stopped by automated content check)
OBSERVED 05:2x UTC: Local branch has 3 unpushed commits over PR head 1d31d81c: 7f10658d (resume-at-bound TCP test), 402bf7e1 (callback re-pause and callback close TCP tests), c0cdaada (drain-before-admission fix + README). Merge base equals origin/develop b01e08e5, no rebase needed. PR 64 still OPEN at 1d31d81c. Checkpoint pushed to https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64b-fix-checkpoint.
OBSERVED: Reviewed fix loop. Termination: drainPendingInbound returns only when the child closes, autoRead reads false, or the FIFO is empty (empty FIFO is removed from pendingInbound), so each `continue` either finds queued==null (deliver directly), autoRead==false (paused admission with limit), or a closed child (release frame, return).
INFER: Remaining edge: if childRead were re-entered while a drain is in progress (queued.draining == true) the frame is appended under the limit check while readable. No normal TCP path re-enters the parent decoder from a child read callback (NIO parent read() only sets interest ops), so this is not reachable on real transports; noted, not changed.
OBSERVED: memory.current 10.20 GB of 10.74 GB max at start of verification (other lanes' kompile JVMs and codex processes); anon 7.08 GB, file cache 2.28 GB. Running mux family 5x with --max-workers=2.
OBSERVED 05:15 UTC: Mux family (io.libp2p.mux.* plus io.libp2p.etc.util.netty.mux.*) on c0cdaada, five separate `gradlew :libp2p:test --rerun` invocations, no retry layer: 25 classes, 208 tests, 0 failures/errors/skips in each of runs 1-5. Includes all three YamuxResumeAtFrameBound*TcpTest cases. XML in L64b-evidence/mux-runs/xml1..5.
OBSERVED: spotlessCheck + detekt pass on c0cdaada.
OBSERVED: Baseline 20 full-suite failures (from wip/l64-handoff-evidence-20261022 failure-stacks.txt) saved to L64b-evidence/baseline-20.txt: TLS SSL_CTX (TlsSecureChannelTest x7, HostTestJava x4, QuicServerTestJava), BindException fixed ports (Gossip* x6, RpcHandlerTest), QuicKuboTestJava connection refused.
Full suite started under flock at ~05:17 UTC.

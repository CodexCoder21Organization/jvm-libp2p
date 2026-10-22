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

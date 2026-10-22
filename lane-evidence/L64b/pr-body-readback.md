The owner asked: "Then, do another round, Your goal is to add end-to-end integration tests which identify bugs (the tests should initially fail), and then fix the bug in the implementation." A UrlResolver test exposed connection-wide stalls when one Yamux stream paused. https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63 is now merged into develop and made pausing per-stream; this PR bounds how many payload frames a paused stream may retain.

Rebased onto develop and retargeted this PR to develop, retaining `preparePendingChildRead`, exact-sized queued copies, and their storage regression tests.

The previous frame threshold stopped parent TCP reads, which also stopped incoming window updates and remote reset frames. Both committed TCP regressions failed before the implementation change: a 4 MiB reply received only 262,091 bytes and remained pending, and a remote reset left the paused receiver open. Failing-test checkpoint: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64-control-traffic-red.

A paused Yamux stream now retains at most 64 payload copies and its receive-window bytes. Before frame 65 is copied, the muxer releases the incoming payload and all held copies, reports the attempted frames/bytes and both limits locally, resets that stream, and fails any pending reply with the detailed cause. The peer receives the normal Yamux RST frame; Yamux RST carries no text. No payload is silently dropped from a live stream. Parent reads remain enabled for window updates, resets, FIN, sibling data, and new stream negotiation. Mplex retains its connection pause behavior.

Tests cover successful complete replies and remote reset at 64 queued frames, terminal reset above the limit with 1,000 one-byte writes, exact retained counts and capacities at 63/64/65, 256 coalesced frames followed by sibling data, 16 streams at both storage limits, two saturated streams closed through local close/remote reset/parent close, FIFO resume, callback re-pause with FIN after held input, and failure-callback re-entry. The TCP close test waits until all 64 frames are admitted. The existing backing-storage test retains its 100 iterations and exact copy/message assertions with a test-only 128-frame fixture allowance. The existing 65-frame window-accounting test likewise keeps all iterations and assertions with that explicit test-fixture allowance. Both isolate their original contracts from the default admission limit; the new bounds fixture uses the unmodified production limit of 64.

### Resume ordering at the frame bound (added in this revision)

A review of `1d31d81c` found that a stream resumed from another thread could still be reset at the frame bound. `child.config().isAutoRead = true` takes effect immediately, but the drain of held frames is scheduled on the connection's event loop. If a DATA frame that was already decoded reached Yamux before that drain ran, `childRead` compared it against the old 64-frame queue, reset the stream and released every held payload, although the stream was readable. Real-TCP reproducer: pause, hold 64 one-byte frames, hold decoded frame 65 just before the mux handler, resume from the test thread, release frame 65. On `1d31d81c` it failed with `Resuming before the next frame reaches Yamux must deliver all 65 bytes; actual=0 childOpen=false`. Failing-test checkpoint: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64b-resume-frame-bound-red.

`childRead` now drains a readable stream's held frames in order before it decides anything about the arriving frame. The 64-frame and receive-window limits apply only when the stream is still paused after that drain. A read callback that pauses again during the drain leaves the remaining frames queued, and the arriving frame is then checked against the limit. A callback that closes the stream causes the arriving frame to be released. Control frames are still read while a stream is paused, and resets are still processed.

New real-TCP tests (each failed on its first attempt at `1d31d81c`, before the implementation change):
- `YamuxResumeAtFrameBoundTcpTest`: resume before frame 65 reaches Yamux delivers all 65 bytes in FIFO order.
- `YamuxResumeAtFrameBoundRePauseTcpTest`: a callback re-pause during the drain leaves room for the arriving frame.
- `YamuxResumeAtFrameBoundCloseTcpTest`: a callback close during the drain releases the arriving frame.

Other places that read `autoRead` or the queue length were checked. The config setter only schedules bookkeeping on the parent event loop. The scheduled resume task, the drain, remote FIN handling and close cleanup all run on that loop. No public API exposes the queue length. `childRead` was the only place that made a limit decision from a flag that could be newer than the queue.

Validation at `1b9c76e8d6943ee2152e74a0228edbde84dc4a41` (same tree as `c0cdaada`):

- Five separate first-attempt runs of the whole mux family (`io.libp2p.mux.*` and `io.libp2p.etc.util.netty.mux.*`): **208/208 passed in every run**, with zero failures, errors or skips. Gradle has no retry layer here. No timeout or iteration count was changed.
- `spotlessCheck` and `detekt` pass.
- Full suite under the machine-wide lock: one `./gradlew test --rerun --no-daemon --max-workers=2` holding `flock /tmp/claude-501/complete-suite.lock`: **1,179 tests (1,176 earlier plus the 3 new ones): 1,157 passed, 20 failed, 2 existing skips**. The 20 failing tests are exactly the same set that failed at `1d31d81c`, with the same three causes: native TLS `failed to create an SSL_CTX`, fixed-port `BindException: Address already in use`, and a local Kubo `Connection refused`. All mux and Yamux tests passed. Gradle stopped at `:libp2p:test`, so later subproject tests did not run.

Earlier validation at `1d31d81c` (before this revision), kept for reference:

- Five separate first-attempt runs of the entire mux family: **204/204 passed in every run**, zero failures/errors/skips, with a 128 MiB test JVM. Gradle has no retry layer here. No timeout or iteration count was relaxed.
- Kotlin formatting completed successfully; separate test-coverage and code/lifecycle review passes completed.
- One full `./gradlew test --no-daemon --max-workers=2` invocation, holding `flock /tmp/claude-501/complete-suite.lock`: **1,176 tests: 1,154 passed, 20 failed, two existing skips**. All new Yamux cases passed. Gradle stopped at `:libp2p:test`, so this does not claim later subproject tests ran.
- The full-suite failures are in unchanged native TLS setup (`failed to create an SSL_CTX`), fixed-port listeners (`Address already in use`), and a local Kubo API call (`Connection refused`). A targeted control with every PR-changed file replaced by pure develop reproduced all three failure classes on its first attempt: `HostTestJava.addPingAfterHostStart`, `RpcHandlerTest.test1`, and `QuicKuboTestJava.pingKubo` all failed in the same places. The first host test exits on TLS setup before reaching cleanup, leaving its fixed port for later tests. These are separate from this queue change; the full suite is **not green**.

CI on `1b9c76e8`: `kotlin.build (remote)` and `kotlin.build (kompile-remote-build)` both passed. Their summaries say "no build.kts found in repository", so they do not run this Gradle project's tests; the local Gradle runs above are the test evidence. The GitHub Actions `build` workflow ends in `startup_failure` on this head (https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39728125457), and it fails the same way on develop `b01e08e5`. `c0cdaada` was first pushed to a WIP branch, and the CI app attached that SHA's check suite to the WIP push, so the PR received no checks for it. `1b9c76e8` re-commits the identical tree so the PR gets its own checks.

Evidence for this revision: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64b-evidence-20261022/lane-evidence/L64b

[Durable handoff and verification evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64-handoff-evidence-20261022/handoff-artifacts/l64). The central handoff upload failed during its client bytecode fetch, so the record is preserved on GitHub without a merge.

No artifact publication or production deployment is included. This PR remains open for review.

🤖 Generated with [Claude Code](https://claude.com/claude-code)



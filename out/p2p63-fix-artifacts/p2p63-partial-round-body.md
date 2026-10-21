do not merge: awaiting owner approval of the existing-test edits listed below

HandoffServiceServer, one of five repositories in the owner's handoff model-usage tracking effort, cannot move from `jvm-libp2p:1.3.0-codexcoder21-snapshot-27` to a current develop snapshot. Five large-RPC-payload tests fail on first attempt with develop snapshots 28 and 30–35 (requests ending "read 0 of 4 bytes", attachments making no progress for 10 seconds, and 30-second test limits), while snapshots 27 and 29 are clean. The load-matched comparison in the brief found 0/30 failures on 27, 0/25 on 29, and 6–13 failures out of 25–30 on 28 and 30. [The connection-wide child pause change](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/40) stopped parent reads when one child paused, including the Yamux WINDOW_UPDATE frames required to finish a large reply. UrlProtocol pauses request delivery while writing that reply.

The user's request was: "A handoff should keep track of which agent and model(s) are working on the handoff, and display the information both as a percentage chart (how many minutes and how many tokens for each model, in aggregate) and as a timeline (who was working when [...])." This PR supplies the transport fix needed by that effort in [HandoffApi](https://github.com/CodexCoder21Organization/HandoffApi), [HandoffEmbedded](https://github.com/CodexCoder21Organization/HandoffEmbedded), [HandoffServiceServer](https://github.com/CodexCoder21Organization/HandoffServiceServer), [HandoffCli](https://github.com/CodexCoder21Organization/HandoffCli), and [HandoffWui](https://github.com/CodexCoder21Organization/HandoffWui).

The earlier [per-stream pause PR](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/42), containing [the snapshot-29 fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/276ba4acd9e2bb9b8c06e4c7097078214ebf4116), is still open with no comments, reviews, or stated rejection. It is not a draft and now has merge conflicts with develop (`mergeable=false`, `mergeable_state=dirty`). This ports its behavior onto current develop, retaining the later buffered half-close changes.

## Changes

- A paused Yamux child holds only its own delivery and receive credit. Its parent keeps reading, so siblings and reverse-direction reply credit keep flowing. The receive window still bounds retained payloads.
- Mplex keeps connection-wide read pausing and its existing byte/frame bounds. An application pause directly on the parent remains effective under both muxers.
- TCP/Noise regression tests force pause-before-write ordering for sibling traffic, a 1,675,062-byte reply on the paused request stream, and a 1,675,062-byte reply on a sibling while the request stream remains paused. Additional tests cover a full retained receive window, withheld credit, ordered resume, concurrent setters, close callbacks, and unchanged Mplex behavior.
- README documents the public contract and an asynchronous reply example; FORK.md records the change. Source version is `1.3.0-codexcoder21-snapshot-37`. Nothing is published.

## Tests and first-attempt results

| Gate | Passed | Failed | Skipped | Result |
| --- | ---: | ---: | ---: | --- |
| First reverse-direction TCP/Noise baseline on unchanged production | 0 | 1 | 0 | Assertion failure: 262,084 of 1,675,062 reply bytes received. |
| First baseline mux/pause gate | 170 | 12 | 0 | Expected Yamux assertions fail; Mplex controls pass. |
| First fixed mux/pause gate | 182 | 0 | 0 | All pass, including buffered half-close tests. |
| First mutation control, port removed again | 1 | 4 | 0 | All four Yamux TCP regressions fail; Mplex passes. |
| Restored mux/pause gate after helper cleanup | 182 | 0 | 0 | All pass. |
| First explicit sibling-reply baseline with old pause code | 0 | 1 | 0 | Assertion failure: 262,082 of 1,675,062 bytes received. |
| First full `:libp2p:test` run before adding the explicit sibling-reply case | 1,150 | 1 | 2 | Only the unchanged Kubo test fails. |
| Complete mux gate including explicit sibling reply | 183 | 0 | 0 | All pass. |
| Post-pass mutation control for explicit sibling reply | 0 | 1 | 0 | Again stops at 262,082 bytes when the port is removed. |
| Full suite with every added test | 1,150 | 2 | 2 | Kubo prerequisite failure plus the Gossip large-Mplex-message test timing out; investigation below. |
| Unchanged Gossip baseline, PARANOID tracking | 0 | 1 | 0 | Same receive-wait failure as the full suite. |
| Diagnostic unchanged Gossip baseline, SIMPLE tracking | 1 | 0 | 0 | Mode comparison only; no source/default changes. |
| Final rebase/targeted gate | 183 | 0 | 0 | Includes restored exact fragmented-read delivery assertion for both muxers. |

The first full run failed only on `QuicKuboTestJava.pingKubo`: `java.net.ConnectException: Connection refused` from `http://localhost:5001/api/v0/id`, before it constructs a libp2p host. A targeted baseline run with the old develop pause code reproduces the same failure (0 passed / 1 failed). No IPFS daemon is installed on this box. The second full run also timed out in `GossipTwoHostTest.test message larger than mplex frame`; the unchanged develop pause code reproduces the same wait failure on first attempt (0 passed / 1 failed) with PARANOID leak tracking, the process-global mode set by existing suite test bases. Both hosts use Mplex. LoggingHandlerShort formats the entire 1 MiB frame before shortening it; the baseline logs show the message arriving after the unchanged wait limit. The first full run passed this test. A diagnostic baseline comparison with SIMPLE tracking passed on its first attempt; neither full-suite result is replaced by that diagnostic control. No test source or leak-tracking default is changed. Existing skipped tests are `QuicServerTestJava.checkConnectionIsNotClosedByTimeout` and `EchoSampleTest.connect1`; neither is edited.

`spotlessCheck` and `detekt` across all projects passed on their first attempt. Final static gate: passed across all projects. The final targeted gate also retains and checks the original exact first-payload assertion for both fragmented-read muxer cases; the full run above compiled before that test-only strengthening. The full Gradle commands, invariant table, and full failure traces are recorded in the [findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/fix/yamux-per-stream-pause/out/p2p-pause-findings.md). No CI monitoring, publication, merge, or deployment is performed.

## Every existing-test edit

| Test | Before | After and reason |
| --- | --- | --- |
| `MuxHandlerAbstractTest.pausedChildStopsParentReadsUntilEveryPausedChildResumes` | Both muxers expected the parent to pause until both children resumed. | Moved unchanged to `MplexHandlerTest`. Yamux's `pausedChildrenNeverStopParentReads` asserts continuing parent reads with one/two paused children and closing one. |
| `MuxHandlerAbstractTest.closingOneOfTwoPausedChildrenKeepsParentPaused` | Both muxers expected the parent to remain paused after one child closed. | Moved unchanged to `MplexHandlerTest`; the new Yamux test covers closing one while the second remains paused, expecting parent reads to continue. |
| `MuxHandlerAbstractTest.concurrentConfigSettersLeaveParentAndChildInAgreement` | Parent auto-read expected false after a child pause. | Uses `!childPauseStopsParentReads`: false for Mplex, true for Yamux. All delivery/resume assertions and iteration counts remain. |
| `MuxHandlerAbstractTest.pausingAgainDuringQueuedDeliveryKeepsRemainingPayloadInOrder` | Parent auto-read expected false after re-entrant pause. | Same mux-specific parent-read expectation; all payload-order assertions remain. |
| `MplexHandlerTest`, `YamuxHandlerTest` | No fixture property describing whether a child pause stops parent reads. | Implement `childPauseStopsParentReads` as true/false respectively so shared assertions express each contract. |
| `ChildReadPauseTcpTest.offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume` | Both muxers expected parent reads to stop. Fragmented reads expected later payload/end frames not to reach the muxer until resume. | The exact first-payload assertion remains for both muxers. Mplex keeps the unread-frame assertions. Yamux expects continuing parent reads and explicitly awaits those payload/end frames at the muxer while child delivery stays paused. All delivery limits, order, resume, and end-event assertions remain. |

No existing time limit was raised, iteration count reduced, or test disabled. Test-comprehensiveness and code review were separate passes; details and the invariant table are in `out/p2p-pause-findings.md`.

## Review round 1 — checkpoint only; required checks unfinished

The review fixes are prepared on [the non-PR checkpoint branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/usage-p2p63-fix-ckpt), with candidate source [d933ef4c](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d933ef4c2a4edc43e5d47b0345014c7dbba9994a). **This PR's source is still 986632ab; no PR-branch push occurred because the required checks did not finish before the lane's time gate. The blocking retained-storage issue is therefore not yet fixed in this PR.**

In the candidate, [AbstractMuxHandler.kt:190](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/d933ef4c2a4edc43e5d47b0345014c7dbba9994a/libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/AbstractMuxHandler.kt#L190) prepares storage only for queued payloads, with an identity default. [YamuxHandler.kt:491](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/d933ef4c2a4edc43e5d47b0345014c7dbba9994a/libp2p/src/main/kotlin/io/libp2p/mux/yamux/YamuxHandler.kt#L491) makes an exact-sized unpooled copy and releases the input slice, so the queue does not keep already-delivered sibling backing bytes alive.

| This round's first-attempt evidence | Passed | Failed | Skipped | Outcome |
| --- | ---: | ---: | ---: | --- |
| First actual unchanged-production run of the two focused classes | 1 | 1 | 0 | TCP passed; storage assertion measured 28,100 retained input bytes for 100 queued payload bytes against a 300-byte window. |
| Earlier build attempt | 0 | 0 | 0 | Compile error in the new observer's nullable-property access; corrected with a local value. No tests executed. |
| Second baseline build attempt | 0 | 0 | 0 | 900-second command deadline after test classes compiled; no tests executed. |
| First fixed mux command | 0 | 0 | 0 | Main/fixture compilation and spotlessApply completed; 900-second deadline during test compilation. No fixed runtime result. |
| Scoped mux compilation attempt | 0 | 0 | 0 | 600-second deadline during test compilation; no fixed runtime result. |

`spotlessApply` passed. `spotlessCheck`, `detekt`, the full `:libp2p:test`, post-pass storage revert, resume-on-write mutation, final targeted gate and gated PR push remain **unfinished**. Earlier Kubo/Gossip results above are prior-round evidence, not new runs in this round. No temporary production mutation was applied, test assertion weakened, test timeout raised, iteration reduced, artifact published, merge/deploy performed or CI watched.

Candidate-only existing-test edit (not yet in the PR source):

| Test | Before | After and reason |
| --- | --- | --- |
| `YamuxPausedRequestLargeReplyTcpTest.pausedRequestReadsMustNotStopLargeReplyWindowUpdates` | One request; later callbacks ignored; write completion automatically resumed reads. | Pauses the reply reader after its first chunk to keep the large reply pending, sends byte 2, observes it reaching the real muxer and asserts no application delivery or read re-enable. All original reply/write assertions remain; explicit resume then checks exactly-once byte 2 delivery. Mutation verification remains outstanding. |

The candidate also removes `out/p2p-pause-findings.md` from its source diff; the ledger and resumption evidence are retained only on the non-PR checkpoint branch. This removal is not yet delivered to the PR. [Current findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/usage-p2p63-fix-ckpt/out/p2p63-fix-findings.md) and [resumption instructions](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/usage-p2p63-fix-ckpt/out/p2p63-fix-resume.md) name the exact remaining commands and evidence. No Maven coordinates were published.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

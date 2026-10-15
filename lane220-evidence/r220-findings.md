# Lane 220 findings
Started 2026-10-15 11:43 UTC. Stop new work 12:48 UTC; hard wall 12:58 UTC.

## Plan
- IN PROGRESS: Read instructions/docs, clone branch, record/rebase head and inspect full diff.
- TODO: Classify two tests (5 branch and 5 unchanged develop each), inspect framing evidence.
- TODO: Independent test review with invariant-to-test fail-first table; enumerate pre-existing test changes.
- TODO: Independent code review; reproduce/fix any blocking findings.
- TODO: Final package gate, update PR review paragraph, push with explicit lease, preserve evidence and clean processes/checkout/cache.

## 11:44 UTC — context
OBSERVED: Read task brief, common.md and handoff snapshot, and lane208 findings. Prior lane reports head 1b4d3a10782f448cec5de1e7af35cc859bd7f518, targeted 200/200 pass and broader 482/485. Echo plus malformed framing reproduced on base; multiple ping and child pause remain unclassified. These are prior observations to verify, not current conclusions. No nested delegation, merge, publish, deployment or Handoff service actions authorized. GitHub repository and status-report skills applied; brief-specific progress reporting and no-handoff rules take precedence over generic skill workflow.

## 11:46 UTC — verified branch and review scope
OBSERVED: Fresh clone, no AGENTS.md in repository. README.md and FORK.md read. Repository search confirms fork default develop. PR is OPEN at 1b4d3a10782f448cec5de1e7af35cc859bd7f518; base origin/develop is 3e88aeef2e016a46565be871b367646c10ef6f65. Fresh fetch/rebase was a no-op. Full six-file diff read: README/version, AbstractMuxHandler/MuxHandler, and two new test files. There are no modifications to tests that existed on develop. The inherited lane165 reproducer is new relative to develop; lane208 changed formatting and cleanup there but retained its four cases, bounds and assertions. Detailed before/after audit will compare that predecessor separately.

| Resource | Creator / owner / closer | Terminal state and caller surface | Reentry / close mid-operation / restart | Existing public test |
|---|---|---|---|---|
| Physical TCP parent | Transport creates, Host/Connection owns; Connection.close or transport closes | Closed parent cannot accept new streams | Callbacks can close and reopen; no persisted state | closeAfterSuccessfulPingRejectsNewStreams; original four callback-close scenarios |
| Mux session readiness | StreamMuxer.initChannel creates future; MuxHandler settles | Activate then publish, or fail readiness on close | Close during install and before activation; no persisted state | sessionIsPublishedAfterActivation; closeDuringHandlerInstallationFailsSessionReadiness; closeBeforeActivationFailsConnectionWithoutPublishingIt |
| Activation and queued creation | AbstractMuxHandler privately owns gate and pending set | Every queued result settles on physical close, successful/failed results removed | Concurrent register requires second closed-state check, callbacks outside locks | queuedOpensAndCompletionReentrySettleWhenPublicationClosesParent |
| Stream/controller views | Session creates; caller owns returned mutable observations | Caller mutation cannot hide underlying controller closure | cancel/fail/complete stream future then close; no persisted state | callerCompletedStreamFutureDoesNotHideParentCloseFromController |

OBSERVED: Scratch worktree review-baseline is at the same head with exactly the two production Kotlin files restored from unchanged develop, for the required fail-first check. No PR source changes yet. Identical five-task branch classification is running; each task includes multiplePingChannelsOnTheSameConnection, echoOverSecureConnection and offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume (both parameters).
INFER (code review, not test verdict): PR 39 touches physical close ownership in transport files, none of the two modified mux implementation files. This patch observes the underlying channel closeFuture, which is retained by PR 39's one-owner helper. Source-level composition appears compatible; it does not mean PR 39 has been merged or included in this branch.

## 11:50 UTC — test review gap identified before implementation
OBSERVED: All new pending-close cases issue new streams from the event-loop callback; none exercises the concurrent registry's off-loop entry path. Added a test ONLY in the source-reverted scratch worktree, with 64 real public Session.createStream callers on an eight-thread executor while the publication callback is held by a latch and parent close is already queued. It snapshots all 128 stream/controller futures at close completion and counts each completion; both muxers are parameters. Existing bounds/counts/assertions are unchanged. No implementation change is proposed; this strengthens regression coverage of the existing pending registry fix. Fail-first will run together with every original lifecycle case against the restored develop implementation, then the same test will be transferred to the branch for verification.

## 11:53 UTC — branch classification completed
OBSERVED: Five identical branch Gradle tasks at unchanged 1b4d3a10 ran 20 cases total: 19 pass, 1 failure. Plaintext multiple-ping 5/5 pass; plaintext echo 5/5 pass; child-read-pause Mplex 5/5 pass; child-read-pause Yamux 4/5 pass. The Yamux failure is at controller creation (ChildReadPauseTcpTest.kt:146), ExecutionException caused by ConnectionClosedException from ProtocolSelect.channelUnregistered (:89) following physical parent close. Full stack is r220-branch-five.log; raw XML and output are r220-branch-lane220Repeat1-xml/ and r220-branch-child-output.log. Base comparison is now running on exact unchanged origin/develop. The requested original Mplex failure has not reproduced in the branch's five trials; no conclusion about absence or frequency is drawn from that.
OBSERVED: Snapshot-31 POM probe returns HTTP 404 at 11:51 UTC. No artifact published. Drafted a separate evidence-only framing experiment that batches the real server's plaintext identity and initial mux negotiation header into one network write, via public Host/Channel handlers. It will be used only after the prescribed base comparison, and will not be silently added as a failing source test.

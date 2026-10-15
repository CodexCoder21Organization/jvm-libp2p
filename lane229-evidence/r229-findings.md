# Lane 229 findings
Started 2026-10-15 13:00 UTC; stop new work 14:15 UTC; hard wall 14:30 UTC.

## Plan
- IN PROGRESS: Read instructions/docs, verify source head and no-op rebase, reconcile full diffs of both PRs.
- TODO: Five identical branch and unchanged-develop runs of the four plaintext failures; classify each from XML and full logs.
- TODO: Complete full module in bounded package halves, preserve every result and classify additional failures where possible.
- TODO: Separate test and code reviews, update PR via REST with reconciliation/classifications/counts/follow-up, explicit-lease push and evidence checkpoint.
- TODO: Terminate owned processes, verify ps, delete own checkout/cache, final brief audit.

## 13:02 UTC — instructions and prior evidence
OBSERVED: Read brief, common.md, complete handoff snapshot, lane208 and lane220 findings. No nested agents, Handoff service, merge, publication or deployment authorized. Brief overrides generic status skill's scheduling/handoff/delegation instructions; written lane updates are the reporting mechanism. Repository skill lookup located CodexCoder21Organization/jvm-libp2p, default develop. Prior lane's four failures are PlaintextTcpTest.twoEchosOverSecureConnection, unsupportedServerProtocol, echoOverSecureConnection and largeEchoOverSecureConnection. They remain individually unclassified until this lane's branch/base experiment. Prior full module run was incomplete, not a passing gate.

## 13:04 UTC — source reconciliation complete
OBSERVED: PR is OPEN at required 7a27e4890885592f60ad402af6af30b5578da364; develop is 3e88aeef2e016a46565be871b367646c10ef6f65. Fetch/rebase is a no-op. Read README.md and FORK.md; no repository AGENTS.md exists. Read full PR diffs, compared the parent-close files directly against current PR39 head 8372d92a15a875855342d5955927f767ee75c80c. ConnectionOverNetty, PlainNettyTransport and QuicTransport implementations are byte-identical to PR39. NettyChannelClose differs only in the already-completed close branch: PR48 registers a close listener rather than completing inline, preserving lane220's proven correction. ParentCloseOwnershipTest differs only in import ordering, line wrapping, and one blank line; both original scenarios and assertions are retained. README's parent-close paragraph is identical. PR39's stale snapshot-27 version/FORK example and investigation logs are not changes to import into the current snapshot-31 PR.
DECISION: PR39 should be closed by the supervisor as superseded after PR48 lands. Do not rebase PR48 onto PR39: all its functional parent-close changes are already present, while its old version and investigation artifacts would be reintroduced. Keep PR48's additional already-closed listener correction and its four fail-first reentry cases. ParentCloseOwnershipTest still needs a current run before declaring this reconciliation verified. No source edit is required for composition.
OBSERVED: First native five-task classification is compiling. Both sides use identical filters/settings and independent Test tasks, not retries to green. A bounded process-group wrapper saves command/exit/duration. Native module gate's normal configuration excludes interop; the halves will preserve that same default. The untagged QuicKuboTestJava requires Kubo 0.34.1 per the workflow. Preparing an isolated local Kubo instance, without external bootstrap peers, for that test rather than skipping it.

## 13:07 UTC — isolated Kubo setup and bounded runner
OBSERVED: Workflow build.yml runs install-run-ipfs.sh, which requests Kubo 0.34.1. The configured dist.ipfs.io download timed out after 120 seconds with curl (28), without receiving the archive. The same official GitHub release asset downloaded successfully and matches the repository's declared SHA-256 42045802fe60c64fb01350bc071190c534d600fe269759c06e27e22b2012fd3e. Started the real binary with a fresh lane-owned IPFS_PATH, no bootstrap peers, mDNS disabled, routing=none, TCP/QUIC only on 127.0.0.1:4001 and HTTP API on 127.0.0.1:5001. API id returns the isolated local peer and both loopback addresses; daemon PID 272690. No test was skipped.
OBSERVED: Gradle forks its single-use daemon into a distinct process group. Updated the bounded wrapper to track descendant groups and terminate all of them on exit/deadline; the first already-running command's daemon group 269679 is separately tracked for cleanup. Wrapper changes affect only outer command bounds, not any test bound.
CHALLENGE RECORD: Read report-challenge skill, but did not invoke its CLI because it automatically opens/enqueues/merges a PR and this lane explicitly forbids merge/queue actions. Dist download timeout plus the official mirror workaround are recorded here for the supervisor. No repository installer source was changed.
DOCUMENTS READ: Brief, common.md, handoff snapshot, r208/r220 findings; README.md, FORK.md, both full PR diffs; Testing architecture TDD/public API/isolation/concurrency/fail-first/hardware-independent trigger sections; Engineering Philosophy required root-cause/upstream/reproduction/refuted-premise sections; Code Review Red Flags; CI_SYSTEMIC_FAILURES; fix-flakey-test, github-repos, status-report, report-challenge skills. These came from current GitHub contents or named local paths; earlier lanes' claims are verified where used.

## 13:08 UTC — independent review pass
TEST REVIEW: Read all three lifecycle/parent-ownership test files. They use real loopback TCP/Noise hosts with ephemeral ports, public Host/Connection/Session and debug/pipeline APIs, no new reflection or mocks. The original two parent-owner scenarios retain exact physical-close counts and full injected-error text. New scenarios cover both muxers, preactivation/handler-install close, event-loop and concurrent off-loop opens, completion reentry, caller cancellation/failure/completion, early physical-close observers via both explicit and pipeline close, and a successful ping control. Lane220's fail-first records align with the source and test assertions; this lane will verify the exact final head with its own 28-case focused result as well as the entire module. No test source is changed in this lane; no existing assertion/count/bound is loosened.
CODE REVIEW: Compared every parent-close implementation hunk to PR39, enumerated all nine helper calls, and checked Yamux/Mplex as the only AbstractMuxHandler production subclasses; native QUIC session creation remains separate. The channel attribute claims ownership before callbacks, callers receive independent derived futures, original failure remains terminal, and already-closed completion registers behind pending close notifications. The pending stream registry registers before scheduling, observes terminal completion for removal, rechecks parent state, and completes outside a callback lock. Mux readiness is published after activation; the private creation result independently reports close to the controller. No new public signature or interface call is introduced. No additional code defect established by this pass. Module evidence is still required.
FOLLOW-UP (source-confirmed): Negotiator.GenericHandler.channelRead0 removes framing handlers, which can deliver buffered coalesced identity bytes and activate the next stage; completion removes ProtocolSelect's activeFired guard. Negotiator then calls ctx.fireChannelActive again at line 121, reaching the newly installed mux negotiator. Lane220's saved valid-coalescing public-host probe records duplicate root headers and fails on both base and branch. Preserve that probe as the explicit follow-up required by this brief; do not call these protocol-transition failures fixed.

## 13:10 UTC — branch five-run milestone
OBSERVED: Exact required branch head completes all five native Test tasks in 367.88 seconds, exit 1 due to a single failed test: **19/20 passing, 1 failure, no errors/skips**. twoEchosOverSecureConnection, unsupportedServerProtocol and largeEchoOverSecureConnection each pass 5/5; echoOverSecureConnection passes 4/5 and fails once. Full failure:
```
java.util.concurrent.TimeoutException
    at java.base/java.util.concurrent.CompletableFuture.timedGet(CompletableFuture.java:1960)
    at java.base/java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2095)
    at io.libp2p.core.HostTransportsTest.echoOverSecureConnection(HostTranportsTest.kt:285)
    at java.base/java.lang.reflect.Method.invoke(Method.java:580)
    at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
    at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
```
Saved r229-branch-five.log, command/exit JSON, summary with all cases/full stack and five raw XML directories. Switched this same compiled workspace to exact unchanged origin/develop 3e88aeef; fetch/rebase is a no-op and identical five-task command is running. Do not classify the one failure as introduced or pre-existing until the evidence comparison.

## 13:11 UTC — evidence comparison, before fresh base verdict
OBSERVED: Branch repeat5's failed echo trial receives Mplex CLOSE(resp), id=2, payload length 47 at 13:09:14.130. Payload bytes are `multistream/1.0.0\n/mplex/6.7.0\n` followed by the child OPEN and multistream frame prefix. The client had sent stream 16 OPEN and DATA; it receives no controller before the unchanged 5-second bound. Extracted full class stdout/stderr as r229-branch-failure-output.log.
OBSERVED: Independently read lane208's saved base-repeat9 XML (unchanged develop 3e88aeef, prior lane's recorded command/head), rather than relying only on its narrative: echoOverSecureConnection fails at the identical HostTranportsTest.kt:285 with the same full stack and exactly the same 47 payload bytes, server timestamp 11:28:33.106. Copied that existing record to r229-prior-unchanged-base-echo.xml and extracted output, clearly marked PRIOR evidence. It proves this echo failure shape predates PR48 even if a five-trial sample does not fire; a small sample's absence cannot establish introduced causality. The newly prescribed base five-task comparison is still running and will be reported independently.

## 13:13 UTC — verification contract and reconciliation table
The module verification keeps the normal Gradle unit-task exclusion of the interop tag. Half A includes core/etc/mux/host/network/transport; Half B excludes exactly those packages and therefore includes every remaining class. They are complementary filters, with no test removed from the normal module task. Each command gets its own 720-second outer bound, one worker, unchanged internal test bounds, same shared compiled workspace. A separate lifecycle/ownership task will give an explicit 28-case result.

| PR39 hunk | PR48 disposition | Why |
|---|---|---|
| ConnectionOverNetty.close | Keep current; identical | Same physical-parent owner |
| PlainNettyTransport close/unlisten/late register | Keep current; identical | All four close routes join owner |
| QuicTransport close/unlisten/late register | Keep current; identical | All four close routes join owner |
| NettyChannelClose | Keep current listener on already-closed path | Original inline completion fails two deterministic pipeline-close cases (lane220); current source retains correction |
| ParentCloseOwnershipTest | Keep current; format-only difference | Both original public-API scenarios/assertions remain |
| README close ownership paragraph | Keep current; identical | Same public contract |
| snapshot-27 version and FORK example | Keep current snapshot-31 declaration | Old release metadata is stale relative to develop snapshot-28/current PR31 |
| investigations/*.md and captured logs | Keep off source PR | Evidence remains linked from PR39 and evidence branches; these are not product source |

No overlap requires a source edit or importing PR39 history. Current focused/module test outcomes, still pending, are the runtime verification for this decision.

## 13:17 UTC — requested classification complete
OBSERVED: Unchanged develop 3e88aeef completes the identical five-task command in 401.05 seconds, exit 0: **20/20 pass, 0 failures/errors/skips**. Fresh comparison totals are branch 19/20, base 20/20.

| PlaintextTcpTest scenario | Branch pass/fail | Fresh unchanged-base pass/fail | Disposition |
|---|---:|---:|---|
| twoEchosOverSecureConnection | 5 / 0 | 5 / 0 | Unreproduced in both requested samples; earlier suite failure remains recorded |
| unsupportedServerProtocol | 5 / 0 | 5 / 0 | Unreproduced in both requested samples; earlier wrong-exception trial remains recorded |
| echoOverSecureConnection | 4 / 1 | 5 / 0 | Existing failure, established by prior identical-base native failure with identical full stack and exact malformed 47-byte wire payload; fresh five base trials did not fire |
| largeEchoOverSecureConnection | 5 / 0 | 5 / 0 | Unreproduced in both requested samples; earlier suite failure remains recorded |

REFUTED INFERENCE: The brief's suggested rule that a failure occurring only in a five-trial branch sample must be introduced is not supported for echo: the identical source base already emitted the identical test failure and byte-for-byte malformed frame. Five base passes do not invalidate that observed base failure. This is not a green retry verdict or a claim of absence/unchanged frequency; all 40 fresh results and the prior failing base XML are preserved. No speculative source fix is made. The known repeated-activation protocol defect is explicitly left as the follow-up the brief requested.
PLAN: source/reconciliation DONE; requested four-test comparison DONE with the evidence-backed qualification above; full module verification IN PROGRESS; final PR update/push/cleanup TODO. Restored exact source head 7a27e489, fresh fetch/rebase remains no-op, and began focused 28-case gate before the complementary module halves.

## 13:20 UTC — 20-minute checkpoint
OBSERVED: Classification XML and all forty fresh trials are pushed through evidence commit cb1c2a4b. Focused exact-head verification is currently compiling. Source remains untouched at 7a27e489; separate module halves are the next dependent steps. No other JVM build runs in this lane; Kubo is idle until its QUIC test. Source reconciliation and the four classifications are complete; runtime gate, PR paragraph, explicit-lease push and cleanup remain. Stop-new-work/hard-wall remain 14:15/14:30 UTC.

## Independent invariant coverage inventory (no new implementation)
| Resource | Creation / owner / closer | Terminal state, caller surface and reentry | Public test coverage |
|---|---|---|---|
| TCP/QUIC parent close | Transport creates; channel attribute owns first close; Connection and transport join | One success/failure retained; later callers borrow independent futures; reentry cannot issue a second physical close; no persisted restart state | ParentCloseOwnershipTest's pending and failed-close scenarios |
| Already-closed physical parent | Native channel owns close future; first library close observer joins it | Completion must not run ahead of pending physical-close listeners when an early observer reenters; no restart record | earlyCloseObserverReentrySettlesQueuedOpens, explicit/pipeline × both muxers |
| Mux readiness/activation | StreamMuxer creates readiness; MuxHandler owns publication | Publish activated open session, or fail on close during installation/preactivation; callbacks may close immediately | sessionIsPublishedAfterActivation; closeDuringHandlerInstallationFailsSessionReadiness; closeBeforeActivationFailsConnectionWithoutPublishingIt; original four publication tests |
| Queued stream creation | AbstractMuxHandler privately owns result registry and event-loop work | Every result settles/removes on success/failure; closure rejects registered and racing callers; callbacks can reenter without a held callback lock | queuedOpensAndCompletionReentrySettleWhenPublicationClosesParent; offLoopQueuedOpensSettleBeforeParentCloseCompletes (64 callers / exact 128 completions) |
| Caller stream/controller views | MuxHandler owns creation source; caller owns mutable stream view | Caller cancel/fail/complete cannot suppress controller close; closed parent rejects later streams | callerCompletedStreamFutureDoesNotHideParentCloseFromController, 3 mutations × both muxers; closeAfterSuccessfulPingRejectsNewStreams control |

No state in this change survives a process restart; no persisted record shape changes. Each defect row has lane220's fail-first evidence; normal ping controls intentionally also pass on old code. Current final-head native verification is pending.

## 13:24 UTC — focused gate progress
OBSERVED: Restored PR production and fixture compilation completed; compileTestKotlin is still running. No focused runtime verdict exists yet. Command remains bounded at 720 seconds; no internal timeout/count has changed. Evidence branch contains classification milestone; no source branch edit exists. Both module halves remain pending on the compiled final head.

## 13:27 UTC — focused gate complete
OBSERVED: Exact unchanged PR head 7a27e489 completes the focused lifecycle/ownership command successfully in 512.51 seconds including recompilation. Raw XML confirms **28/28 pass, zero failures/errors/skips**: original publication 4/4, expanded session activation/reentry 22/22, and PR39's two original parent-ownership cases 2/2. This verifies the source reconciliation's required native tests and retains the already-closed listener correction. Full XML, command, output and summary saved with r229-lifecycle prefix. Source remains clean; fresh fetch/rebase remains a no-op before Half A.
PLAN: reconciliation and requested comparison DONE; focused lifecycle/ownership DONE; full module halves IN PROGRESS; PR update/lease push/cleanup TODO.

## 13:30 UTC — broader failures surfaced, not yet final
OBSERVED: Half A has reported PlaintextTcpTest.multiplePingChannelsOnTheSameConnection timeout (HostTranportsTest.kt:240 via :212) and unsupportedServerProtocol receiving TotalTimeoutException instead of NoSuchProtocolException (HostTranportsTest.kt:183, caused by TotalTimeoutHandler.onTimeout:66). The complete full stacks are in the still-running r229-half-a.log; no aggregate half verdict yet. These are retained failures, not dismissed because the five-trial selector comparison passed. After both final-head module halves complete, compare the additional broader failures on unchanged develop under the same package invocation if time allows. This is additional discriminating classification, not rerunning the PR to green. No source edit.

## 13:32 UTC — complete module Half A
OBSERVED: Half A finishes natively in 237.35 seconds, exit 1: **632 discovered, 623 passed, 8 failures, 0 errors, 1 skipped**. Skipped checkConnectionIsNotClosedByTimeout in QuicServerTestJava has an unchanged pre-existing @Disabled("Runs too long") at line 161, also present on develop; no skip was added by this lane. Real isolated Kubo pingKubo passes in 1.958 seconds and keyPairGeneration passes. All 28 focused cases also pass within this half.
Failures and full stacks are r229-half-a.log, summary JSON, and complete raw XML. Failed scenarios: PlaintextTcpTest.twoEchosOverSecureConnection, identifyOverSecureConnection, multiplePingChannelsOnTheSameConnection, unsupportedServerProtocol, largeEchoOverSecureConnection; ChildReadPauseTcpTest.offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume — Yamux initialization; NettyOutboundBufferBackpressureTest.normallyDrainingYamuxPeerReceivesBulkPayloadIntact and peerThatStallsBelowBudgetThenResumesReceivesEveryAcceptedByte — stream creation fails on physical close before bulk traffic. These last two also use plaintext handshakes, but that is not sufficient by itself to claim a shared mechanism.
NEXT: Half B runs from the same compiled source, after another no-op fetch/rebase. Then run the identical Half A package gate on unchanged develop to classify the newly surfaced broader failures; do not rerun this failed PR half as a green replacement. Remaining time permits that additional discriminating comparison.

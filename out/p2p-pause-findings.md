# p2p-pause findings

Started 2026-10-21 20:40 UTC; deadline 22:00 UTC, checkpoint exit gate 21:50 UTC.

## Plan
- [done] Read contract/guidance and prior PR history; review existing pause invariants.
- [done] Add deterministic real TCP/Noise regression tests; record baseline assertion failures.
- [done] Port per-stream Yamux pausing, retain Mplex behavior, update docs and snapshot-37.
- [in progress] Run mutation control, mux/pause tests, full libp2p suite, formatting/static checks; independent test/code review passes.
- [pending] Rebase develop, targeted verification, checkpoint and single PR push; open PR against develop and stop processes.

OBSERVED: Read CONTRACT.md, CLAUDE.md, FORK.md, README.md and the Testing Architecture sections Test-Driven Development and Tests Must Be Hermetically Sealed. Gradle/JUnit conventions apply in this repository.
OBSERVED: Prior change https://github.com/CodexCoder21Organization/jvm-libp2p/pull/42 is OPEN, has no comments or reviews, and contains commit 276ba4acd9e2bb9b8c06e4c7097078214ebf4116. No stated rejection or reason for not landing exists in the PR record.
INFER: The prior implementation can be evaluated and ported without a recorded design objection. This lane must add proof of the reverse-direction RPC shape as well as sibling progress.
Current commit: 9d718392 (launch develop).

## Invariants before implementation
| Resource | Creator/owner/close | Required observations and re-entry behavior | Public-path proof |
| --- | --- | --- | --- |
| Child config | Child channel; retained through close | Stable config; pause holds only its own ordered delivery; close releases queues, including pause from close callback | Existing config/re-entry/close tests plus ported close-listener tests |
| Yamux receive queue and credit | Stream handler; releases on delivery/reset/parent close | Paused child retains at most receive window, grants no credit until delivery; siblings and reverse-direction credit keep flowing | TCP sibling/full-window tests, reverse-direction reply test, embedded window/close tests |
| Parent read state | Parent channel/application | Yamux child never changes it; Mplex pauses until all paused children resume/close, preserving prior root state | Both mux handler suites and real TCP tests |
| Pending writes | Stream/mux handler; completed or failed on close/reset | Reply larger than window completes while request reading paused; reset/parent close fail pending writes | New reverse-direction TCP test and existing write/half-close suites |

## 20:43 UTC — test setup
OBSERVED: The earlier draft pauses the responder child synchronously in its request callback before sending 1,675,062 reply bytes. This is the reverse-direction RPC shape: response window updates must arrive at the same parent whose reads the old pause disables.
OBSERVED: Ported the earlier sibling tests, using TCP + Noise rather than plaintext. They open both streams before pausing, reconcile the pause on the server event loop, then transfer four windows plus one byte on the sibling. The full-window case also checks withheld paused-stream credit and ordered resume.
INFER: These handshakes force the relevant ordering independent of host load; no scheduler-delay amplifier is needed.
OBSERVED: Existing develop has a newer fragmented-read TCP scenario than the prior fix. Its Mplex parent-pause assertions remain intact; Yamux now explicitly waits for all payload and end frames to reach the muxer while its child delivery stays paused.
Current commit: 9d718392; test-first baseline build running; production unchanged.

## Existing test edits (complete list)
- `MuxHandlerAbstractTest.pausedChildStopsParentReadsUntilEveryPausedChildResumes`: moved unchanged into `MplexHandlerTest`; Yamux's old parent-stopped expectation is replaced by `YamuxHandlerTest.pausedChildrenNeverStopParentReads` with parent-reading assertions for one/two paused children and closing one.
- `MuxHandlerAbstractTest.closingOneOfTwoPausedChildrenKeepsParentPaused`: moved unchanged into `MplexHandlerTest`; Yamux close with another paused child is covered by the new per-stream parent-reading test.
- `MuxHandlerAbstractTest.concurrentConfigSettersLeaveParentAndChildInAgreement`: before `parent.autoRead == false` after pause; after `parent.autoRead == !childPauseStopsParentReads` (Mplex false, Yamux true). Delivery, resume, iteration counts and time limits unchanged.
- `MuxHandlerAbstractTest.pausingAgainDuringQueuedDeliveryKeepsRemainingPayloadInOrder`: same parent-read expectation change; all ordered-delivery assertions retained.
- `MplexHandlerTest` and `YamuxHandlerTest`: implement the new test-fixture expectation property as true and false respectively. No production interface gains a member requiring other adapters.
- `ChildReadPauseTcpTest.offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume`: before both muxers expected parent stopped; after only Mplex expects stopped. Fragmented-read assertions requiring later frames and remote end to remain unread apply only to Mplex. Yamux instead asserts those exact frames arrive at the muxer while child delivery stays paused. Existing delivery limits, order, resume and end-event assertions all retained, as are all time limits.

Mechanism to fix: `AbstractMuxHandler.pauseChild` unconditionally clears parent autoRead, preventing Yamux WINDOW_UPDATE processing for a large reverse-direction reply, and all sibling traffic. Reproducer: TCP/Noise request callback pauses its child before a reply exceeding the initial window; TCP/Noise sibling test pauses A and reconciles its config before sending four windows plus one byte on B.

OBSERVED: First-attempt TCP/Noise reverse-direction baseline: 1 executed, 0 passed, 1 assertion failure, 0 skipped. org.opentest4j.AssertionFailedError: A paused request reader must still receive Yamux window updates so its 1675062-byte reply completes; received 262084 bytes, write completed=false, failure=null ==> expected: <true> but was: <false> Full stack trace: out/baseline-reverse-stacktrace.txt; Gradle log out/baseline-reverse.log. Production remains unchanged.

OBSERVED: Baseline mux gate first attempt: 182 executed, 170 passed, 12 failed, 0 skipped. Production unchanged at 9d718392.
- io.libp2p.mux.YamuxPausedRequestLargeReplyTcpTest.pausedRequestReadsMustNotStopLargeReplyWindowUpdates(): org.opentest4j.AssertionFailedError: A paused request reader must still receive Yamux window updates so its 1675062-byte reply completes; received 262084 bytes, write completed=false, failure=null ==> expected: <true> but was: <false>
- io.libp2p.mux.yamux.YamuxHandlerTest.pausingAChildFromItsOwnCloseListenerLeavesParentReadingAndReleasesPayload(): org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
- io.libp2p.mux.yamux.YamuxHandlerTest.concurrentConfigSettersLeaveParentAndChildInAgreement(): org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
- io.libp2p.mux.yamux.YamuxHandlerTest.pausingAgainDuringQueuedDeliveryKeepsRemainingPayloadInOrder(): org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
- io.libp2p.mux.yamux.YamuxHandlerTest.pausedChildrenNeverStopParentReads(): org.opentest4j.AssertionFailedError:
Expecting value to be true but was false
- io.libp2p.mux.yamux.YamuxHandlerTest.concurrentPauseResumeOfOneChildNeverTouchesParentOrSibling(): org.opentest4j.AssertionFailedError:
Expecting value to be false but was true
- io.libp2p.mux.yamux.YamuxHandlerTest.pausedChildAtItsFullWindowDoesNotHoldSiblingDeliveryOrCredit(): org.opentest4j.AssertionFailedError:
Expecting value to be true but was false
- io.libp2p.mux.ChildReadPauseTcpTest.yamux, fragmented transport reads=false: org.opentest4j.AssertionFailedError: only Mplex pauses parent reads; Yamux withholds only the paused child's delivery and credit ==> expected: <false> but was: <true>
- io.libp2p.mux.ChildReadPauseTcpTest.yamux, fragmented transport reads=true: org.opentest4j.AssertionFailedError: only Mplex pauses parent reads; Yamux withholds only the paused child's delivery and credit ==> expected: <false> but was: <true>
- io.libp2p.mux.ChildPauseSiblingStreamTcpTest.yamuxPausedChildDoesNotStallSiblingStreamOnTheSameConnection(): org.opentest4j.AssertionFailedError: the sibling stream stalled after 0 of 1048577 bytes while another child on the same Yamux connection was paused ==> expected: <true> but was: <false>
- io.libp2p.mux.ChildPauseSiblingStreamTcpTest.yamuxPausedChildAtItsFullWindowWithholdsOnlyItsOwnCreditAndResumesInOrder(): org.opentest4j.AssertionFailedError: the sibling stream stalled after 0 of 1048577 bytes while a child holding its full receive window was paused ==> expected: <true> but was: <false>
- io.libp2p.mux.ChildPauseSiblingStreamTcpTest.yamuxConcurrentPauseResumeOfOneChildNeverStallsSiblingAndKeepsItsOwnOrder(): org.opentest4j.AssertionFailedError: the sibling stream stalled after 0 of 2097154 bytes while the other child was paused by racing callers ==> expected: <true> but was: <false>

OBSERVED: Checkpoint 80ff063f06471adc02712527c98d7c5f8aa85aba is pushed to https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/usage-p2p-pause-ckpt. Baseline mux stack traces are retained locally in out/baseline-mux-stacktraces.txt. Port now applies the exact three-file production change from 276ba4ac to develop, retaining all newer half-close behavior.

OBSERVED: First fixed mux gate: 182 executed, 182 passed, 0 failed, 0 skipped. Per-test PASSED lines in out/fixed-mux.log. :libp2p:spotlessApply completed successfully. This includes all newer buffered-half-close tests.

## 20:48 UTC — passing port and review passes
OBSERVED: Passing port checkpoint b5ee9114 is pushed to https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/usage-p2p-pause-ckpt. The old production pause behavior is temporarily restored in exactly AbstractMuxHandler.kt and YamuxHandler.kt for mutation control; no test expectation is changed for this control.
OBSERVED: Test-comprehensiveness review (separate pass): the real TCP/Noise tests cover no-data pause, a full retained receive window, withheld credit, sibling progress beyond four windows, ordered resume, concurrent public config setters, unchanged Mplex parent pausing, and the same-stream reverse-direction reply. Existing mux tests cover child/parent close, reset, re-entrant pause, deferred end-of-stream, invalid receive credit and queue bounds. Added close-listener cases retain/release payload assertions. No time limit or iteration count was reduced or raised.
OBSERVED: Code review (separate pass): only pauseChild's parent-read policy changes. Yamux still deducts receiveWindowSize before childRead and restores it only from onBytesDelivered; zero-length data is released before queuing. The default remains true for Mplex and AsyncParentWriteMuxHandler. Resuming a Yamux child cannot restore an application-paused parent because Yamux never saves a parent-pause state. Newer half-close and write-completion code remains unchanged. No production interface method or public test-only seam is added.
INFER: Per-stream pausing preserves the existing receive-memory bound while removing the parent read dependency that blocked reverse-direction reply credit. No protocol or dependency change is required.
Current commit: b5ee9114; mutation control running.

OBSERVED: Mutation control first attempt: 5 executed, 1 Mplex pass, 4 Yamux assertion failures, 0 skipped. Same-stream reverse reply again stops at 262,084/1,675,062 bytes; sibling transfers stall when the old parent pause is restored. Full stack traces: out/mutation-stacktraces.txt.
- io.libp2p.mux.YamuxPausedRequestLargeReplyTcpTest.pausedRequestReadsMustNotStopLargeReplyWindowUpdates(): org.opentest4j.AssertionFailedError: A paused request reader must still receive Yamux window updates so its 1675062-byte reply completes; received 262084 bytes, write completed=false, failure=null ==> expected: <true> but was: <false>
- io.libp2p.mux.ChildPauseSiblingStreamTcpTest.yamuxPausedChildDoesNotStallSiblingStreamOnTheSameConnection(): org.opentest4j.AssertionFailedError: the sibling stream stalled after 0 of 1048577 bytes while another child on the same Yamux connection was paused ==> expected: <true> but was: <false>
- io.libp2p.mux.ChildPauseSiblingStreamTcpTest.yamuxPausedChildAtItsFullWindowWithholdsOnlyItsOwnCreditAndResumesInOrder(): org.opentest4j.AssertionFailedError: the sibling stream stalled after 0 of 1048577 bytes while a child holding its full receive window was paused ==> expected: <true> but was: <false>
- io.libp2p.mux.ChildPauseSiblingStreamTcpTest.yamuxConcurrentPauseResumeOfOneChildNeverStallsSiblingAndKeepsItsOwnOrder(): org.opentest4j.AssertionFailedError: the sibling stream stalled after 0 of 2097154 bytes while the other child was paused by racing callers ==> expected: <true> but was: <false>

OBSERVED: Port restored, helpers moved to package scope, pause caller pools given bounded shutdown waits: restored mux gate 182 executed, 182 passed, 0 failed, 0 skipped, first attempt. Production matches b5ee9114 exactly. Formatting application succeeds. Current commit b5ee9114; next gate is full :libp2p:test plus spotlessCheck and detekt.

## 20:53 UTC — full suite
OBSERVED: Full :libp2p:test on cabf264f, first attempt: 1153 total, 1150 passed, 1 failed, 2 pre-existing skipped. Static checks are still running.

OBSERVED: io.libp2p.transport.quic.QuicKuboTestJava.pingKubo()

```text
java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:694)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:542)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at java.base/sun.net.NetworkClient.doConnect(NetworkClient.java:178)
	at java.base/sun.net.www.http.HttpClient.openServer(HttpClient.java:539)
	at java.base/sun.net.www.http.HttpClient.openServer(HttpClient.java:644)
	at java.base/sun.net.www.http.HttpClient.<init>(HttpClient.java:290)
	at java.base/sun.net.www.http.HttpClient.New(HttpClient.java:394)
	at java.base/sun.net.www.http.HttpClient.New(HttpClient.java:416)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getNewHttpClient(HttpURLConnection.java:1327)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.plainConnect0(HttpURLConnection.java:1260)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.plainConnect(HttpURLConnection.java:1146)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.connect(HttpURLConnection.java:1075)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getOutputStream0(HttpURLConnection.java:1477)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getOutputStream(HttpURLConnection.java:1440)
	at io.libp2p.transport.quic.QuicKuboTestJava.getKuboPeerId(QuicKuboTestJava.java:65)
	at io.libp2p.transport.quic.QuicKuboTestJava.pingKubo(QuicKuboTestJava.java:21)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

```

OBSERVED: QuicKuboTestJava calls http://localhost:5001/api/v0/id before creating its Host (lines 21, 59–65). That request is refused; no ipfs executable is installed. This test file is unchanged.
OBSERVED: An additional explicit sibling-reply test was written after the first full suite compiled its test sources, so that run does not include it. It keeps server request stream A paused throughout a 1,675,062-byte server-to-client reply on sibling B, on the same TCP/Noise connection. It will be run first against the old pause behavior, then against the port.
Current commit: cabf264f680e27588839fdd01a85edffd00eb0a6.

OBSERVED: First-attempt explicit sibling-reply baseline: 1 assertion failure, 0 passes, 0 skips. With the old production pause restored, reply B stops at 262,082/1,675,062 bytes while A stays paused. The same baseline run also reproduces QuicKuboTestJava.pingKubo: Connection refused at its HTTP call, before creating a host. Thus the full-suite Kubo failure occurs with unchanged develop pause code too. Both full traces follow.

io.libp2p.mux.YamuxPausedRequestSiblingReplyTcpTest.pausedRequestReadsMustNotStopSiblingReplyWindowUpdates()

```text
org.opentest4j.AssertionFailedError: A paused request reader must not block window updates for its sibling's 1675062-byte reply; received 262082 bytes, write completed=false, failure=null ==> expected: <true> but was: <false>
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
	at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
	at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
	at app//io.libp2p.mux.YamuxPausedRequestSiblingReplyTcpTest.pausedRequestReadsMustNotStopSiblingReplyWindowUpdates(YamuxPausedRequestSiblingReplyTcpTest.kt:106)
	at java.base@21.0.12.1/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base@21.0.12.1/java.util.ArrayList.forEach(ArrayList.java:1596)

```

io.libp2p.transport.quic.QuicKuboTestJava.pingKubo()

```text
java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:694)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:542)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at java.base/sun.net.NetworkClient.doConnect(NetworkClient.java:178)
	at java.base/sun.net.www.http.HttpClient.openServer(HttpClient.java:539)
	at java.base/sun.net.www.http.HttpClient.openServer(HttpClient.java:644)
	at java.base/sun.net.www.http.HttpClient.<init>(HttpClient.java:290)
	at java.base/sun.net.www.http.HttpClient.New(HttpClient.java:394)
	at java.base/sun.net.www.http.HttpClient.New(HttpClient.java:416)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getNewHttpClient(HttpURLConnection.java:1327)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.plainConnect0(HttpURLConnection.java:1260)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.plainConnect(HttpURLConnection.java:1146)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.connect(HttpURLConnection.java:1075)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getOutputStream0(HttpURLConnection.java:1477)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getOutputStream(HttpURLConnection.java:1440)
	at io.libp2p.transport.quic.QuicKuboTestJava.getKuboPeerId(QuicKuboTestJava.java:65)
	at io.libp2p.transport.quic.QuicKuboTestJava.pingKubo(QuicKuboTestJava.java:21)
	at java.base/java.lang.reflect.Method.invoke(Method.java:580)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)

```

OBSERVED: First full-suite spotlessCheck and detekt gates succeeded for every project (the command failed only on :libp2p:test). Existing skipped tests are QuicServerTestJava.checkConnectionIsNotClosedByTimeout and EchoSampleTest.connect1; neither is edited.

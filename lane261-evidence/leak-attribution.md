# Paranoid leak-detector observations

No focused task (ten fresh JVMs, 200 cases total) contains a LEAK report. Selected input buffers have explicit refCnt == 0 checks after callback-close; new outgoing-write checks also verify every later inbound buffer is released and explicitly release every drained outbound buffer.

The larger unchanged-neighbour run passed 288 cases, with zero failures/errors and two inherited disabled cases, but paranoid detection logged warnings. Netty can report a collected buffer during a later test; the reporting class is not the allocation's owner. We therefore compared Created-at call sites with fresh probes at unchanged develop 5c08e2e226692e8f2b214d56fcf9ee405c61fd5e. All observed allocation families reproduce on base:

| Allocation family in reviewed-head reports | Independent base evidence | Disposition for this PR |
|---|---|---|
| SecureChannelTestBase handler input and SecIoHandshake/TestChannel fixture traffic | base-leak-check-summary.json plus full XML | Predates PR; introduction by this change refuted |
| MplexFrameCodecTest decoded test frames | same base probe, exact test allocation lines | Predates PR; introduction by this change refuted |
| Netty Quiche.allocateNativeOrder at codec handlerAdded | same base probe; both native-order allocation sites captured | Predates PR; introduction by this change refuted |
| Existing MultistreamTest and early-close-test outbound negotiation buffers | base-multistream-leak-check-summary.json and XML (13/13 passing cases, nine warning records) | Predates PR; introduction by this change refuted |

The broad base probe itself had 219 discovered cases: 216 passes, one connection-setup failure in ChildReadPauseTcpTest.serverConsumerCanPauseAndResumeRealMuxedStream for Yamux, two inherited disabled cases. Its full failure is preserved, not overwritten by a rerun seeking green. All four unchanged ChildReadPauseTcpTest cases passed on the reviewed head. We do not infer a further mechanism for the base failure solely from its ConnectionClosedException.

This review verifies ProtocolSelect's leftover-buffer release and attributes the wider warnings; it does not claim all library/test/Netty paths are leak-free or that those older ownership issues have been repaired. No production or neighbour test was edited for this attribution exercise. The PR stays focused on connection-setup ordering and its direct close paths, as the brief requests.

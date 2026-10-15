| Case | PR pass/fail | Base pass/fail |
|---|---:|---:|
| io.libp2p.core.PlaintextTcpTest.echoOverSecureConnection() | 5/0 | 5/0 |
| io.libp2p.core.PlaintextTcpTest.identifyOverSecureConnection() | 5/0 | 5/0 |
| io.libp2p.core.PlaintextTcpTest.largeEchoOverSecureConnection() | 5/0 | 4/1 |
| io.libp2p.core.PlaintextTcpTest.multiplePingChannelsOnTheSameConnection() | 5/0 | 5/0 |
| io.libp2p.core.PlaintextTcpTest.twoEchosOverSecureConnection() | 5/0 | 5/0 |
| io.libp2p.core.PlaintextTcpTest.unsupportedServerProtocol() | 5/0 | 4/1 |
| io.libp2p.mux.ChildReadPauseTcpTest.[1] mplex | 4/1 | 4/1 |
| io.libp2p.mux.ChildReadPauseTcpTest.[2] yamux | 3/2 | 5/0 |
| io.libp2p.pubsub.GossipApiTest.testFastMessageId() | 4/1 | 5/0 |
| io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame() | 5/0 | 5/0 |
| io.libp2p.transport.NettyOutboundBufferBackpressureTest.normallyDrainingYamuxPeerReceivesBulkPayloadIntact() | 4/1 | 4/1 |
| io.libp2p.transport.NettyOutboundBufferBackpressureTest.peerThatStallsBelowBudgetThenResumesReceivesEveryAcceptedByte() | 4/1 | 4/1 |

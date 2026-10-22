Written 2026-10-22T02:53:11.419540+00:00
Verified production head: 1d31d81c0c19c0e378ceae4d0136c3a9708c658f

Round 1: exit=0, {'tests': 204, 'failures': 0, 'errors': 0, 'skipped': 0}, heap=128MiB
Round 2: exit=0, {'tests': 204, 'failures': 0, 'errors': 0, 'skipped': 0}, heap=128MiB
Round 3: exit=0, {'tests': 204, 'failures': 0, 'errors': 0, 'skipped': 0}, heap=128MiB
Round 4: exit=0, {'tests': 204, 'failures': 0, 'errors': 0, 'skipped': 0}, heap=128MiB
Round 5: exit=0, {'tests': 204, 'failures': 0, 'errors': 0, 'skipped': 0}, heap=128MiB

Full suite: exit=1, {'tests': 1176, 'failures': 20, 'errors': 0, 'skipped': 2}

io.libp2p.pubsub.gossip.GossipBackwardCompatibilityTest :: testConnect_1_2_to_1_1() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.pubsub.gossip.GossipBackwardCompatibilityTest :: testConnect_1_1_to_1_2() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.core.HostTestJava :: addPingAfterHostStart() :: java.util.concurrent.ExecutionException: io.libp2p.etc.types.NonCompleteException: java.util.concurrent.CompletionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.core.HostTestJava :: ping() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.core.HostTestJava :: largeBlob() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.core.HostTestJava :: largePing() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.security.tls.TlsSecureChannelTest :: [1] 0 :: java.util.concurrent.ExecutionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.security.tls.TlsSecureChannelTest :: [2] 16 :: java.util.concurrent.ExecutionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.security.tls.TlsSecureChannelTest :: [3] 65519 :: java.util.concurrent.ExecutionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.security.tls.TlsSecureChannelTest :: [4] 65520 :: java.util.concurrent.ExecutionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.security.tls.TlsSecureChannelTest :: [5] 65535 :: java.util.concurrent.ExecutionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.security.tls.TlsSecureChannelTest :: [6] 65536 :: java.util.concurrent.ExecutionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.security.tls.TlsSecureChannelTest :: incorrect initiator remote PeerId should throw() :: java.lang.AssertionError: 
io.libp2p.pubsub.gossip.GossipTwoHostTest :: test message larger than mplex frame() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: existing peer subscribed to many topics() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: new peer subscribed to many topics() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: new peer subscribed to few topics() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.core.RpcHandlerTest :: test1() :: java.util.concurrent.ExecutionException: java.net.BindException: Address already in use
io.libp2p.transport.quic.QuicServerTestJava :: tlsAndQuicInSameHostPing() :: java.util.concurrent.ExecutionException: io.libp2p.etc.types.NonCompleteException: java.util.concurrent.CompletionException: javax.net.ssl.SSLException: failed to create an SSL_CTX
io.libp2p.transport.quic.QuicKuboTestJava :: pingKubo() :: java.net.ConnectException: Connection refused

Pure develop targeted control: 3 tests, 3 first-attempt failures in unchanged TLS/native initialization, fixed-port cascade, and local Kubo HTTP connection. No second full-suite run.

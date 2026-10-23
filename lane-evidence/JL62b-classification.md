# JL62b full-suite failure classification

PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/70 (left draft).

Tested PR: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/091b6d17280045ec2a1260ec890d1a06991bb6ac. Tested develop: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/bd7edb2570fb609c20245305c25af29d166a67c6.

The one locked full root suite completed in 5m 28s with 1,286 tests: 1,267 passed, 17 failed, and 2 existing disabled tests skipped. The library module accounted for 1,259 tests; the simulator contributed 27 passing tests. All 81 focused and neighbouring cases passed, including 13 new cases. No test was retried, and no stale result was counted. Shared cgroup memory before execution was 8,003,457,024 bytes; the observer sampled 10,736,848,896 bytes during execution at 06:52:45 UTC. The cgroup oom_kill count remained 1 before and after; no suite was killed.

All 17 failing cases ran separately on both commits, including individual JUnit parameter invocations: 34 actual test executions, exactly one test started each, with zero skips or aborts. Twelve fail on both; five pass on both when run alone. For all eight full-run binding failures, a real TCP listener was held at 127.0.0.1:40002 during one further selected test on each commit. All 16 controls produced the exact BindException on their first attempt, including the five cases that pass with the port free. The socket was closed after every case. No PR-specific failure was identified.

This is a comparison of individual cases against develop. It does not claim develop has a clean full suite or assert a 20-case baseline. Three names in the old 20-case list passed in the current full PR run: RpcHandlerTest.test1 and both GossipBackwardCompatibilityTest methods.

| Test | develop alone | PR alone | Port occupied (develop / PR) | Classification |
|---|---|---|---|---|
| `io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: existing peer subscribed to many topics()` | PASS | PASS | BindException / BindException | Pre-existing |
| `io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: new peer subscribed to many topics()` | PASS | PASS | BindException / BindException | Pre-existing |
| `io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: new peer subscribed to few topics()` | PASS | PASS | BindException / BindException | Pre-existing |
| `io.libp2p.core.HostTestJava :: addPingAfterHostStart()` | FAIL | FAIL | BindException / BindException | Pre-existing |
| `io.libp2p.core.HostTestJava :: ping()` | FAIL | FAIL | BindException / BindException | Pre-existing |
| `io.libp2p.core.HostTestJava :: largeBlob()` | PASS | PASS | BindException / BindException | Pre-existing |
| `io.libp2p.core.HostTestJava :: largePing()` | FAIL | FAIL | BindException / BindException | Pre-existing |
| `io.libp2p.pubsub.gossip.GossipTwoHostTest :: test message larger than mplex frame()` | PASS | PASS | BindException / BindException | Pre-existing |
| `io.libp2p.security.tls.TlsSecureChannelTest :: [1] 0` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.security.tls.TlsSecureChannelTest :: [2] 16` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.security.tls.TlsSecureChannelTest :: [3] 65519` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.security.tls.TlsSecureChannelTest :: [4] 65520` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.security.tls.TlsSecureChannelTest :: [5] 65535` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.security.tls.TlsSecureChannelTest :: [6] 65536` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.security.tls.TlsSecureChannelTest :: incorrect initiator remote PeerId should throw()` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.transport.quic.QuicKuboTestJava :: pingKubo()` | FAIL | FAIL | — | Pre-existing |
| `io.libp2p.transport.quic.QuicServerTestJava :: tlsAndQuicInSameHostPing()` | FAIL | FAIL | — | Pre-existing |

The full-run binding condition is an existing test dependency: HostTestJava and TwoGossipHostTestBase bind fixed port 40002. Their source is unchanged between the tested commits. The original full-run owner of that port is unknown; no specific competing process or leak is asserted. When run alone with the port free, three HostTestJava methods instead fail while creating a native TLS context, identically on both commits.

The other standalone failures reach TLSSecureChannel.kt:132 and native SSLContext.make with `javax.net.ssl.SSLException: failed to create an SSL_CTX`, or the Kubo lookup at QuicKuboTestJava.java:59 with `Connection refused` for localhost:5001. These are observed failure locations; a lower-level native-library cause is not established.

Both skipped tests were already annotated Disabled on develop and were not changed: EchoSampleTest.connect1 and QuicServerTestJava.checkConnectionIsNotClosedByTimeout.

The helper exports Gradle's actual runtime classpath, including built test fixtures, then uses the public JUnit unique-ID selector. Its first baseline discovery ran zero tests because the original exporter omitted the runtime fixture JAR. That invalid discovery was rejected and is excluded from every test count. The 34 standalone and 16 controlled executions each ran one test without retries. Full exception stacks remain in local out/JL62b-full-summary.json and the per-case XML files. Reviewed summaries and helper source are pushed; raw JVM recordings, environment/property dumps and build outputs are excluded.

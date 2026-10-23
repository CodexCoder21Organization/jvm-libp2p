# JL62b full-suite comparison

Plan:
- [done] Fresh clone, read repository and linked guidance, verify open draft/head, rebase on develop.
- [in progress] Run first actual full root Gradle suite once under shared lock; allow at least three execution hours, record memory and fresh results.
- [pending] Compare every failing test alone on develop and PR; diagnose/fix any new failures with fail-first evidence.
- [pending] Update draft PR counts/classification via REST PATCH/readback; preserve evidence and stop owned processes.

2026-10-23 05:19 UTC OBSERVED: PR https://github.com/CodexCoder21Organization/jvm-libp2p/pull/70 is OPEN/draft at 091b6d17280045ec2a1260ec890d1a06991bb6ac, base develop. Previous notes/handoff read: full command ./gradlew --no-daemon cleanTest test --continue; previous acquisitions ran zero full-suite tests. Current lane overrides obsolete 45m execution cap and Kotlin-only gate: never kill executing suite; local full suite is authoritative, Actions jobs are required. Shared cgroup memory 9,496,256,512 / 10,737,418,240 bytes. Seven-hour lane starts approximately 05:17 UTC; no code changes or delegates. github-repos search and status-report skills applied; no scheduler tool exposed, cadence maintained in tracked observations.

2026-10-23 05:22 UTC OBSERVED: fresh checkout at workspace/JL62b/repo; README, CLAUDE.md and FORK.md read (no AGENTS.md). Rebased successfully on unchanged develop bd7edb2570fb609c20245305c25af29d166a67c6, head remains 091b6d17. Suite queued 05:20:17 under flock, PID82253; three lanes ahead (holder65230, waiters44904/65525). Exact command after acquisition: timeout 4h ./gradlew --no-daemon cleanTest test --continue; no deadline on lock wait, four hours execution from acquisition. No test timeout or JVM options changed. Memory sampled every120seconds from queue onward. First evidence commit8dd25d04 pushed/verified on wip/JL62b-evidence-20261023. Required TESTING.md and PHILOSOPHY.md fully read from authenticated GitHub API after browser could not fetch them. Handoff guide also read; its legacy merge exception is superseded by service-only skill and explicit lane prohibition. Documentation says remote gate mandatory, but current lane explicitly overrides it as informational. Actions suites108121244990/108143125085 completed startup_failure, zero check runs; workflow build uses gradle ubuntu/windows jobs. Existing runs https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39891399114 and https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39899535098 both startup_failure. No CI mutating operation performed.

2026-10-23T05:23:24.602450+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10608943104; locks: 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65230 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T05:28:54.857805+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10244915200; locks: 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65230 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T05:34:25.250081+00:00 OBSERVED: full suite waiting for shared lock; memory.current=9710616576; locks: 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65230 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T05:39:55.511681+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10304851968; locks: 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65230 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T05:45:25.834715+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10233270272; locks: 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65230 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T05:50:52.340878+00:00 OBSERVED: full suite waiting for shared lock; memory.current=9415630848; locks: 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65230 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T05:56:22.568534+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10413113344; locks: 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65230 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:01:52.762608+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10237956096; locks: 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:07:23.255984+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10192080896; locks: 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:12:54.269722+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10444566528; locks: 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:18:46.599559+00:00 OBSERVED: full suite waiting for shared lock; memory.current=9634013184; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:24:16.817213+00:00 OBSERVED: full suite waiting for shared lock; memory.current=9989570560; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23 06:28 UTC OBSERVED: lock holder44904 has four current child JVMs aged0–5seconds; queue is active. Our82253 remains waiting behind44904 and65525. PR freshly open/draft091b6d17, baseunchangedbd7edb25; oom_kill1. Gradle8.14.3 JUnitPlatform filter source was read from https://github.com/gradle/gradle/blob/v8.14.3/platforms/jvm/testing-junit-platform/src/main/java/org/gradle/api/internal/tasks/testing/junitplatform/JUnitPlatformTestClassProcessor.java : MethodSource filtering uses class/method, not parameter invocation names. INFER: if parameterized failures require individual isolation, use the JUnit launcher public unique-ID selector for each invocation rather than claiming an all-parameters method run is one case. No comparison test run yet; preserve memory while another suite is executing.

2026-10-23T06:29:47.047987+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10335461376; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:35:17.400976+00:00 OBSERVED: full suite waiting for shared lock; memory.current=9206738944; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:40:47.724185+00:00 OBSERVED: full suite waiting for shared lock; memory.current=9518252032; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:46:20.189484+00:00 OBSERVED: full suite waiting for shared lock; memory.current=10706833408; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE* /tmp/claude-501/complete-suite.lock; 44904 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23 06:49 UTC OBSERVED: suite remains queued (two lanes ahead); PR/base unchanged, open/draft091b6d17/developbd7edb25. Evidence c44a6c37 pushed06:44 and verified remote. Handoff report upload from06:28 exited1 during service bytecode fetch; derived details in JL62b-challenge.md. Earlier report/update calls succeeded. report-challenge skill read; its automatic enqueue/merge conflicts with explicit lane prohibition, so challenge saved to WIP evidence without invoking its CLI. No production operation attempted.

2026-10-23 06:49 UTC OBSERVED: shared lock acquired06:49:15UTC after88m58s. Fresh fetch/rebase after acquisition confirms unchanged PR091b6d17280045ec2a1260ec890d1a06991bb6ac and developbd7edb2570fb609c20245305c25af29d166a67c6. Actual full Gradle command has started. Memory before8,003,457,024bytes; oom_kill1. Latest Handoff report upload succeeded (06:49 report), so the prior auxiliary upload failure is not blocking progress. FIFO queue order was not guaranteed: our lock acquired while65525 remained queued.

2026-10-23T06:51:50.684981+00:00 OBSERVED: full suite executing; memory.current=10735751168; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE* /tmp/claude-501/complete-suite.lock; 82253 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23T06:55:31.192131+00:00 OBSERVED: full suite finished {"exit": 1, "ended": "2026-10-23T06:54:45.559767+00:00"}; memory.current=7937351680; locks: 65525 flock   WRITE* /tmp/claude-501/complete-suite.lock; 39011 flock   WRITE  /tmp/claude-501/complete-suite.lock

2026-10-23 06:56 UTC OBSERVED: full root suite completed normally06:54:45UTC, Gradle5m28s, exit1. Fresh XML audit:1286total,1267passed,17failed,2pre-existing skips,0stale reports. libp2p alone1259tests (1240pass/17fail/2skip); root adds27passing tests from other modules. Initial console1259 count referred only to libp2p. Failure groups:8BindException,7TLS-context failures (6parameter cases plus wrong-peer assertion whose cause was SSL_CTX),1QuicKubo connection-refused,1QuicServer SSL_CTX. No new OOM kill (count1). The remembered20baseline is not confirmed:3of those old failure identities passed this full run. Plan: build current develop test classes, isolate each of17failing cases in its own JUnit process on develop and PR using the exact runtime classpaths; enforce one discovered/started test per invocation, no retries. For inherited TLS parameter cases use JUnit public unique-ID selection, because Gradle filters only methods. Save all comparisons before updating the PR. No implementation change is indicated yet.

2026-10-23 07:05 UTC OBSERVED: comparison helper validation rejected first baseline discovery:0testsfound/started, exit2, NoClassDefFoundError io/libp2p/tools/TestChannel. Cause is my export helper: testClasses builds compile dependencies but not the runtime-only testFixtures JAR. PR fixtures existed from the full suite, so PR parameter1 genuinely ran1test and failed SSL_CTX. Corrected export task to depend on the full test runtime FileCollection, preserving fixture and project dependency build ordering. Invalid baseline report retained separately; it is not counted as a test attempt. No product/test-source change, no assertion weakening, no suite rerun.

2026-10-23T07:07:41.209036+00:00 OBSERVED: comparison 8 io.libp2p.security.tls.TlsSecureChannelTest :: [1] 0 develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:07:59.146074+00:00 OBSERVED: comparison 0 io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: existing peer subscribed to many topics() develop=PASS PR=PASS; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:08:18.828791+00:00 OBSERVED: comparison 1 io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: new peer subscribed to many topics() develop=PASS PR=PASS; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:08:37.262718+00:00 OBSERVED: comparison 2 io.libp2p.pubsub.gossip.SubscriptionsLimitTest :: new peer subscribed to few topics() develop=PASS PR=PASS; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:08:51.865693+00:00 OBSERVED: comparison 3 io.libp2p.core.HostTestJava :: addPingAfterHostStart() develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:09:09.081978+00:00 OBSERVED: comparison 4 io.libp2p.core.HostTestJava :: ping() develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:09:42.092402+00:00 OBSERVED: comparison 5 io.libp2p.core.HostTestJava :: largeBlob() develop=PASS PR=PASS; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:09:56.908395+00:00 OBSERVED: comparison 6 io.libp2p.core.HostTestJava :: largePing() develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:10:18.577126+00:00 OBSERVED: comparison 7 io.libp2p.pubsub.gossip.GossipTwoHostTest :: test message larger than mplex frame() develop=PASS PR=PASS; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:10:37.259038+00:00 OBSERVED: comparison 9 io.libp2p.security.tls.TlsSecureChannelTest :: [2] 16 develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:10:56.146522+00:00 OBSERVED: comparison 10 io.libp2p.security.tls.TlsSecureChannelTest :: [3] 65519 develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:11:12.851889+00:00 OBSERVED: comparison 11 io.libp2p.security.tls.TlsSecureChannelTest :: [4] 65520 develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:11:26.717397+00:00 OBSERVED: comparison 12 io.libp2p.security.tls.TlsSecureChannelTest :: [5] 65535 develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:11:40.161586+00:00 OBSERVED: comparison 13 io.libp2p.security.tls.TlsSecureChannelTest :: [6] 65536 develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:11:55.309406+00:00 OBSERVED: comparison 14 io.libp2p.security.tls.TlsSecureChannelTest :: incorrect initiator remote PeerId should throw() develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:11:59.804537+00:00 OBSERVED: comparison 15 io.libp2p.transport.quic.QuicKuboTestJava :: pingKubo() develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23T07:12:22.730584+00:00 OBSERVED: comparison 16 io.libp2p.transport.quic.QuicServerTestJava :: tlsAndQuicInSameHostPing() develop=FAIL PR=FAIL; exactly one test discovered/started per process, zero skips/aborts, first attempt.

2026-10-23 07:11 UTC OBSERVED: standalone comparison has5full BindException victims passing on both versions (3Subscriptions,Host.largeBlob,GossipTwoHost). Three other Host methods fail on both with SSL_CTX when run alone. Source binds fixed40002 in HostTestJava and TwoGossipHostTestBase:39; port is currently unbound. INFER: full-only BindException is a test resource-state dependency, not yet classified solely from isolated passes. Next decisive experiment: hold an actual loopback listener at40002 while each of the8binding victims runs alone on each version, checking exact BindException and one started test. This directly creates the OS condition observed in the full run; no mock, CPU load, timeout/iteration/assertion change. Origin of the full-run occupancy is not established; do not claim a specific leak or competing process. Network namespace isolation probe was denied (unshare Operation not permitted); controls stay on the same local test port already used by these tests and release the guard after each case.

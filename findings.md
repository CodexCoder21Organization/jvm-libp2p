# flake-urlprotocol-dial6 findings
Launch: 2026-10-19 01:18 UTC. Wall deadline: 02:48 UTC.

## Plan
- IN PROGRESS: Resume dial5 evidence; fresh fork clone, read repo instructions; run two controls at pre-merge and merged develop, once each.
- PENDING: If controls establish pre-existing failures, build/publish one next snapshot; exact resolve/hash; draft version-record PR.
- PENDING: Fresh consumer clone and pin; original four-selector batch once, diagnostic ten workloads, content-selected dialers. Stop if original red.
- PENDING: If bar passes, draft pin PR and bounded native CI attempt audit; evidence checkpoint and process cleanup.

OBSERVED: Dial4 and dial5 findings read. No prior reading redone per continuation. Lane-specific instructions prohibit handoff changes, challenge CLI, delegation, merging, enqueueing and production changes; only one snapshot publish is authorized. Status-report skill workflow is limited to these findings by lane instructions.
The mechanism is process-wide inline admission allowing Noise work to hold an event loop with competing ready work; the evidence that demonstrates it is dial3 red/green/reversal and merged N1. This lane qualifies the version/pin only; no product fix or assertion/bound edit is planned. The controls distinguish known suite failures before/after N1; one green control followed by red merged run requires STOP.

## 2026-10-19 01:21 UTC — recovered selector correction
OBSERVED: The authoritative baseline f280853e /api/test-results filter text names testBytecodeRemoteRetirementNamesLocalLinkIdentity plus testColdBytecodeDialAlongsideRetirementOne/Two/Three. Dial5 findings instead name stressTerminalResponseRetirementCannotReuseClosingRoot. These are different tests: the stress test is FILTERED_OUT in f280853e. The brief requires the exact dial1 batch, so qualification will use the actual recorded filter, not the stale dial5 transcription. Baseline API page retained in evidence/baseline-four-page1.json. This correction changes no test source/assertion/bound.
OBSERVED: Pre-merge control acquired shared flock at 01:18:58 UTC with no whole-second wait and is compiling. Fresh UrlProtocol clone at c7d9a191 (same baseline main), README overview/build read; no root AGENTS. One next available version checked: snapshot-33 POM HTTP 404. No version/pin/publish yet.

## 2026-10-19 01:23 UTC — first control result
OBSERVED: Pre-merge develop 031e565a912acd94b5ff5c5a9840131f025654e3 targeted command completed in 3m31s, exactly two native XML test entries, one attempt each: GossipTwoHostTest.test message larger than mplex frame PASSED (3.934s); QuicKuboTestJava.pingKubo FAILED (0.064s), java.net.ConnectException: Connection refused at getKuboPeerId:65. Complete stack is in before-control-attempts.json and fork-before-control-native XML. The second control began at merged develop ecbc4a35, shared flock WAIT=ACQUIRED=01:22:31 UTC. No rerun beyond the two authorized comparisons, no source/assertion/bound change.
INFER: Kubo pre-existing failure is partially established pending identical merged result. The gossip pre-merge pass means the requested red-before/red-after proof is not yet established; a merged failure requires STOP under the brief, while two passes are an unspecified classification. Asked the orchestrator for the two-pass ruling while the authorized merged comparison continues; no publication proceeds without the required classification.

## 2026-10-19 01:26 UTC — terminal control audit
OBSERVED: Both authorized commands completed. Native Gradle XML contains exactly one test entry for each selected test at each head; no retry plugin/attempt-history and no duplicate test entries. Test source and base fixture SHA-256 values are byte-identical across heads (control-fixture-hashes.json). Pre-merge head 031e565a912acd94b5ff5c5a9840131f025654e3; merged head ecbc4a354c323f366150165842d6a039dd105128. Gradle command for both: ./gradlew --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m' -Pkotlin.compiler.execution.strategy=in-process :libp2p:test --tests 'io.libp2p.transport.quic.QuicKuboTestJava.pingKubo' --tests 'io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame'. Both Gradle exits 1 (each has exactly one failed test). Before: BUILD FAILED in 3m31s; after: BUILD FAILED in 3m26s. Shared lock WAIT and ACQUIRED were identical to the second: before 01:18:58, after 01:22:31 UTC.

| Test | Before N1 attempt 1 | After N1 attempt 1 | Classification |
|---|---|---|---|
| QuicKuboTestJava.pingKubo | FAILED 0.064s | FAILED 0.063s | Pre-existing local Kubo API dependency; same full stack at getKuboPeerId:65 |
| GossipTwoHostTest.test message larger than mplex frame | PASSED 3.934s | PASSED 4.121s | Dial5 full-suite failure NOT reproduced in either targeted control; origin unresolved |

INFER: These controls do not demonstrate a gossip regression caused by N1, and do not demonstrate the brief's required red-before/red-after proof that its full-suite failure is pre-existing. A pair of targeted passes cannot erase the original first-attempt suite failure or classify it by origin. The continuation explicitly allows publication if both failures are established pre-existing; that gate is not established for gossip. Two-pass classification was omitted from the brief; an asynchronous ruling request was sent while the second comparison ran, with no answer received at this checkpoint. No unchanged test was rerun beyond the two explicitly requested comparisons. No fix is guessed, no failure is ignored, and no artifact is published.

## Brief completion register
1. Resume dial5, fresh fork clone, merged prerequisite, repo context: DONE. Prior documentation readings inherited without repeating them, as instructed. Fresh consumer prepared, root instructions/README overview and build guidance read; no root AGENTS exists in either clone.
2. Classify two failures with exactly two requested control runs: DONE with four native attempt rows and full stacks; Kubo pre-existing established. Premise that these controls classify gossip as pre-existing is REFUTED by its two passes. Its suite failure remains unresolved, not declared fixed or harmless.
3. Publication/version-record PR/exact resolve-hash: STOP at unestablished publication gate, no version changed, built release, publication or PR. Snapshot-33 was checked free (404) only; it is not reserved.
4. Consumer pin/four-selector qualification/ten diagnostic copies/content-selected sweep: not executed against an unpublished snapshot. Same main c7d9a191 established; thirteen prior diagnostic sources and prior 1334-entry/1328-selector content inventory retained as evidence, including child-JVM tests. Independent 931-file content search retained. 2204 prospective pin source paths identified, with no source changes. Correct four selectors recovered from authoritative baseline record: testBytecodeRemoteRetirementNamesLocalLinkIdentity and testColdBytecodeDialAlongsideRetirementOne/Two/Three; dial5 stress-test transcription was wrong. No downstream result or improvement is claimed; baseline stays 0/4 and 0/10.
5. Pin PR and native CI attempt audit: no pin PR or CI run exists; no pending/success CI claim. Existing-test edit ledger EMPTY (no edits anywhere); no fixture correction, product fix or reversal test applicable.
6. Checkpoint/cleanup: native XML/logs/full control stacks and findings on wip/flake-urlprotocol-dial6-evidence. No handoff write, challenge CLI, subagent, merge, queue, deploy, production restart or unauthorized publication.

## Complete control failure stacks
fork-before
io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame() attempt=1 status=PASSED duration=3.934s

io.libp2p.transport.quic.QuicKuboTestJava.pingKubo() attempt=1 status=FAILED duration=0.064s
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

fork
io.libp2p.pubsub.gossip.GossipTwoHostTest.test message larger than mplex frame() attempt=1 status=PASSED duration=4.121s

io.libp2p.transport.quic.QuicKuboTestJava.pingKubo() attempt=1 status=FAILED duration=0.063s
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


# Review finding repairs — started 2026-10-27 21:37 UTC

Plan:
- IN PROGRESS: verify handoff, instructions, live heads and test mechanisms.
- TODO: PR 76 readiness regression red, fix green, reverted fix red; scope JDK 24 to Kubo; targeted Windows and Linux verification; description and push.
- TODO: PR 77 controlled unrelated Noise caller regression red with shared address, green isolated, reversal red; gap review and push.
- TODO: independent test and code review; descriptions; final JSON and clean up.

OBSERVED: Both assigned PRs OPEN at exact supplied heads. Default branch develop. Fresh clones under workspace/jvm-libp2p-fix213755-{76,77}. Original handoff read through hc.sh. Owner worker findings file absent; full review read. Repo uses native Gradle, not kompile; handoff records explicit native exception. No merge, enqueue, deploy or settings changes authorized.
Documents read: handoff, full review, project README/FORK/CLAUDE, DocumentationRepository TESTING.md, PHILOSOPHY.md, CODE_REVIEW.md (large output requires chunk follow-up).

Mechanisms: readiness reads file then treats missing watch event as failure without rechecking state; fix will consume actual process output. Whole Windows JDK 24 changes CompletableFuture.join cancellation behavior and Android tooling, so keep Gradle/general tests on 17 and select 24 only for Kubo tests.
Invariant table:
| Resource | Creator/owner | End/close | Observable and failure case |
| Kubo process/output | test | terminate bounded then drain/close | pre-existing or later readiness output recognized even without file event; early EOF reported; no reader left running |
| Windows Gradle/test runtime | workflow/Gradle | task exit | general tasks retain 17; only Kubo uses corrected wait runtime 24; tests retained |
| Noise fixture listener | harness | hosts stop in finally | intended dialers only in responder accounting; unrelated real caller targets ordinary loopback, never isolated address |
| Saturation executor | harness | release and bounded shutdown | original responder queue/count assertions and 120 dialers retained |

OBSERVED 21:44 UTC: PR76 fail-first regression committed and pushed on disposable branch verify/w2-readiness-red-213755 before any fix push. Run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40588933548 is executing. It launches a real child that emits readiness before the waiter begins and stays alive; its output pipe has readiness but no log modification can notify a file watcher. The extracted old watch algorithm therefore has no readiness event. No mock or external service. Green implementation in progress consumes that pipe and drains all output into the owned log.
INFER: JDK21 does not carry the relevant fix: current openjdk/jdk21u ProcessImpl.java still returns on getExitCodeProcess!=STILL_ACTIVE, while jdk24u checks native isProcessAlive. Thus use 24 only for all Kubo class cases in a separate check dependency; build/Android/general tests remain17. Every test remains assigned to a build task.
Delegated well-understood PR77 implementation to gpt-6-luna max, one item only. Design/diagnosis stays with parent; no local JVM started by either lane.

OBSERVED 21:48 UTC: readiness fail-first Windows run completed first attempt:1 test,1 assertion failure,0 errors/skips. This is the file-watch poll returning null while the child has already emitted readiness, not a compilation/process-start error. Full assertion stack follows from XML:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.libp2p.transport.quic.QuicKuboTestJava" tests="1" skipped="0" failures="1" errors="0" timestamp="2026-10-27T21:45:19.638Z" hostname="runnervmdaln5" time="5.177">
  <properties/>
  <testcase name="readinessAlreadyBufferedWithoutFileNotification(Path)" classname="io.libp2p.transport.quic.QuicKuboTestJava" time="5.177">
    <failure message="org.opentest4j.AssertionFailedError: Kubo did not become ready:  ==&gt; expected: not &lt;null&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: Kubo did not become ready:  ==&gt; expected: not &lt;null&gt;
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:152)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertNotNull.failNull(AssertNotNull.java:49)
	at app//org.junit.jupiter.api.AssertNotNull.assertNotNull(AssertNotNull.java:41)
	at app//org.junit.jupiter.api.Assertions.assertNotNull(Assertions.java:320)
	at app//io.libp2p.transport.quic.QuicKuboTestJava.awaitReadiness(QuicKuboTestJava.java:123)
	at app//io.libp2p.transport.quic.QuicKuboTestJava.readinessAlreadyBufferedWithoutFileNotification(QuicKuboTestJava.java:148)
	at java.base@24.0.2/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base@24.0.2/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base@24.0.2/java.util.ArrayList.forEach(ArrayList.java:1604)
</failure>
  </testcase>
  <system-out><![CDATA[]]></system-out>
  <system-err><![CDATA[]]></system-err>
</testsuite>

```
OBSERVED: Windows green proof submitted https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40589636928. It keeps Gradle/JDK17 general tests and explicitly invokes all3 Kubo cases on24. Separate Linux11 targeted compatibility run submitted on verify/w2-readiness-linux-213755 to cover output drain through actual Kubo shutdown on Unix. No full local suite, no local JVM, no PR required-CI debugging.

OBSERVED 21:55 UTC: Runtime controls complete, https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40589867110: reviewed62d84d62 onWindows24=18tests/6failures/0errors/skips; actual develop onWindows17=18tests/0failures/errors/skips. The workflow explicitly checks these counts from XML; no assertions weakened. XML and full stacks at /tmp/w2-fix213755/cancellation-controls/.
OBSERVED: First green-source compilation had a Java variable-shadowing error (InputStreamReader received local BufferedReader instead of field InputStream); both Windows40589636928 andLinux40589744297 stopped before any tests. Corrected to this.input. Full logs retained at /tmp/w2-fix213755/readiness-{green,linux}-full.log. These are not red/green runtime proofs.
OBSERVED: Strengthened the readiness regression to also have ready text readable in the file while the watcher receives no event: real child uses FileChannel.map/put and keeps the channel alive, emits the same text on stdout, and marks emission outside the watched directory. A test-only before-wait callable forces the known ordering after the old initial read. This now reproduces the own-error-message-contains-ready condition instead of only an already-buffered pipe. Pushed fail-first mapped version on verify/w2-mapped-readiness-red-213755; https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40590575890 running. Await red before green push.
Gap search: actual-Kubo ping covers readiness arriving after reader attachment and full shutdown; mapped regression covers ready state not communicated by a directory notification. EOF/errors propagate via ready/drain futures; reader and process each have bounded cleanup. No new production contract or public test accessor is introduced.

OBSERVED 21:59 UTC: strengthened mapped-write control red on first attempt: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40590575890.1 test,1 assertion failure,0errors/skips; assertion contains both "Kubo did not become ready" and "Daemon is ready", exactly matching reviewed condition. Full XML/stack follows.
```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.libp2p.transport.quic.QuicKuboTestJava" tests="1" skipped="0" failures="1" errors="0" timestamp="2026-10-27T21:58:18.197Z" hostname="runnervmdaln5" time="5.342">
  <properties/>
  <testcase name="readinessAlreadyBufferedWithoutFileNotification(Path)" classname="io.libp2p.transport.quic.QuicKuboTestJava" time="5.342">
    <failure message="org.opentest4j.AssertionFailedError: Kubo did not become ready: Daemon is ready&#10; ==&gt; expected: not &lt;null&gt;" type="org.opentest4j.AssertionFailedError">org.opentest4j.AssertionFailedError: Kubo did not become ready: Daemon is ready
 ==&gt; expected: not &lt;null&gt;
	at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:152)
	at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at app//org.junit.jupiter.api.AssertNotNull.failNull(AssertNotNull.java:49)
	at app//org.junit.jupiter.api.AssertNotNull.assertNotNull(AssertNotNull.java:41)
	at app//org.junit.jupiter.api.Assertions.assertNotNull(Assertions.java:320)
	at app//io.libp2p.transport.quic.QuicKuboTestJava.awaitReadiness(QuicKuboTestJava.java:129)
	at app//io.libp2p.transport.quic.QuicKuboTestJava.readinessAlreadyBufferedWithoutFileNotification(QuicKuboTestJava.java:150)
	at java.base@24.0.2/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base@24.0.2/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base@24.0.2/java.util.ArrayList.forEach(ArrayList.java:1604)
</failure>
  </testcase>
  <system-out><![CDATA[]]></system-out>
  <system-err><![CDATA[]]></system-err>
</testsuite>

```
OBSERVED: Pushed green stream-reader implementation only after recording that mapped red. New proof branch verify/w2-mapped-readiness-green-213755 runs Windows17 general18/24 all3Kubo cases and Linux11 all3Kubo cases, with formatting. Product commit is separately preserved on product/w2-review-fix76 so disposable workflows never enter the PR.

OBSERVED 22:02 UTC: Noise preliminary red was rejected as proof because an isolated unrelated caller completed inline before the saturated path was active. Parent inspected NoiseXXSecureChannel.enqueueCrypto: the saturation-selector callback is consulted only after offload is selected. Parent prescribed one real established anchor connection before selector installation, one event-loop worker in the isolated saturated child,120 intended dials before the outside caller; hasOtherConnectionsOnLoop then forces the exact path independent of scheduler/startup timing. Agent executing that fully specified correction. Original5-second handshake and6-second wait remain; no11-second wait introduced.

OBSERVED 22:05 UTC: Windows split-runtime proof succeeded first execution of final mapped regression:18/18 general cases on17 plus3/3Kubo on24, static formatting green. Linux11 ran3Kubo cases: mapped regression andkey generation pass;actual1000ping succeeds butreader drain fails oncleanup. Process.destroy closes its own stdout InputStream onUnix, so draining afterdestroy hits IOException Streamclosed. This is a new test-fixture ownership error, reproduced bythe existing real ping test, not an upstream Java behavior bug. Fix is to ask the real owned Kubo API/CLI toshutdown and let stdout reachEOF;retain bounded wait/forcedtermination and propagateerrors. Full failingXML/stack:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="io.libp2p.transport.quic.QuicKuboTestJava" tests="3" skipped="0" failures="1" errors="0" timestamp="2026-10-27T22:03:18.066Z" hostname="runnervmtai0g" time="2.375">
  <properties/>
  <testcase name="pingKubo(Path)" classname="io.libp2p.transport.quic.QuicKuboTestJava" time="2.207">
    <failure message="java.util.concurrent.ExecutionException: java.io.UncheckedIOException: Could not read owned Kubo output" type="java.util.concurrent.ExecutionException">java.util.concurrent.ExecutionException: java.io.UncheckedIOException: Could not read owned Kubo output
	at java.base/java.util.concurrent.FutureTask.report(FutureTask.java:122)
	at java.base/java.util.concurrent.FutureTask.get(FutureTask.java:205)
	at io.libp2p.transport.quic.QuicKuboTestJava$DaemonOutput.close(QuicKuboTestJava.java:175)
	at io.libp2p.transport.quic.QuicKuboTestJava.pingKubo(QuicKuboTestJava.java:107)
	at java.base/java.lang.reflect.Method.invoke(Method.java:566)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1541)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1541)
Caused by: java.io.UncheckedIOException: Could not read owned Kubo output
	at io.libp2p.transport.quic.QuicKuboTestJava$DaemonOutput.lambda$new$1(QuicKuboTestJava.java:154)
	at java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:515)
	at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
	at java.base/java.lang.Thread.run(Thread.java:829)
Caused by: java.io.IOException: Stream closed
	at java.base/java.io.BufferedInputStream.getBufIfOpen(BufferedInputStream.java:176)
	at java.base/java.io.BufferedInputStream.read1(BufferedInputStream.java:289)
	at java.base/java.io.BufferedInputStream.read(BufferedInputStream.java:351)
	at java.base/sun.nio.cs.StreamDecoder.readBytes(StreamDecoder.java:295)
	at java.base/sun.nio.cs.StreamDecoder.implRead(StreamDecoder.java:337)
	at java.base/sun.nio.cs.StreamDecoder.read(StreamDecoder.java:179)
	at java.base/java.io.InputStreamReader.read(InputStreamReader.java:181)
	at java.base/java.io.BufferedReader.fill(BufferedReader.java:161)
	at java.base/java.io.BufferedReader.readLine(BufferedReader.java:326)
	at java.base/java.io.BufferedReader.readLine(BufferedReader.java:392)
	at io.libp2p.transport.quic.QuicKuboTestJava$DaemonOutput.lambda$new$1(QuicKuboTestJava.java:144)
	... 5 more
</failure>
  </testcase>
  <testcase name="keyPairGeneration()" classname="io.libp2p.transport.quic.QuicKuboTestJava" time="0.029"/>
  <testcase name="readinessAlreadyBufferedWithoutFileNotification(Path)" classname="io.libp2p.transport.quic.QuicKuboTestJava" time="0.137"/>
  <system-out><![CDATA[Ping is 9
Ping is 1
Ping is 2
Ping is 1
Ping is 2
Ping is 1
Ping is 2
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 2
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 2
Ping is 1
Ping is 4
Ping is 2
Ping is 1
Ping is 1
Ping is 1
Ping is 2
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 2
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 2
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
Ping is 1
Ping is 0
Ping is 0
PeerId: 00250802122102f6c4a165458b789c331e20ff74a8e7adb6173a36c86c3d5a766fc09c9bd77a95
]]></system-out>
  <system-err><![CDATA[]]></system-err>
</testsuite>

```

OBSERVED 22:06UTC: mapped Windows green artifact downloaded:3/3Kubo cases at test-results/kuboProcessTest,18/18 general cases at test-results/test,0errors/skips inboth. This also proves routing rather than removing tests. Candidate shutdown-fix run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40592111312 is executing the same Windows/Linux targeted scopes.
Parent test-review pass on Noise found a new immediate receiver-publication assertion after anchor initiator completion. Agent now waits for the real receiver connection-handler future before asserting publication; this is the same independently finishing peer boundary already corrected in the sibling PR. No new artificial delay. Original-route red prior to that ordering refinement is121queued/120failures; final setup red still required before adoption.

OBSERVED 22:11UTC: final owned-shutdown source first-attempt targeted green, https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40592111312: Linux11=3/3Kubo, Windows general17=18/18 plusKubo24=3/3,0failures/errors/skips; SpotlessCheck passed onboth. Formatted source uploaded fromboth platforms has identical SHA256 8059e68f3375f2ddf95a67c6735a66c788bb57cba2bc1907f7085cd529f17e57. Candidate product branch now uses exactlythatfile, with accurate README/FORK runtime and cleanup descriptions.
OBSERVED: required reversal submitted https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40593008122: Windows restores filewatch+whole24 (expect1mappedreadiness+6cancellationfailures in19cases); Linux restores Process.destroy beforedrain (expectStreamclosed inactual1000ping case). No newfinalgreen rerun willfollow: restore already-proven blob and verifyhash.
Independent root code/test review: confirmed realchild andrealKubo public OS/API paths; no mock, reflection, exposed productiontesthook, discarded readerfailure or changed bounds/counts. Readinessregressionforcesfile-visible/noevent stateafteroldinitialread, actualKubo covers laterreadyoutputanddrain, output errors propagate, native24 scopeassignsall3Kubocases tocheckratherthanomittingthem. CleanupreviewfoundandclosedtheUnixstreamclosuregaptestfirstusingrealpingred/green. Productdiffhas5expectedfilesonly, nobuild/evidenceartifact.

OBSERVED 22:19 UTC: PR 76 pushed after fresh fetch/rebase and an OPEN/expected-head check, using an explicit force-with-lease. New head is be43caad. The final Java hash matches both green platform uploads (8059e68f...). Only the five expected files changed. Required reversals were verified on the first attempt: Windows 19 cases/7 failures (one readiness plus six cancellation), Linux one real Kubo case/one Stream closed failure after all 1,000 pings. These are JUnit failures, not compiler errors.

OBSERVED ecosystem friction: bundled gh pr edit fails after the head push with `GraphQL: Projects (classic) is being deprecated in favor of the new Projects experience, see: https://github.blog/changelog/2024-05-23-sunset-notice-projects-classic/. (repository.pullRequest.projectCards)`. The workaround is a structured REST PATCH with the body in JSON. REST body update succeeded. The report-challenge CLI automatically merges its record, conflicting with this brief's explicit no-merge/no-enqueue constraint; the exact error and workaround are recorded here instead.

Plan: PR 76 code, targeted proofs, independent review and description are COMPLETE. Its full native gate validation is IN PROGRESS. PR 77's final ownership review closed the async child lifetime gap by using a synchronous caller with lexical cleanup; the final source proof sequence is underway.

OBSERVED 22:23 UTC: Windows baseline provenance independently verified. Historical run40521828270 checked out merge933b371 (99e82414 into develop25efb199). `git diff --quiet origin/develop 99e82414 -- libp2p/src` succeeds: all library and test sources were byte-identical to current develop; only workflow, FORK and Kubo installer differed. Baseline XML totals1444cases/8failures/0errors/2existing skips are parsed into /tmp/w2-fix213755/windows-baseline.json. The eight identities are PoisonedDefaultSecureRandomTest (one), YamuxOutboundBufferBudgetRefCountTest (one), NetworkMultiAddressConnectTest (one), NoiseProbePhasesTest (five). Compare final Windows failures by exact class/case/type, not a blanket unrelated label.

OBSERVED 22:46 UTC: Full native run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40593772531 is terminal FAILED. Linux JUnit totals:1,446 cases,1 failure,0 errors,2 existing skips. Failed case is YamuxPausedStreamKeepsConnectionReadingTest.closingAPausedStreamWithQueuedDataKeepsTheConnectionReading: "Pressure-state payloads must remain held before close ==> expected: <0> but was: <1>". Its test/library read code is unchanged from develop; a controlled default-branch proof is being prepared before classifying the cause. Linux also failed :examples:pinger:dokkaJavadoc with java.lang.OutOfMemoryError: Metaspace under the unchanged512MiB cap. This is observed failure, not a root-cause claim; earlier handoff MAT queries remain invalid and no leak fix is claimed. Full raw log and stack: /tmp/w2-fix213755/native-final-failed.log; all Linux cases in linux-case-ledger.json. The required Linux-green condition is NOT MET; no unchanged CI rerun or cap/test weakening was attempted.
Windows completed1,446 cases/6 failures/0 errors/2 existing skips. All six exact class/case/type identities are in the baseline8; no new identities. Android and documentation tasks completed; only :libp2p:test failed. Comparison JSON: /tmp/w2-fix213755/windows-comparison.json. The baseline run40521828270 uses source byte-identical to develop (only CI/installer/docs differ).
PR77 is FIXED and pushed at0e7c69f436bbecce1e4aa158c103f8410dae4f53; only the intended Noise test file changed and checkout/remote head are clean/matching. Current outcome for the whole brief must remain PARTIAL because Linux full verification is still red, even though all requested code/description repairs and targeted negative/positive/reversal proofs are complete.
Documentation conflict read during handoff upkeep: PlanRepository/handoffs/README.md retains a legacy instruction to merge handoff PRs. The current create-handoff skill says that flow is retired; the explicit user no-merge instruction also takes precedence. Only the central handoff CLI update will be used.

## Final review state — 2026-10-27 22:52 UTC

OBSERVED: Both requested PRs are OPEN and unmerged. https://github.com/CodexCoder21Organization/jvm-libp2p/pull/76 head is be43caad1d51b48635b749158936bc2e347e7835; https://github.com/CodexCoder21Organization/jvm-libp2p/pull/77 head is 0e7c69f436bbecce1e4aa158c103f8410dae4f53. Default develop remains25efb1996bd79e59e6eef28d2d44d9a0405fdd78. No merge, enqueue, deployment or publication was performed.

1. Readiness finding: FIXED with deterministic red/green/reversal evidence. Final Java source matches both green platform uploads byte for byte.
2. Runtime finding: runtime scope and Windows regression FIXED; general17/Kubo24 retains every test. Full Windows reports six failures, each exactly present in the eight-case develop baseline. Android and documentation complete. The required Linux-green condition is NOT MET: the full head run has a Yamux pressure assertion plus Dokka Metaspace. This is an unfinished verification requirement, not a waived failure or a claim of full completion.
3. Description finding: FIXED; reason now opens the description and the approval hold immediately follows it.
4. Original Noise caller route: FIXED. Red121/120, green120/120 and formatted reversal121/120 are recorded. The formatter changed only whitespace; parent independently verified identical non-whitespace character sequence and identical string literal sequence between the actually passing synchronous source and final formatted source. Spotless passes; the three Detekt findings are byte-identical to develop and already corrected in the sibling PR.

OBSERVED: The separate controlled develop proof https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40597356309 has one test, one assertion failure, zero errors/skips, on its first attempt. It holds a real automatic read after a real primer payload, pauses off-loop, releases the read and executes the original64-frame pressure assertion. It reproduces exactly expected0/actual1 in1.232seconds. Source is pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/w2-yamux-pause (c29a36adf73af5f818ee07fa17ce8f939ef5d019). No production fix was guessed or written. Findings: /tmp/w2-fix213755/yamux-pause-findings.md.

INFER: The controlled ordering explains why the existing read-pause test's no-op after its off-loop setter does not guarantee zero later delivery. A proposed pause-on-loop fixture correction still needs its own green and reversal proof before adoption. Dokka's precise retained-memory cause is unestablished; the observed Metaspace error and earlier invalid MAT reports do not prove a particular leak. Read OOM.md and keep existing caps; obtain valid heap/classloader evidence before fixing.

Overall outcome is PARTIAL because the Linux verification criterion remains failed. All requested code and description repairs are pushed, targeted behavior is proven, and the two full-build failures are recorded explicitly for the owner. No unchanged full CI was rerun hoping for green.

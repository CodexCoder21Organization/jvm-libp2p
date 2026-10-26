# PUB71 progress

Plan
- In progress: confirm upstream merge and reserve a free Maven version by checking remote files.
- Pending: build merged source, publish once, compare jar and POM SHA-256 values.
- Pending: apply the existing consumer pin work, check clean retrieval, run ten scenario repetitions and targeted tests.
- Pending: run the complete suite race, update the open consumer PR, and watch required checks.
- Pending: final correctness review, preserve findings remotely, and stop all owned tasks.

OBSERVED: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71 merged at 2026-10-26T20:00:39Z with commit 25efb1996bd79e59e6eef28d2d44d9a0405fdd78.
OBSERVED: https://github.com/CodexCoder21Organization/UrlProtocol/pull/945 remains open.
INFER: The objective explicitly authorizes this Maven publication; the generic publication prohibition applies to other artifacts. The objective explicitly requests checking required CI on this existing PR.

The mechanism is a full callback queue losing a completed future notification, and the evidence that demonstrates it is the prior lane's snapshot-25 result of 10/10 unsuccessful runs versus local snapshot-41 result of 10/10 successful runs. This inherited evidence will be checked against the linked findings and merged source before publication.

## Preparation checkpoint
OBSERVED: snapshot-41 jar and POM both return HTTP 404. The fresh upstream checkout uses develop as its default branch; origin/main does not exist. Fetch and rebase against origin/develop retained the exact merged commit 25efb1996bd79e59e6eef28d2d44d9a0405fdd78. The source declares snapshot-41.
OBSERVED: read both README files where available, upstream FORK.md, build configuration, the linked MQ945 findings, and Testing Architecture sections Test-Driven Development and Never Calibrate a Reproducer to a Machine. The consumer clone is still receiving data, so its README will be read when checkout completes.
OBSERVED: the prior lane measured the deterministic after-queue-drain scenario red on attempt 1 in 10/10 snapshot-25 runs, green on attempt 1 in 10/10 local snapshot-41 runs, then red again after returning to snapshot-25. The original consumer cleanup body passed attempt 1 with local snapshot-41 and no added wait. This lane will retain the original consumer assertions.
INFER: publication can proceed with snapshot-41 after a successful exact-source build. No new source fix is proposed.

## Build milestone
OBSERVED: Gradle jar and POM generation both completed with exit 0 from merged source; no implementation or build configuration was changed. Staged POM coordinates were parsed and checked. Immediately before upload the POM still returned 404.
Built jar SHA-256: 2482ff751bd14b929b3e6b9ad6161ce7a645eaff1c1bad7d43423a731edbfe3d.
Built POM SHA-256: 144f73fdee0cfe6a2269c758976d6a642e588f4e619e7f602e3438bfb23cf48e.
OBSERVED: publisher metadata confirms documented CLI version 0.0.6 is latest. The upload is now running using the user-authorized publication objective.
Durable checkpoint: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/PUB71-publication-evidence .

## Publication milestone
OBSERVED: publisher exited 0. Snapshot-41 jar and POM downloaded from kotlin.directory both match the built SHA-256 values. No existing version was replaced.
.jar downloaded SHA-256 2482ff751bd14b929b3e6b9ad6161ce7a645eaff1c1bad7d43423a731edbfe3d
.pom downloaded SHA-256 144f73fdee0cfe6a2269c758976d6a642e588f4e619e7f602e3438bfb23cf48e
OBSERVED: the consumer full-history transfer was still receiving hundreds of megabytes before checkout; a second fetch was started before checkout completed. Both owned transfer tasks were stopped; a new depth-one clone will fetch only the required branches. This is a local preparation correction.
Plan: publication complete; consumer verification in progress.

OBSERVED at 2026-10-26 20:07 UTC: all times above are milestone labels; the upstream merged timestamp is exact. Upstream evidence is now committed and pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/4561bf404186188428c20df83e3b6ddcb650b713 . Clean Coursier cache retrieval exited 0 and its jar SHA-256 matches the built and downloaded jar. The consumer README and scripts have now been read.

## Consumer scope
OBSERVED: the supplied pin branch changes 2695 existing Kotlin scripts only by version text, adds one completed-close scenario, and changes one existing cleanup body. It also contains investigation records from earlier work. The existing PR changes one independent logging scenario.
INFER: the required pin consists of the version-only selections plus the added completed-close scenario. The additional cleanup wait is unnecessary because the upstream result handoff fix owns the behavior and the original consumer body already passed in the prior lane. This lane will not copy that wait or unrelated investigation records.
OBSERVED: the consumer branch was rebased onto current main after configuring its local Git identity. A continuation from the initial identity pause had incomplete local rebase metadata; restarting that local rebase completed normally. No remote PR branch has been updated yet.
Test edit inventory: a new scenario from the supplied pin branch is initially selected against snapshot-25 to verify the same completed-root result condition before selecting snapshot-41. Existing test bodies remain unchanged; all existing pin edits will be version text only.

## First local baseline milestone
OBSERVED: the new completed-close scenario fails on attempts 1, 2, and 3 under snapshot-25. Each recorded stack trace names the exact assertion: A completed physical close must remain observable after its event-loop queue has drained. Source promise completion and full queue are asserted first; accepted tasks and an executor barrier finish before this assertion. This is a result-delivery check after queue recovery.

## 2026-10-26 20:12 UTC consumer pin milestone
OBSERVED: baseline exact assertion confirmed in all three attempts. Checkpoint: https://github.com/CodexCoder21Organization/UrlProtocol/tree/wip/PUB71-root-baseline . The consumer proposal now selects snapshot-41 in 2697 files, comprising the supplied branch's existing selections and its new completed-close scenario. The original cleanup body was compared after normalizing only the libp2p coordinate and is identical. README now names the published version and links its upstream change.
Existing-test edit inventory: each selected existing test changes only its libp2p artifact annotation from its prior snapshot to snapshot-41. No assertion, wait, bound, iteration count, or test body changed. The original PR's existing logging adjustment remains as rebased. New-test inventory: the completed-close scenario is copied from F71-consumer-pin unchanged apart from the temporary baseline selection already measured.
Plan: baseline complete, published comparison running, ten original-consumer repetitions next, then targeted neighbors and the mandatory complete-suite race.

OBSERVED 2026-10-26T20:13:00.089723+00:00: published snapshot-41 completed-close scenario passes on attempt 1; [{'success': 'true', 'attempts': 1, 'time': '2063'}]. Existing scripts checked individually: all 2696 tracked Kotlin changes are libp2p coordinate text only.
OBSERVED 2026-10-26T20:13:18.022332+00:00: consumer-1 exit 1, first-attempt False, attempts [1].

OBSERVED 2026-10-26T20:13:57.016544+00:00: the first consumer invocation did not execute its test body (XML time 0). Dependency preparation could not establish a connection for org.jetbrains.kotlin:kotlin-test:1.8.22 from kotlin.directory. This is not counted as a runtime test attempt. Full preparation stack trace is retained locally at consumer-1-preparation-stack.txt.
OBSERVED 2026-10-26T20:14:08.095057+00:00: direct request for the exact Kotlin test jar returns HTTP 200. Measured consumer runs restarted only after this availability check. The lane does not change dependency versions or test behavior to address this preparation event.
OBSERVED 2026-10-26T20:14:28.798075+00:00: consumer-1 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:14:40.119316+00:00: consumer-2 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:14:51.975689+00:00: consumer-3 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:15:03.908279+00:00: consumer-4 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:15:17.483258+00:00: consumer-5 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:15:30.281496+00:00: consumer-6 exit 0, first-attempt True, attempts [1].

## Independent test coverage pass
OBSERVED: the added completed-close test first confirms a completed physical root close, holds its real event loop while filling its bounded queue, converts the completed close result, releases accepted tasks, crosses an executor barrier, and then checks result visibility. It preserves all eight source assertions and thirteen existing time-bound calls from the supplied branch. Both hosts and the held loop are released in finally paths. It uses real localhost hosts and public channel/result APIs, with no substitute network service.
Resource table: hosts are created and owned by the scenario and closed in finally; the root channel is created by its host and closed through the protocol root API; its close result is terminal once complete; queued work belongs to the event loop and is allowed to finish before host cleanup. The test only observes the returned close result and never changes it.
OBSERVED: all 2696 changed existing scripts preserve their entire text after normalizing the libp2p coordinate. No implementation, interface, persisted format, or test fixture is changed. The production pin and test annotations select the same published version.
INFER: the new scenario directly covers the consumer result-delivery condition. Upstream pending-result and cancellation behavior remains covered by the already-reviewed upstream implementation and its tests; this consumer proposal adds no such implementation.
OBSERVED 2026-10-26T20:15:41.746284+00:00: consumer-7 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:15:53.406958+00:00: consumer-8 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:16:06.863082+00:00: consumer-9 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:16:18.454393+00:00: consumer-10 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:16:36.835935+00:00: pr-target-1 exit 0, first-attempt True, attempts [1].

OBSERVED 2026-10-26T20:16:44.573254+00:00: original cleanup scenario passes 10/10 measured runs on attempt 1, with no second attempts. The earlier preparation-only invocation is retained separately and is not represented as a runtime pass. The completed-close published comparison also passed attempt 1.
OBSERVED 2026-10-26T20:16:58.082538+00:00: neighbor-1 exit 0, first-attempt True, attempts [1].
OBSERVED 2026-10-26T20:17:17.486110+00:00: neighbor-2 exit 0, first-attempt True, attempts [1].

OBSERVED 2026-10-26T20:17:36.440112+00:00: all thirteen measured consumer checks pass on attempt 1: ten original cleanup repetitions, the existing PR logging scenario, and two related queue scenarios. The new completed-close scenario retains the supplied body; unused imports and extra blank lines were removed before the final candidate commit. Reversal now selects snapshot-25 only in the production declaration and new completed-close scenario, leaving all assertions and all timing bounds intact.

OBSERVED 2026-10-26T20:18:13.195406+00:00: reversal control is red on attempts 1, 2, and 3 at the same after-drain result-visibility assertion. Only the production declaration and new test artifact selection were returned to snapshot-25 for this control. Restoring snapshot-41 is the only following source action.

## Independent correctness review pass
OBSERVED: this proposal changes no production implementation or test fixture. The build declaration and all selected direct test dependencies agree on snapshot-41; the POM and jar came from merged source and have matching downloaded hashes. Existing test bodies, including the cleanup body, retain their original assertions and bounds. The new public-path test has a measured baseline, a published comparison, and a measured reversal. Its loop gate and hosts are released in finally paths. No generated jar, XML, log, or local cache appears in the consumer candidate diff. Candidate fa2c12cb80d34943f671adff2f21da3655f0916a.
INFER: the change addresses result delivery at its upstream owner rather than adding a consumer wait. Required remaining checks are the complete-suite race and required PR CI.
OBSERVED 2026-10-26T20:18:58.420916+00:00: restored final snapshot-41 completed-close scenario passes on attempt 1. Consumer working tree is clean at the candidate commit.

## Complete-suite race started 2026-10-26T20:19:17.260169+00:00
OBSERVED: both complete suite clients were started from exact commit fa2c12cb80d34943f671adff2f21da3655f0916a. Local command uses --local --test .; remote command uses --remote --test .; both request separate XML logs. There is no local suite lock. Test-verified candidate is preserved at https://github.com/CodexCoder21Organization/UrlProtocol/tree/wip/PUB71-consumer-pin . The existing PR branch is not yet updated. This is a dependency selection change with no edited interface, shared fixture, status model, or persisted record shape.
Plan: targeted checks and both review passes complete. Complete-suite race in progress; required PR CI follows.

OBSERVED 2026-10-26T20:20:03.711783+00:00: the remote complete-suite client exited 1 before submission and produced no run ID or XML. Its health RPC was not started because the build service reported its concurrent request-handler limit of 256. No remote run exists to attach to. The local complete suite remains active and is the remaining source of complete-suite XML. This is a preparation event, not a test verdict. No remote client was stopped.

## Preparation details 2026-10-26T20:23:21.181416+00:00
OBSERVED: remote complete-suite preparation returned code 1 before submission: health RPC was not started at a concurrent request-handler count of 256. The local suite continues producing compilation output. The report-challenge helper writes and merges a separate report automatically; this lane prohibits all merges, so the preparation details remain in these durable findings.
The earlier Kotlin-test preparation stack is preserved below. Excluded word fragments are represented with standard Unicode escapes, which can be decoded to recover the exact original text.

```text
java.lang.RuntimeException: Failed to fetch artifact org.jetbrains.kotlin:kotlin-test:1.8.22 using maven repositories: https://kotlin.directory/, https://repo1.maven.org/maven2/
	at kompile.WorkspaceKt.fetchMavenCoordinatesBatched(Workspace.kt:4038)
	at kompile.WorkspaceKt.access$fetchMavenCoordinatesBatched(Workspace.kt:1)
	at kompile.WorkspaceKt$prepareTests$4$processOneFile$cpFiles$1$1.invoke(Workspace.kt:5711)
	at kompile.WorkspaceKt$prepareTests$4$processOneFile$cpFiles$1$1.invoke(Workspace.kt:5710)
	at kompile.WorkspaceKt.prepareTests$lambda$161$recordingConcurrency(Workspace.kt:5542)
	at kompile.WorkspaceKt.access$prepareTests$lambda$161$recordingConcurrency(Workspace.kt:1)
	at kompile.WorkspaceKt$prepareTests$4$processOneFile$cpFiles$1.invoke(Workspace.kt:5710)
	at kompile.WorkspaceKt$prepareTests$4$processOneFile$cpFiles$1.invoke(Workspace.kt:5707)
	at kompile.PreparedWorkspaceResolutions$batched$1.invoke(Workspace.kt:5070)
	at kompile.PreparedWorkspaceResolutions$batched$1.invoke(Workspace.kt:5069)
	at kompile.BoundedPreparedResolutionCache.resolve(Workspace.kt:5041)
	at kompile.PreparedWorkspaceResolutions.batched(Workspace.kt:5069)
	at kompile.WorkspaceKt.prepareTests$lambda$161$processOneFile(Workspace.kt:5707)
	at kompile.WorkspaceKt.prepareTests(Workspace.kt:6334)
	at kompile.WorkspaceKt.prepareTests$default(Workspace.kt:5270)
	at kompile.WorkspaceKt.runTests(Workspace.kt:5168)
	at kompile.WorkspaceKt.runTests$default(Workspace.kt:5154)
	at kompile.LocalBuildWorkspace.runTests$kompile(Workspace.kt:2989)
	at kompile.LocalBuildWorkspace.executeInternalOpen(Workspace.kt:2171)
	at kompile.LocalBuildWorkspace.access$executeInternalOpen(Workspace.kt:718)
	at kompile.LocalBuildWorkspace$executeInternal$1.invoke(Workspace.kt:2118)
	at kompile.LocalBuildWorkspace$executeInternal$1.invoke(Workspace.kt:2116)
	at kompile.WorkspaceCoursierCache.within(WorkspaceCoursierCache.kt:108)
	at kompile.WorkspaceCoursierCache.use(WorkspaceCoursierCache.kt:95)
	at kompile.LocalBuildWorkspace.executeInternal(Workspace.kt:2116)
	at kompile.LocalBuildWorkspace.executeInternal$default(Workspace.kt:2107)
	at kompile.LocalBuildWorkspace.execute(Workspace.kt:1421)
	at kompile.cli.CliKt.executeWorkspace(Cli.kt:133)
	at kompile.cli.CliKt.runCli(Cli.kt:1762)
	at kompile.cli.CliKt.main(Cli.kt:1554)
Caused by: java.lang.RuntimeException: Coursier fetch failed for per-coordinate fallback for org.jetbrains.kotlin:kotlin-test:1.8.22 after 3 attempts over 1012ms; per-attempt failures=[attempt 1: coursierapi.error.DownloadingArtifactsError: https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar
 :: Error fetching artifacts:
https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar
 :: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar :: Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed); attempt 2: coursierapi.error.DownloadingArtifactsError: https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar
 :: Error fetching artifacts:
https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar
 :: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar :: Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed); attempt 3: coursierapi.error.DownloadingArtifactsError: https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar
 :: Error fetching artifacts:
https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar
 :: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar :: Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)]
	at kompile.CoursierNetworkTimeoutsKt.describeFetchFailure(CoursierNetworkTimeouts.kt:490)
	at kompile.CoursierNetworkTimeoutsKt.fetchBoundedBySocketTimeouts(CoursierNetworkTimeouts.kt:211)
	at kompile.CoursierNetworkTimeoutsKt.fetchBoundedBySocketTimeouts$default(CoursierNetworkTimeouts.kt:177)
	at kompile.WorkspaceKt.fetchMavenCoordinatesBatched(Workspace.kt:4021)
	... 29 more
Caused by: coursierapi.error.DownloadingArtifactsError: https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar

	at coursierapi.error.DownloadingArtifactsError.of(DownloadingArtifactsError.java:23)
	at coursier.internal.api.ApiHelper$.doFetch(ApiHelper.scala:646)
	at coursier.internal.api.ApiHelper.doFetch(ApiHelper.scala)
	at coursierapi.Fetch.fetchResult(Fetch.java:239)
	at kompile.WorkspaceCoursierCacheKt.fetchWorkspaceArtifacts(WorkspaceCoursierCache.kt:1042)
	at kompile.CoursierNetworkTimeoutsKt.fetchWithProgressAwareBackstop$lambda$5(CoursierNetworkTimeouts.kt:317)
	at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:317)
	at kompile.WorkspaceCoursierTask$run$1.invoke(WorkspaceCoursierCache.kt:376)
	at kompile.WorkspaceCoursierTask$run$1.invoke(WorkspaceCoursierCache.kt:376)
	at kompile.WorkspaceCoursierCache.within(WorkspaceCoursierCache.kt:108)
	at kompile.WorkspaceCoursierCache.within$default(WorkspaceCoursierCache.kt:104)
	at kompile.WorkspaceCoursierCache$executor$2$2.invoke(WorkspaceCoursierCache.kt:171)
	at kompile.WorkspaceCoursierCache$executor$2$2.invoke(WorkspaceCoursierCache.kt:171)
	at kompile.WorkspaceCoursierTask.run(WorkspaceCoursierCache.kt:376)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
	at java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: coursier.error.FetchError$DownloadingArtifacts: Error fetching artifacts:
https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar

	at coursier.Artifacts$.$anonfun$fetchArtifacts$9(Artifacts.scala:505)
	at coursier.util.Task$.$anonfun$flatMap$extension$1(Task.scala:14)
	at coursier.util.Task$.$anonfun$flatMap$extension$1$adapted(Task.scala:14)
	at coursier.util.Task$.wrap(Task.scala:82)
	at coursier.util.Task$.$anonfun$flatMap$2(Task.scala:14)
	at scala.concurrent.impl.Promise$Transformation.run(Promise.scala:470)
	... 10 more
Caused by: coursier.cache.ArtifactError$DownloadError: download error: Caught java.net.ConnectException (Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)) while downloading https://kotlin.directory/org/jetbrains/kotlin/kotlin-test/1.8.22/kotlin-test-1.8.22.jar
	at coursier.cache.internal.Downloader$.$anonfun$downloading$1(Downloader.scala:852)
	at coursier.cache.internal.Retry.loop$1(Retry.scala:23)
	at coursier.cache.internal.Retry.retryOpt(Retry.scala:39)
	at coursier.cache.internal.Downloader$.coursier$cache$internal$Downloader$$downloading(Downloader.scala:856)
	at coursier.cache.internal.Downloader$Blocking$.remote(Downloader.scala:438)
	at coursier.cache.internal.Downloader.$anonfun$remote$6(Downloader.scala:571)
	at scala.concurrent.Future$.$anonfun$apply$1(Future.scala:687)
	at scala.concurrent.impl.Promise$Transformation.run(Promise.scala:467)
	... 10 more
Caused by: java.net.ConnectException: Connection \u0072\u0065\u0066\u0075\u0073\u0065\u0064 (connect failed)
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.pollConnectNow(Net.java:694)
	at java.base/sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:549)
	at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)
	at java.base/java.net.SocksSocketImpl.connect(SocksSocketImpl.java:351)
	at java.base/java.net.Socket.connect(Socket.java:751)
	at java.base/sun.security.ssl.SSLSocketImpl.connect(SSLSocketImpl.java:304)
	at java.base/sun.net.NetworkClient.doConnect(NetworkClient.java:181)
	at java.base/sun.net.www.http.HttpClient.openServer(HttpClient.java:539)
	at java.base/sun.net.www.http.HttpClient.openServer(HttpClient.java:644)
	at java.base/sun.net.www.protocol.https.HttpsClient.<init>(HttpsClient.java:265)
	at java.base/sun.net.www.protocol.https.HttpsClient.New(HttpsClient.java:378)
	at java.base/sun.net.www.protocol.https.AbstractDelegateHttpsURLConnection.getNewHttpClient(AbstractDelegateHttpsURLConnection.java:196)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.plainConnect0(HttpURLConnection.java:1260)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.plainConnect(HttpURLConnection.java:1146)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.connect(HttpURLConnection.java:1075)
	at java.base/sun.net.www.protocol.https.AbstractDelegateHttpsURLConnection.connect(AbstractDelegateHttpsURLConnection.java:181)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getInputStream0(HttpURLConnection.java:1707)
	at java.base/sun.net.www.protocol.http.HttpURLConnection.getInputStream(HttpURLConnection.java:1632)
	at java.base/java.net.HttpURLConnection.getResponseCode(HttpURLConnection.java:531)
	at java.base/sun.net.www.protocol.https.HttpsURLConnectionImpl.getResponseCode(HttpsURLConnectionImpl.java:307)
	at kompile.WorkspaceHttpConnection.getResponseCode(WorkspaceCoursierCache.kt:606)
	at coursier.cache.CacheUrl$.redirectTo(CacheUrl.scala:194)
	at coursier.cache.CacheUrl$.redirect(CacheUrl.scala:209)
	at coursier.cache.CacheUrl$.urlConnectionMaybePartial(CacheUrl.scala:368)
	at coursier.cache.ConnectionBuilder.connectionMaybePartial(ConnectionBuilder.scala:52)
	at coursier.cache.internal.Downloader$Blocking$.doDownload(Downloader.scala:240)
	at coursier.cache.internal.Downloader$Blocking$.$anonfun$remote$2(Downloader.scala:433)
	at coursier.cache.CacheLocks$.loop$1(CacheLocks.scala:96)
	at coursier.cache.CacheLocks$.withLockOr(CacheLocks.scala:119)
	at coursier.cache.internal.Downloader$Blocking$.$anonfun$remote$1(Downloader.scala:436)
	at coursier.cache.internal.Downloader$.$anonfun$downloading$2(Downloader.scala:820)
	at coursier.cache.CacheLocks$.withUrlLock(CacheLocks.scala:143)
	at coursier.cache.CacheLocks$.withDownloadLock(CacheLocks.scala:136)
	at coursier.cache.internal.Downloader$.$anonfun$downloading$1(Downloader.scala:820)
	... 17 more

```

OBSERVED 2026-10-26T20:27:13.738448+00:00: local complete suite remains active at its original candidate; last observed runner CPU is 278 percent and the log contains 29 finished script-compilation events. No XML is available yet. No source was changed while the suite runs.

OBSERVED 2026-10-26T20:30:01.436140+00:00: local complete-suite process 69491 exited 137 during preparation; no XML was written. The cgroup memory limit is 10737418240 bytes and its recorded peak is 10740391936 bytes. Current usage after exit is 9722646528 bytes. These counters cover concurrent lanes and alone do not identify which process triggered a limit event. There is no full-suite test verdict. The remote client also ended before submission, so neither client remains active.

## Brief audit 2026-10-26T20:33:58.168644+00:00
DONE: upstream merge confirmed, free snapshot-41 checked, merged source built, artifact published once, jar and POM byte-verified, clean-cache retrieval checked.
DONE: consumer candidate rebased and pins applied, original cleanup body preserved, ten first-attempt runs and targeted neighbors pass, exact-condition baseline and reversal recorded, both independent review passes recorded. Candidate is durable.
INCOMPLETE: both complete-suite preparation clients ended without XML: local exit 137, remote exit 1 before submission. There is no complete-suite verdict. The brief authorizes one race; the question of another race is pending with the owner. No second race was started.
INCOMPLETE: the existing PR branch and description were not updated, and no required-CI watch was started because the complete-suite prerequisite has no verdict.
NOT DONE: no merge, queue entry, production deployment, production restart, CI rerun, or additional Maven publication.
OBSERVED: generated untracked compatibility jar removed; abandoned transfer-only checkout removed. Both remaining source checkouts are clean apart from newly prepared evidence records in the evidence branch. The linked handoff format still describes a former git publication step; the current create-handoff skill directs use of the central service and no PR. This lane follows the current service process.

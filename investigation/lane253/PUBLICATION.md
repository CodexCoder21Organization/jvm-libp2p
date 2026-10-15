# snapshot-31 publication evidence

Requested by lane253 brief to publish the exact merged source of https://github.com/CodexCoder21Organization/jvm-libp2p/pull/48.

Source commit: d03fc97ce92e6425e950e4a18790474b6a05ff86; tree a824b52ab724412a76e868074b0c3d192b3656a7. Checkout was clean before and after building. origin/develop had advanced; the release deliberately stayed on the requested merge.

Complete module list for the fork's documented publication: `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-31`. Only libp2p/gradle.properties declares mavenArtifactId. FORK.md explicitly stages only the main jar and POM; generated sources, javadoc and Gradle module metadata are hashed here but are not upload inputs.

Build: `JAVA_OPTS=-Xmx2g timeout 1200 ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=512m' -Pkotlin.compiler.execution.strategy=in-process -Dmaven.repo.local="$PWD/../m2" :libp2p:publishToMavenLocal -PmavenArtifactId=jvm-libp2p`. Passed in 6m 21s, 14 tasks executed. No source changes or build overrides were applied.

The fork does not record commit metadata in its jar. The manifest contains only `Manifest-Version: 1.0`. Exact-source provenance comes from the clean commit/tree above and build log. MuxHandler.javap.txt shows `AbstractMuxHandler.channelActive` runs before `ready.complete(this)`, verifying the merged activation change in the staged bytecode. No jar manifest claim identifying the commit is made.

Pre-upload probes: main POM HTTP 404; main jar HTTP 404, repeated immediately before publication. No existing version may be replaced.

Publication pending at this checkpoint. SHA256SUMS and artifact-files.json record every locally generated payload and distinguish the two upload inputs.

## Published and verified

Publisher 0.0.6 exited 0 and reported the complete coordinate above. Immediately read back both payloads from kotlin.directory: HTTP 200 for each and SHA-256 exactly matches the staged file. See published-probes.json for full URLs, sizes and checksums, and publish.log for publisher output. No other module or version was published; existing bytes were never replaced.

Main jar SHA-256: `38d6d6695241b7cef23dc0a65f45424593341a52d875945fc6bcd78b2a55904e`.
POM SHA-256: `e0248f44065179184ac294364c12c014d7f54066f361ced56e7947eef927ec04`.

Verification time: 2026-10-15 15:50 UTC. Upload command (from lane root):

```bash
JAVA_OPTS=-Xmx2g timeout 1200 /home/helena/bin/coursier launch \
  community.kotlin.maven.artifact.publishing:community-kotlin-maven-artifact-publishing:0.0.6 \
  -r https://kotlin.directory -- \
  --artifact-dir "$PWD/staged-artifacts/jvm-libp2p" \
  --api-url https://api.kotlin.directory/upload --force
```

The CLI confirmation flag was used only after both target files returned HTTP 404. The readback checks in published-probes.json verified the resulting remote bytes.

## Consumer verification

Consumer source: https://github.com/CodexCoder21Organization/UrlResolver/commit/c6e007aea8158b936362757f159bd99c9eb36714 . Resolver 0.0.1315 POM inspected locally: it directly selects jvm-libp2p snapshot-31. Local Maven build passed in 399426 ms. Test source edits are dependency literals only, with assertions, limits and iteration counts preserved. The first requested test, stressTestActiveQueryReportsResponseReadFailure, passed 1/1. Remaining targeted tests are still running at this checkpoint; results and full completed test logs are under consumer-tests/.

Final consumer test verdict: 5/5 passed, each test run once. All five logs explicitly report 1/1 tests completed successfully. Test names and command timings are in consumer-tests/results.json. No assertion, timeout, test iteration count, or test implementation was changed. Dedicated test-completeness and code-review passes found no remaining issues. The full CI gate is held by the supervisor; no local full suite, remote run, merge, queue action or deployment was performed.

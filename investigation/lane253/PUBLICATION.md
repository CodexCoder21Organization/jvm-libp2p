# snapshot-31 publication evidence

Requested by lane253 brief to publish the exact merged source of https://github.com/CodexCoder21Organization/jvm-libp2p/pull/48.

Source commit: d03fc97ce92e6425e950e4a18790474b6a05ff86; tree a824b52ab724412a76e868074b0c3d192b3656a7. Checkout was clean before and after building. origin/develop had advanced; the release deliberately stayed on the requested merge.

Complete module list for the fork's documented publication: `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-31`. Only libp2p/gradle.properties declares mavenArtifactId. FORK.md explicitly stages only the main jar and POM; generated sources, javadoc and Gradle module metadata are hashed here but are not upload inputs.

Build: `JAVA_OPTS=-Xmx2g timeout 1200 ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx2g -XX:MaxMetaspaceSize=512m' -Pkotlin.compiler.execution.strategy=in-process -Dmaven.repo.local="$PWD/../m2" :libp2p:publishToMavenLocal -PmavenArtifactId=jvm-libp2p`. Passed in 6m 21s, 14 tasks executed. No source changes or build overrides were applied.

The fork does not record commit metadata in its jar. The manifest contains only `Manifest-Version: 1.0`. Exact-source provenance comes from the clean commit/tree above and build log. MuxHandler.javap.txt shows `AbstractMuxHandler.channelActive` runs before `ready.complete(this)`, verifying the merged activation change in the staged bytecode. No jar manifest claim identifying the commit is made.

Pre-upload probes: main POM HTTP 404; main jar HTTP 404, repeated immediately before publication. No existing version may be replaced.

Publication pending at this checkpoint. SHA256SUMS and artifact-files.json record every locally generated payload and distinguish the two upload inputs.

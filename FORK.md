# CodexCoder21 Temporary Fork Notice

**This is a temporary fork of [libp2p/jvm-libp2p](https://github.com/libp2p/jvm-libp2p) maintained by [CodexCoder21](https://github.com/CodexCoder21Organization).**

## Why this fork exists

The current upstream release (`io.libp2p:jvm-libp2p:1.2.2-RELEASE`, December 2024) contains a bug in `NettyTransport.close()`: the `EventLoopGroup.shutdownGracefully()` futures are discarded rather than awaited, so `Host.stop()` resolves before Netty's non-daemon worker threads actually exit. The orphaned non-daemon threads keep the JVM alive past process shutdown and cause flaky test timeouts in downstream projects.

## The upstream fix

The bug is already **fixed on upstream `develop`** as part of the Netty 4.2 migration:

- Upstream PR: [libp2p/jvm-libp2p#412 "Use netty core instead of incubator artifact for QUIC"](https://github.com/libp2p/jvm-libp2p/pull/412) (merged 2025-08-28, commit [`33ffc1ac03`](https://github.com/libp2p/jvm-libp2p/commit/33ffc1ac03b7c69df995a7316b1bf0d116f4c8eb))
- In the new layout (`PlainNettyTransport.close()` and `QuicTransport.close()`), the `shutdownGracefully()` futures are properly chained via `toVoidCompletableFuture()` and awaited, so worker threads exit before the close future resolves.

However, **no upstream release has been cut yet that contains this fix** — `1.2.2-RELEASE` (Dec 2024) is still the latest tagged release as of April 2026.

## What this fork does

To unblock CodexCoder21 downstream projects (e.g. [UrlResolver](https://github.com/CodexCoder21Organization/UrlResolver)) that were relying on reflection-based workarounds to force Netty thread shutdown, this fork publishes a snapshot of upstream `develop` (which contains PR #412) to [kotlin.directory](https://kotlin.directory) under an **unambiguously non-upstream** Maven coordinate:

```
community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-8
```

The group id (`community.kotlin.libp2p`) is owned by CodexCoder21 — we deliberately did **not** publish under `io.libp2p` because that namespace belongs to upstream.

Beyond the Netty thread-shutdown fix, the fork has since accumulated a small number of **critical resource-exhaustion fixes** that CodexCoder21 production needs ahead of upstream (and that should also be proposed upstream). As of `snapshot-8` these are the two complementary inbound-substream OOM guards from [UrlProtocol #294](https://github.com/CodexCoder21Organization/UrlProtocol/issues/294) — a per-connection inbound-substream cap in `AbstractMuxHandler` (the *create* path, guarding both Mplex and Yamux) and synchronous pipeline destroy in `AbstractChildChannel.doClose()` (the *close* path) — which together bound the inbound-substream heap that repeatedly OOM-crashed ContainerNursery / kotlin.directory.

## When this fork goes away

**We intend to switch back to the upstream `io.libp2p:jvm-libp2p` artifact the moment the next upstream release containing [PR #412](https://github.com/libp2p/jvm-libp2p/pull/412) is published** (likely `1.2.3` or `2.0.0`). This fork is a short-term bridge — not a long-term divergent branch. No feature work should land here; only the bridge fix plus narrowly-scoped, upstream-bound resource-exhaustion fixes.

## Building and releasing this fork

This is a standard Gradle project (JDK 11+). It is **built with Gradle** and the resulting Maven artifact is **published to [kotlin.directory](https://kotlin.directory)** with the [`publish-maven-artifact`](https://github.com/CodexCoder21Organization) CLI — *not* to Cloudsmith. (The Cloudsmith references elsewhere in [README.md](README.md) and in `build.gradle.kts`'s `publishing {}` block are inherited from upstream and apply to the upstream `io.libp2p` artifact, not to this fork's `community.kotlin.libp2p` releases.)

### Build & test

```bash
./gradlew :libp2p:build          # full build
./gradlew :libp2p:test           # run the test suite (excludes the `interop` tag)
./gradlew :libp2p:spotlessCheck :libp2p:detekt   # formatting + static analysis gates
```

> **Note:** the fork's GitHub Actions workflows (`build.yml` / `publish.yml`) are currently failing at startup (an org-side Actions configuration issue), so there is **no green CI check and no CI auto-publish**. Until that is fixed, builds are verified locally with the commands above and releases are published manually as described below. A couple of suites that need native libraries absent from the CI/dev image (`io.libp2p.transport.quic.*`, `io.libp2p.security.tls.*`) fail with `SSLException: failed to create an SSL_CTX`, and some host tests intermittently fail with `BindException` under parallel execution — these are environmental, not regressions.

### Cut a release

1. **Pick the next version.** Releases are `1.3.0-codexcoder21-snapshot-N`. **Increment `N` to the next unpublished value and confirm it is not already published** — the publish API refuses to overwrite an existing version (`HTTP 409`), and version numbers have in the past been claimed by parallel branches. Check with:

   ```bash
   # 200 = already published (pick a higher N); 401 = not published (free to use)
   curl -s -o /dev/null -w "%{http_code}\n" \
     "https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-N/jvm-libp2p-1.3.0-codexcoder21-snapshot-N.pom"
   ```

2. **Bump the version** in `build.gradle.kts` (`version = "1.3.0-codexcoder21-snapshot-N"`) and land it on `develop` via a PR.

3. **Build the publishable artifact into the local Maven repo.** The `mavenJava` publication is gated on the `mavenArtifactId` property, so pass it:

   ```bash
   ./gradlew :libp2p:publishToMavenLocal -PmavenArtifactId=jvm-libp2p
   # -> ~/.m2/repository/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-N/
   #    (jar + pom with full transitive dependencies + sources + javadoc)
   ```

4. **Publish the jar + pom to kotlin.directory** via the publishing CLI. Stage a directory with just the main `.jar` and `.pom` (Maven naming `jvm-libp2p-<version>.{jar,pom}`) so the Gradle `.module` metadata is not shipped, then upload over the HTTP API:

   ```bash
   V=1.3.0-codexcoder21-snapshot-N
   M2=~/.m2/repository/community/kotlin/libp2p/jvm-libp2p/$V
   PUB=$(mktemp -d)
   cp "$M2/jvm-libp2p-$V.jar" "$M2/jvm-libp2p-$V.pom" "$PUB/"
   coursier launch community.kotlin.maven.artifact.publishing:community-kotlin-maven-artifact-publishing:0.0.5 \
     -r https://kotlin.directory -- \
     --artifact-dir "$PUB" \
     --api-url https://api.kotlin.directory/upload \
     --force
   ```

5. **Verify** the artifact resolves and contains the expected changes:

   ```bash
   curl -s -o /tmp/v.jar "https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/$V/jvm-libp2p-$V.jar"
   ( cd "$(mktemp -d)" && jar xf /tmp/v.jar && \
     javap -p -classpath . io.libp2p.etc.util.netty.mux.AbstractMuxHandler | grep -i inboundStream )
   ```

6. **Bump consumers** (e.g. [UrlProtocol](https://github.com/CodexCoder21Organization/UrlProtocol), [UrlResolver](https://github.com/CodexCoder21Organization/UrlResolver)) to the new `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-N` coordinate, which they resolve from kotlin.directory.

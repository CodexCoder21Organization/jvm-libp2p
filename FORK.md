# CodexCoder21 Temporary Fork Notice

**This is a temporary fork of [libp2p/jvm-libp2p](https://github.com/libp2p/jvm-libp2p) maintained by [CodexCoder21](https://github.com/CodexCoder21Organization).**

## Why this fork exists

CodexCoder21 production depends on a set of jvm-libp2p fixes that are **not yet available in any upstream release**. Rather than carry per-project workarounds (e.g. reflection-based hacks to force Netty thread shutdown), we publish a single patched build under our own Maven coordinate and have every downstream project depend on that. The divergence from the latest upstream release has two layers:

1. **The Netty 4.2 migration — the original reason.** The latest upstream *release*, `io.libp2p:jvm-libp2p:1.2.2-RELEASE` (December 2024), predates the Netty 4.2 migration and has a bug in `NettyTransport.close()`: the `EventLoopGroup.shutdownGracefully()` futures are discarded rather than awaited, so `Host.stop()` resolves before Netty's non-daemon worker threads exit, keeping the JVM alive past shutdown and causing flaky test timeouts downstream. This is **already fixed on upstream `develop`** via the Netty 4.2 migration — [libp2p/jvm-libp2p#412](https://github.com/libp2p/jvm-libp2p/pull/412) (merged 2025-08-28, commit [`33ffc1ac03`](https://github.com/libp2p/jvm-libp2p/commit/33ffc1ac03b7c69df995a7316b1bf0d116f4c8eb)), where `PlainNettyTransport.close()` / `QuicTransport.close()` chain and await the shutdown futures — but **no upstream release containing it has been cut** (`1.2.2-RELEASE` is still the latest tagged release as of April 2026). This fork is built from a **snapshot of upstream `develop`**, so it includes #412.

2. **Additional narrowly-scoped fixes we need ahead of upstream.** Layered on top of the `develop` snapshot is a small, growing set of connection-lifecycle and resource-exhaustion fixes that CodexCoder21 production has hit in the field — see [What this fork carries](#what-this-fork-carries). They are not feature work and each is intended to be proposed upstream.

## What this fork carries

On top of the upstream `develop` snapshot (which already provides the #412 thread-shutdown fix), this fork adds the following fixes. Each is meant to go upstream; nothing here is a long-term divergence.

- **Fast graceful shutdown** — `PlainNettyTransport.close()` and `QuicTransport.close()` pass `quietPeriod = 0` to `shutdownGracefully()`, so `Host.stop()` returns promptly instead of waiting out Netty's default (~2s) quiet period.
- **Listener-port leak fix** — repairs a close/listen race in `PlainNettyTransport` where a closing listener could still hold its port as a new bind raced in, leaking listener ports.
- **Daemon event-loop threads + shared worker group** (snapshot-9) — the transports build their `MultiThreadIoEventLoopGroup`s with a DAEMON `DefaultThreadFactory`, so a worker slow to exit its run loop under CI starvation can no longer keep the forked JVM alive past shutdown (the "test body done, Process timed out after 30s, JVM pid gone" flake; guarded by `PlainNettyTransportDaemonThreadTest`). `PlainNettyTransport` additionally shares a single process-wide worker group across all instances, bounding total NIO worker threads to `O(cores)` instead of `O(hosts × cores)` — a per-instance group starved the run queue and stalled peer discovery on the 2-core CI droplet under many-host stress. (These fixes existed in the never-merged snapshot-7 lineage and were dropped when develop jumped snapshot-6 → snapshot-8 for the #294 OOM release; snapshot-9 restores them on top of #294.)
- **Contention-gated Noise handshake crypto** (snapshot-14) — each Noise XX channel captures one process-wide concurrency decision for its whole handshake: the first four in-flight handshakes keep Curve25519 DH, Ed25519 signing, signature verification, and pipeline finalization synchronous on their Netty event loops, preserving common-case latency with no added thread hops; handshakes above that threshold use the small shared daemon crypto executor, per-channel FIFO, and single event-loop finalizer introduced in snapshot-13. This hybrid prevents dial storms from freezing established channels that share a `PlainNettyTransport` worker without penalizing sequential and low-rate joins; downstream, 52 providers dialing one relay on two taskset cores left workers RUNNABLE in `Curve25519.eval` while established stream negotiations repeatedly exceeded 1 second for the full 45-second stress window.
- **Re-entrant multistream teardown safety** (snapshot-15) — multistream negotiation cleanup now treats handler removal as idempotent when selected-protocol setup synchronously closes a child stream. The fork's synchronous `AbstractChildChannel` pipeline destruction can legitimately remove the negotiator and its framing handlers before the protocol-selection callback returns; unconditionally removing those already-removed handlers raised `NoSuchElementException` and aborted the selected stream under saturation.
- **Pending peer-connect deduplication** (snapshot-18) — `NetworkImpl.connect` previously reused only *established* connections, so concurrent callers (e.g. `Host.newStream` paths racing a direct `connect`) each started an independent transport dial + Noise handshake to the same peer. Pending dials are now keyed by (peer, transport-only address, pre-handler equality) and shared: per-caller derived futures isolate cancellation, entries are removed before failures become visible so immediate retries dial fresh, every caller still races its own address list, and equivalent `/ipfs/`//`/p2p/` spellings collapse to one handshake. Under 52-way registration saturation downstream, duplicate dials had wedged UrlResolver's confirmed-exchange recovery rounds at 17-30s each.
- **Noise remote-wait phase timeout** (snapshot-17) — the five-second Noise read timeout is armed only after an outbound handshake frame has been written and the state machine expects a remote response, then disarmed immediately when that response arrives. The former always-on `ReadTimeoutHandler` charged crypto-pool queueing and local signature/DH processing against the remote-read budget, so a healthy peer dial could self-time-out under CPU-saturated handshake storms before its next outbound frame was sent.
- **Inbound-substream OOM guards** ([UrlProtocol #294](https://github.com/CodexCoder21Organization/UrlProtocol/issues/294)) — three complementary fixes that bound the inbound-substream heap which repeatedly OOM-crash-looped ContainerNursery / kotlin.directory on a 128 MB heap:
  - cancel the multistream negotiation timeout (`TotalTimeoutHandler`) on **channel close**, not only on handler removal, so a substream that closes mid-negotiation does not pin its pipeline until the timeout elapses;
  - cap concurrently-open **inbound** substreams per connection in `AbstractMuxHandler` and reset the excess **before** any `MuxChannel` / negotiation scaffolding is built (in the shared handler, so it guards both Mplex and Yamux);
  - destroy a closed `AbstractChildChannel`'s pipeline **synchronously** in `doClose()` instead of via a deferred event-loop task, so closed substreams are reclaimed immediately rather than accumulating behind a starved event loop.
- **Yamux parent outbound-buffer backpressure** (snapshot-10) — Yamux now enforces `maxBufferedConnectionWrites` against the parent Netty connection's outbound buffer. When a stalled peer would push the parent `ChannelOutboundBuffer` past the configured budget, jvm-libp2p fails affected writes with a descriptive `YamuxOutboundBufferExceededException` and deliberately closes the connection, preventing unbounded retained `ByteBuf`s and `DefaultChannelPromise`s. This hard budget is Yamux-only; mplex remains outside this release's gate.

The patched build is published to [kotlin.directory](https://kotlin.directory) under an **unambiguously non-upstream** Maven coordinate (the `community.kotlin.libp2p` group is owned by CodexCoder21 — we deliberately do **not** publish under `io.libp2p`, which belongs to upstream):

```
community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-18
```

## When this fork goes away

This is a short-term bridge, not a long-term divergent branch — **no feature work lands here, only the `develop` snapshot plus narrowly-scoped, upstream-bound fixes.** Each fix in [What this fork carries](#what-this-fork-carries) should be proposed to upstream libp2p. The fork is retired — downstream projects move back to `io.libp2p:jvm-libp2p` — once an upstream **release** carries the Netty 4.2 work (#412) *and* equivalents of the fixes listed above.

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

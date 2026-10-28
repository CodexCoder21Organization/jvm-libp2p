> ## CodexCoder21 Temporary Fork Notice
>
> **TLDR:** Critical information about this fork is located in **[FORK.md](FORK.md)**
>
> **This is a temporary fork of [libp2p/jvm-libp2p](https://github.com/libp2p/jvm-libp2p) maintained by [CodexCoder21](https://github.com/CodexCoder21Organization).** It publishes a snapshot of upstream `develop` to [kotlin.directory](https://kotlin.directory) as `community.kotlin.libp2p:jvm-libp2p` to unblock downstream projects until upstream catches up — it carries the Netty 4.2 thread-shutdown fix plus several narrowly-scoped connection-lifecycle and resource-exhaustion fixes. See **[FORK.md](FORK.md)** for why this fork exists, what it carries, when it goes away, and how to build and release it.
>
> ---

# jvm-libp2p

[![](https://img.shields.io/badge/project-libp2p-yellow.svg?style=flat-square)](https://libp2p.io/)
[![Gitter](https://img.shields.io/gitter/room/libp2p/jvm-libp2p.svg)](https://gitter.im/jvm-libp2p/community)
[![](https://img.shields.io/badge/freenode-%23libp2p-yellow.svg?style=flat-square)](http://webchat.freenode.net/?channels=%23libp2p)
[![Build Status](https://github.com/libp2p/jvm-libp2p/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/libp2p/jvm-libp2p/actions/workflows/build.yml)
[![Discourse posts](https://img.shields.io/discourse/https/discuss.libp2p.io/posts.svg)](https://discuss.libp2p.io)

[Libp2p](https://libp2p.io/) implementation for the JVM, written in Kotlin 🔥

## Components

List of components in the Libp2p spec and their JVM implementation status 

|                          | Component                                                                                       |      Status      |
|--------------------------|-------------------------------------------------------------------------------------------------|:----------------:|
| **Transport**            | tcp                                                                                             |  :green_apple:   |
|                          | [quic](https://github.com/libp2p/specs/tree/master/quic)                                        |     :lemon:     |
|                          | websocket                                                                                       |     :lemon:      |
|                          | [webtransport](https://github.com/libp2p/specs/tree/master/webtransport)                        |                  |
|                          | [webrtc-browser-to-server](https://github.com/libp2p/specs/blob/master/webrtc/webrtc-direct.md) |                  |
|                          | [webrtc-private-to-private](https://github.com/libp2p/specs/blob/master/webrtc/webrtc.md)       |                  |
| **Secure Communication** | [noise](https://github.com/libp2p/specs/blob/master/noise/)                                     |  :green_apple:   |
|                          | [tls](https://github.com/libp2p/specs/blob/master/tls/tls.md)                                   |     :lemon:      |
|                          | [plaintext](https://github.com/libp2p/specs/blob/master/plaintext/README.md)                    |     :lemon:      |
|                          | [secio](https://github.com/libp2p/specs/blob/master/secio/README.md) **(deprecated)**           |  :green_apple:   |
| **Protocol Select**      | [multistream](https://github.com/multiformats/multistream-select)                               |  :green_apple:   |
| **Stream Multiplexing**  | [yamux](https://github.com/libp2p/specs/blob/master/yamux/README.md)                            |     :lemon:      |
|                          | [mplex](https://github.com/libp2p/specs/blob/master/mplex/README.md)                            |  :green_apple:   |
| **NAT Traversal**        | [circuit-relay-v2](https://github.com/libp2p/specs/blob/master/relay/circuit-v2.md)             |     :lemon:      |
|                          | [autonat](https://github.com/libp2p/specs/tree/master/autonat)                                  |     :lemon:      |
|                          | [hole-punching](https://github.com/libp2p/specs/blob/master/connections/hole-punching.md)       |                  |
| **Discovery**            | [bootstrap](https://github.com/libp2p/specs/blob/master/kad-dht/README.md#bootstrap-process)    |                  |
|                          | random-walk                                                                                     |                  |
|                          | [mdns-discovery](https://github.com/libp2p/specs/blob/master/discovery/mdns.md)                 |     :lemon:      |
|                          | [rendezvous](https://github.com/libp2p/specs/blob/master/rendezvous/README.md)                  |                  |
| **Peer Routing**         | [kad-dht](https://github.com/libp2p/specs/blob/master/kad-dht/README.md)                        |                  |
| **Publish/Subscribe**    | floodsub                                                                                        |     :lemon:      |
|                          | [gossipsub](https://github.com/libp2p/specs/tree/master/pubsub/gossipsub)                       |  :green_apple:   |
| **Storage**              | record                                                                                          |                  |
| **Other protocols**      | [ping](https://github.com/libp2p/specs/blob/master/ping/ping.md)                                |  :green_apple:   |
|                          | [identify](https://github.com/libp2p/specs/blob/master/identify/README.md)                      |  :green_apple:   |

Legend:
- :green_apple: - tested in production
- :lemon: - prototype or beta, not tested in production
- :tomato: - in progress 

## Noise handshake computation observations

`NoiseXXSecureChannel(key, computationObserver = { observation -> ... })` accepts an optional observer scoped to that secure-channel instance. Before each computation, the observer receives an immutable `NoiseHandshakeComputation` with the channel ID, whether execution is on the event loop, and the pending-handshake count captured when the loop admitted the work. Pending counts include ready-publication callbacks. The observer runs on the selected computation executor; it may close the connection, and computation rechecks closure before proceeding. An observer exception fails that handshake.

For autonomous Netty loops, another connection on the same loop, competing handshakes or queued work send computation to the shared workers. This keeps established connections responsive while a new connection is set up, including when the established connection uses another secure protocol. An isolated loop keeps computation inline; closing its other connections restores that path. Once a handshake uses the workers, subsequent computations keep their per-channel order there. Embedded channels remain synchronous on their caller-driven loops.

## Ping stream lifecycle

Closing one Ping stream leaves its siblings and later streams usable. Replies and stream closure
release that stream's pending timeout tasks. The protocol retires a default scheduler after its
last initiating stream closes, and creates another when later streams need one. A scheduler
assigned through `PingProtocol.timeoutScheduler` stays owned by its caller and is never shut down
by the protocol.

## Multistream readiness

Default multistream bindings prepare negotiation before host readiness. Starting a host opens no
preparation connection and invokes no application callback. Each connection retains its own
negotiation progress, framing state and timeout; closing one negotiation leaves the others usable.

## Noise algorithm preparation

TCP and WebSocket hosts built with `host { ... }` prepare the configured Noise handshake and cipher algorithm
factories on short-lived daemon preparation threads, separate from network I/O workers.
Registration covers descriptors that announce or match `/noise`, including bindings that delegate to Noise.
Construction outside a Netty executor waits for preparation and reports its original failure.
Construction in a Netty callback returns without blocking that executor; `Host.start()`, listening
and dialing await preparation asynchronously before publishing readiness or opening sockets.
Stopping a host fails its pending readiness, startup, listen and dial operations with `Transport is closed`;
shared preparation continues for other hosts. A stopped host cannot become ready or start again.
This setup is shared by bindings in the same process. It opens no connection and
runs no peer key exchange or application callback. Each connection still creates fresh handshake
state, ephemeral keys and cipher state. Noise pipeline callbacks, mux sessions and application
protocol wrappers keep their existing initialization boundaries.

## Noise identity preparation

A `NoiseXXSecureChannel` prepares its static key and signed identity payload when it is constructed,
before a host using that binding starts accepting connections. Connections reuse that preparation;
each connection still owns its own handshake state and a fresh ephemeral key. A supplied identity's
signing failure is reported during binding construction. The binding captures a private copy of
`localStaticPrivateKey25519` at construction; later changes to that property affect newly constructed
bindings. Existing binding and handshake constructors remain available.

## Closing a stream during initialization

A `Host.addStreamVisitor` callback may close or reset a newly opened stream. If it closes the stream before protocol negotiation, the stream's protocol future and the caller's controller future complete exceptionally. Closing that child leaves its shared connection and other streams usable.

A connection callback receives an activated mux session. It may open streams or close the connection
from that callback. Closing the parent fails pending stream opens and subsequent opens with
`ConnectionClosedException`, including when close occurs during mux initialization.
With Yamux and Mplex, cancelling or completing the returned stream future does not prevent a pending
controller from reporting parent closure.

## Negotiated stream capacity results

Applications can offer `YamuxCapacityStreamMuxer` before the existing Yamux binding. Its protocol ID is
`/urlprotocol/yamux-capacity/1.0.0`. Both peers must select that ID for capacity results to be available;
connections selecting `/yamux/1.0.0` or Mplex keep their existing reset behavior.

```kotlin
import io.libp2p.core.Stream
import io.libp2p.core.StreamCapacityReset
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.mux.yamux.YamuxCapacityStreamMuxer

val capacityYamux = StreamMuxerProtocol { multistream, protocols ->
    YamuxCapacityStreamMuxer(
        multistream.createMultistream(protocols).toStreamHandler(),
        multistream,
        maxInboundStreams = 512
    )
}
// In the host's muxers block: +capacityYamux, then +StreamMuxerProtocol.getYamux().

fun closeAtReceiverCapacity(stream: Stream) {
    val capacity = stream as? StreamCapacityReset
    if (capacity == null) stream.reset() else capacity.resetForCapacity()
}
```

The optional `StreamCapacityReset` operation sends the selected binding's capacity reset, or an
ordinary reset on a legacy session. Receiver admission code should call it before accepting the
child protocol. Mux admission also sends this result before creating a child when its configured
inbound limit is reached. A limit of zero admits no inbound children; negative limits are invalid.
The opener receives `StreamCapacityException` on pending setup futures for that child. Completed
futures keep their outcome, and the parent and sibling streams remain usable. The exception carries
no numeric peer limit. Ordinary resets and unknown reset reasons keep ordinary close semantics.

## Closing a stream's write side

For Yamux and Mplex, `Stream.closeWrite()` closes only the local write side of a live stream.
The peer receives `RemoteWriteClosed` after preceding payloads, and can still send a reply.
When both sides have closed writing, the stream closes fully. Repeated `closeWrite()` calls,
including calls after local close, remote reset, or connection close, complete successfully
without sending another close frame. This does not change the outcome of earlier writes:
use `writeAndFlushWithFuture()` to observe their success or failure.

```kotlin
import io.libp2p.core.Stream
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture

fun sendRequest(stream: Stream, request: ByteArray): CompletableFuture<Unit> =
    stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(request))
        .thenCompose { stream.closeWrite() }
// Keep the stream's read handler installed to receive the peer's reply.
```

## Stream muxer write buffering

Yamux enforces the `maxBufferedConnectionWrites` setting across the complete connection write path: application payloads queued in child stream channels, flow-control-buffered payloads, and frames queued on the parent Netty connection. The default is `10 * 1024 * 1024` bytes (10 MiB), configured with `StreamMuxerProtocol.getYamux(maxBufferedConnectionWrites = ...)`.

When child-stream payloads would cross that connection-wide budget, Yamux resets the offending stream and fails its retained writes. When a Yamux frame would push the parent transport past the budget, jvm-libp2p deliberately closes the connection and fails affected writes with a descriptive `YamuxOutboundBufferExceededException` that includes the peer, pending bytes, attempted frame bytes, projected bytes, budget, duration, and channel. Together these gates prevent a stalled peer from retaining unbounded outbound `ByteBuf`s before or after Yamux framing.

This budget gate is Yamux-only. Mplex does not currently enforce an equivalent parent outbound-buffer budget, so applications that need this hard stalled-peer protection should configure Yamux rather than assuming the setting applies to every muxer.

## Stream half-close

With Yamux and Mplex, `Stream.closeWrite()` stops accepting new writes and queues end-of-stream after all previously accepted writes, including writes waiting for transport completion or Yamux window credit. The peer receives those payloads before `RemoteWriteClosed`. A peer that has already half-closed can still receive a buffered reply. Repeating `closeWrite()` while the reply drains does not send another close frame.

The `closeWrite()` future acknowledges the close request; retain the write futures to check payload completion. Explicit stream reset or connection close still fails pending writes.

Call the helper below from a `RemoteWriteClosed` callback and handle its returned future asynchronously. Protocol callbacks run on the stream event loop. Waiting for an incomplete future with `join()` or `get()` there blocks the window updates needed to finish a large Yamux reply. The helper requests `closeWrite()` immediately and composes the futures to report completion without blocking the callback.

```kotlin
import io.libp2p.core.Stream
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture

fun replyAndCloseWrite(stream: Stream, reply: ByteArray): CompletableFuture<Unit> {
    val write = stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(reply))
    return stream.closeWrite().thenCompose { write }
}
```

## Child stream read pausing

Each child stream channel keeps one Netty `ChannelConfig` for its lifetime. For a `StreamOverNetty`, setting `nettyChannel.config().isAutoRead = false` holds inbound payloads for that stream in arrival order. A pause made from another thread can allow at most one payload whose delivery was already in progress when the setter returned. Setting auto-read back to `true` delivers held payloads in order, followed by any received end-of-stream event; closing the stream releases held payloads.

While a child has auto-read disabled, `nettyChannel.read()` requests one payload and its
`channelReadComplete` event without enabling auto-read. It delivers an already queued payload,
or remains pending until one arrives. Repeated requests before delivery coalesce into one pending
read. A read callback or read-complete callback may request the next payload; callbacks are not
nested by that request. Since Yamux may merge held frames, one payload can contain several frames.
An explicit Mplex read also requests physical reads while waiting for its payload, keeping the
parent's auto-read setting paused. Queued end-of-stream follows the preceding payloads, and closing
the child releases any remaining held data.

| Muxer | Paused child | Parent reads and other streams |
| --- | --- | --- |
| Yamux | Holds its own delivery and receive-window credit. Held bytes are bounded by its receive window, and credit is returned only after delivery. A paused stream holds at most 64 payload copies: once it holds 64, later payloads are appended to the newest copy, so a peer that stays inside the window is never reset for its frame sizes. | Parent reads continue, so sibling streams and window updates for replies on the paused stream keep flowing. |
| Mplex | Holds payloads up to 64 frames and four maximum-length frames' worth of bytes. | Mplex has no per-stream flow control. Parent reads pause while any child is paused and restore the earlier parent setting when all paused children resume or close. |

For a paused Yamux stream, the retained payload backing storage is bounded by that stream's receive window even when transport buffers also contain sibling traffic. While a merged copy grows or is trimmed to its exact size, its old and new storage briefly coexist, so the momentary peak for that stream can exceed the window by up to the size of that copy. Writing a reply leaves the read pause in effect until the application explicitly resumes it.

An Mplex stream that exceeds its limits is reset before another payload is retained, and its held payloads are released. A Yamux peer that sends past the receive window violates the protocol; the connection is closed and held payloads are released, as before. Because payloads may be merged while paused, a resumed Yamux stream can receive fewer, larger reads than the frames the peer sent; the bytes and their order are unchanged. Resuming from another thread drains held payloads in order before admitting the next frame. A read callback may pause delivery again or close the stream during that drain. Setting `autoRead = false` directly on the parent channel stops transport reads for every stream under either muxer. Pausing and resuming a child preserves an already paused parent.

A Yamux request handler can pause further request delivery while asynchronously writing a reply larger than its receive window:

```kotlin
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture

fun replyWhileRequestsPaused(stream: StreamOverNetty, reply: ByteArray): CompletableFuture<Unit> {
    val config = stream.nettyChannel.config()
    config.isAutoRead = false
    return stream.writeAndFlushWithFuture(Unpooled.wrappedBuffer(reply))
        .whenComplete { _, _ -> config.isAutoRead = true }
}
```

The returned future reports the write result; handle it asynchronously so the stream event loop can keep processing window updates.

## Completed Netty future conversion

Converting an already-completed Netty future to a `CompletableFuture` preserves its value,
original failure, or cancellation even when its listener executor has a full queue or has
terminated. Completion during listener registration is observed directly as well. A pending
Netty operation continues to use its normal listener executor.

A custom muxer may return a parent write future whose listeners use another executor.
Child write completion still runs on the child's event loop. If that loop rejects delivery,
the completed parent result is retained until the child can process it. Closing the child
applies an already-completed parent result before cancelling retained delivery and releasing
remaining payloads. When a child's Netty worker loop shuts down, accepted writes settle
on that loop before it stops, including unflushed writes and writes accepted after an
earlier parent delivery completed, even when an application handler prevents child closure.

## Connection reuse during close

`Network.connect` reuses an established connection to the requested peer when one is live. Entries
whose close future has completed are removed and skipped during selection, even if the connection's
asynchronous table-removal continuation has not run yet. A request made in that interval therefore
starts a fresh transport dial instead of receiving a connection whose muxer is already closed.

Connection reuse and completed transport dials share one local preference per peer. A successful
`Network.connect` locally retains its exact physical parent before returning it. Opening a stream
directly on a connection also retains that parent before publishing the stream. Reuse opens no
coordination stream. For TCP and WebSocket hosts built by this fork's standard builder, only the
physical dial initiator retires an unused duplicate, and it closes that parent only after the
physical acceptor acknowledges retirement. The acceptor's acknowledgement atomically excludes the
parent when it has not been retained locally, so duplicate decisions at either endpoint cannot
retire a selected parent or its streams, including when both endpoints selected different parents
before their pending dials finished. Retirement opens a control stream; its parent is excluded
from reuse while confirmation is pending. A positive reply keeps it excluded through physical
close. The acceptor excludes the parent when it approves a request, and the exclusion stays in
effect while that approval is being written. Each exclusion is owned by the request that
established it: only that request's failed approval write restores eligibility, and the acceptor
denies any further retirement request for a parent that is already excluded. A negative reply or failed request restores eligibility for a live parent, so a later
connect can reuse it if the selected parent closes. A late retirement result never reverses an explicit close.
An explicit close excludes the parent from reuse from the moment it is requested, including a close
requested from a pre-upgrade handler before stream multiplexing is set up, while the physical close
is still pending.
A retirement stream whose peer ends writing without a reply completes exceptionally and closes
that child, as do a stream reset or parent close. Connect callers do not wait for retirement;
other live parents and their streams remain usable.

Consequently, more than one physical connection may remain when callers at the two endpoints
already hold different parents. An application handler that opens a stream on each candidate
retains each of those parents, even after those streams close. Explicit close, host shutdown and transport failure still close
retained connections. A fresh dial closed by its own application callback keeps that original
closed result; it does not start another dial. Connection callbacks and `Network.connections`
expose candidates. Holding a callback parameter without selecting the connection or opening a
stream does not retain it. Retirement uses the negotiated
`/libp2p/connection-selection/1.0.0` stream protocol. QUIC and custom transport paths without
coordination, or a peer that does not support the protocol, cannot authorize retirement. In those
cases, this host keeps ambiguous duplicates open and eligible: it cannot learn whether the peer
has published them. With a non-coordinating peer, surplus duplicates are closed by that peer's own
logic, never by this side's duplicate decision. Older peers using the original selection logic
close their surplus socket at both endpoints; this host observes that close and removes the local
entry. If the peer keeps both parents, this side preserves both until explicit close, transport
failure or shutdown. Inbound reuse still interoperates with such peers, but their own duplicate
decisions cannot provide this fork's cross-endpoint lifetime guarantee.

Connection close and transport shutdown share one physical close operation for each Netty
parent channel. Repeated calls wait for that operation and report its original failure if it
fails; they do not issue another close. Completing or cancelling one caller's returned future
does not complete the operation or another caller's shutdown future.

An already-closed connection can report its close result from unrelated nested promise
callbacks without another listener dispatch. A synchronous callback on the connection's
`closeFuture()` keeps earlier physical-close listeners ahead of its `close()` result,
including a call handed to another thread before that callback returns.

For `ConnectionOverNetty`, `retryClose()` explicitly requests a new physical close after
the latest attempt has failed. A call before closing starts the initial attempt; a call while
an attempt is pending shares that attempt. Every retry reports its own result. Once an attempt
succeeds, later retries succeed without another physical request. Ordinary `close()` calls keep
reporting the original result even after a successful retry. Completing or cancelling a returned
retry future changes only that caller's observation.

```kotlin
import io.libp2p.core.Connection
import java.util.concurrent.CompletableFuture

// Invoke explicitly after handling a failed close and resolving its cause.
fun retryConnectionClose(connection: Connection): CompletableFuture<Unit> =
    connection.retryClose()
```

Other `Connection` implementations inherit a default that delegates to their `close()` operation.
An implementation that retains failed close results must override `retryClose()` to support a new attempt.

## Gossip simulator

Deterministic Gossip simulator which may simulate networks as large as 10000 of peers

Please check the Simulator [README](tools/simulator/README.md) for more details

## Android support

The library is basically being developed with Android compatibility in mind. 
However we are not aware of anyone using it in production.

The `examples/android-chatter` module contains working sample Android application. This module is ignored by the Gradle 
build when no Android SDK is installed. 
To include the Android module define a valid SDK location with an `ANDROID_HOME` environment variable
or by setting the `sdk.dir` path in your project's local properties file local.properties.

Importing the project into Android Studio should work out of the box.

## Adding as a dependency to your project

Hosting of artefacts is graciously provided by [Cloudsmith](https://cloudsmith.com).

[![Latest version of 'jvm-libp2p' @ Cloudsmith](https://api-prd.cloudsmith.io/v1/badges/version/libp2p/jvm-libp2p/maven/jvm-libp2p/latest/a=noarch;xg=io.libp2p/?render=true&show_latest=true)](https://cloudsmith.io/~libp2p/repos/jvm-libp2p/packages/detail/maven/jvm-libp2p/latest/a=noarch;xg=io.libp2p/)

As an alternative, artefacts are also available on [JitPack](https://jitpack.io/).

[![](https://jitpack.io/v/libp2p/jvm-libp2p.svg)](https://jitpack.io/#libp2p/jvm-libp2p)

### Using Gradle
Add the required repositories to the `repositories` section of your Gradle file.
```groovy
repositories {
  // ...
  maven { url "https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/" }
  maven { url "https://jitpack.io" }  
  maven { url "https://artifacts.consensys.net/public/maven/maven/" }
}
```
Add the library to the `implementation` part of your Gradle file.
```groovy
dependencies {
  // ...
  implementation 'io.libp2p:jvm-libp2p:X.Y.Z-RELEASE'
}
```
### Using Maven
Add the required repositories  to the `dependencyManagement` section of the pom file:
```xml
<repositories>
  <repository>
    <id>libp2p-jvm-libp2p</id>
    <url>https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/</url>
  </repository>
  <repository>
    <id>JitPack</id>
    <url>https://jitpack.io</url>
  </repository>
  <repository>
    <id>Consensys</id>
    <url>https://artifacts.consensys.net/public/maven/maven/</url>
  </repository>
</repositories>
```
Add the library to the `dependencies` section of the pom file:
``` xml
<dependency>
  <groupId>io.libp2p</groupId>
  <artifactId>jvm-libp2p</artifactId>
  <version>X.Y.Z-RELEASE</version>
</dependency>
```

## Building the project 

This fork uses `io.netty:netty-transport:4.2.10.1-codexcoder21-auto-read` from
[kotlin.directory](https://kotlin.directory/io/netty/netty-transport/4.2.10.1-codexcoder21-auto-read/)
to keep paused child delivery separate from explicit read requests. The
[release backport](https://github.com/CodexCoder21Organization/netty/pull/5) contains the
[automatic-read policy change](https://github.com/CodexCoder21Organization/netty/pull/4).
Other Netty modules continue to use the upstream `4.2.10.Final` release.

To build the library you will need just 
- JDK (Java Development Kit) of version 11 or higher
 
For building a stable release version clone the `master` branch:  
```bash
git clone https://github.com/libp2p/jvm-libp2p -b master
```
For building a version with the latest updates clone the `develop` (default) branch:
```bash
git clone https://github.com/libp2p/jvm-libp2p
```

To build this fork from the `jvm-libp2p` folder, run with an installed Kubo 0.34.1 binary so the integration tests receive an owned node:
```bash
python3 .github/scripts/kubo-node.py run /path/to/ipfs /tmp/kubo-native.log \
  libp2p/build/kubo-node.properties ./gradlew build
```
See [fork build and test instructions](FORK.md#build--test) for targeted checks.

Generate Javadoc archives with `./gradlew dokkaJar` and HTML documentation with
`./gradlew dokkaHtml`. The generated HTML remains in each module's `build/dokka` directory;
Javadoc remains in `build/dokkaJavadoc` and the archives in `build/libs`.
Generate Javadoc without packaging archives with `./gradlew dokkaJavadoc`.

Run `python3 scripts/verify-documentation.py` to verify the standalone Javadoc command
in an independent clean source copy, the combined documentation build,
archive contents, Kotlin metadata and the total 512 MiB Metaspace cap. This check
requires JDK 11 and Android SDK platform 30/build tools 30.0.3, and runs in CI.
It rebuilds from clean outputs and writes logs and JUnit results to
`build/documentation-verification`. After a build,
`python3 scripts/verify-documentation-laziness.py` checks that ordinary commands such as
`help` do not resolve the documentation engines, including in offline mode.

After the build is complete you may find the library `.jar` file here: `jvm-libp2p/build/libs/jvm-libp2p-X.Y.Z-RELEASE.jar`

## Notable users

- [Teku](https://github.com/Consensys/teku) - Ethereum Consensus Layer client 
- [Nabu](https://github.com/peergos/nabu) - minimal Java implementation of IPFS
- [Peergos](https://github.com/peergos/peergos) - peer-to-peer encrypted global filesystem

(Please open a pull request if you want your project to be added here)

## License

Dual-licensed under MIT and ASLv2, by way of the [Permissive License
Stack](https://protocol.ai/blog/announcing-the-permissive-license-stack/).

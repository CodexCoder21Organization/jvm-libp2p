# Source algorithm inventory

## Plan and provenance

- [x] Read repository README/FORK and check for AGENTS instructions.
- [x] Read the four linked DocumentationRepository documents in full.
- [x] Read all four requested changed production files at exact detached HEAD.
- [x] Read whole `ConnectionOverNetty.kt` and `P2PChannelOverNetty.kt` close-related classes.
- [x] Read selector API behavior used by `DatagramPortRelease` from primary Netty 4.2 source/Javadoc.
- [x] Compare source-file changes with `origin/develop` and record hashes.
- [x] Finish coverage audit and send final factual inventory to parent.

OBSERVED: checkout `workspace/jvm-libp2p` is clean and detached at `34a2e78bb82637e2c970a0be40fe4244c76cb547`. The source comparison base used here is `origin/develop` = `e7c79098db289f6a08c8dd467f4bedeb4fd7d168`. Across that base, the four target files are 530 insertions / 185 deletions. No repository files were changed; this report is outside the repository.

## Scope and file identity

| File | Lines | SHA-256 |
|---|---:|---|
| `libp2p/src/main/kotlin/io/libp2p/transport/implementation/NettyChannelClose.kt` | 116 | `0b24c7c87c88961bce57db15b8c3649bee252904c631f6cb9b4cfca41cd771d6` |
| `libp2p/src/main/kotlin/io/libp2p/transport/implementation/PlainNettyTransport.kt` | 410 | `f1a33d0225572c542113c8169a33a756cff980d53341e8bb66865af65c95ee50` |
| `libp2p/src/main/kotlin/io/libp2p/transport/quic/DatagramPortRelease.kt` | 80 | `4aa4aa9e7c40b1394d0b19d2109b9d5efc797302ab422d2d07381cd4461b1d8f` |
| `libp2p/src/main/kotlin/io/libp2p/transport/quic/QuicTransport.kt` | 669 | `e90efbb65f39c1093a94caac25eb179a6169480585ec8d70b8cab00759425619` |

OBSERVED: only `DatagramPortRelease.kt` is new relative to `origin/develop`. The change stack includes: owned group cleanup after channel close; QUIC UDP parent cleanup; one retained shutdown future; aggregation of settled close failures; cleanup on rejected event-loop dispatch; transport closed checks after callback handoff; setup/readiness ordering; selector release waiting; and test-only commits. Commit subjects and source diff are listed by `git log --reverse origin/develop..HEAD` and `git diff --numstat`.

Base comparison, limited to source behavior (OBSERVED):

- `NettyChannelClose.kt`: the per-channel close state, callback observation, one-shot close, and retry logic already exist in `origin/develop`; the target adds `transportCloseResult`, which identity-deduplicates and aggregates settled operation failures.
- `PlainNettyTransport.kt`: base `close()` completes readiness exceptionally before it installs/starts cleanup, returns `allClosed.thenCompose { shutdown boss }`, and therefore chains boss shutdown only through successful channel closure. Target CAS-publishes one shared close result, installs cleanup before completing readiness, starts boss shutdown from an `allClosed.handle` regardless of channel outcome, then aggregates close and group outcomes.
- `QuicTransport.kt`: base has listener/connection snapshots and closes its worker group after successful `allClosed`; target adds separately-owned UDP dial parents, selector release waiting, child-settled gating, loop shutdown hooks/direct-on-loop close fallback, shared shutdown completion, and failure aggregation. The four target files' diff totals are shown above; `DatagramPortRelease.kt` is new.

## `DatagramPortRelease.kt`

State and ownership:

- `ordinary` is one default Netty `SelectStrategy` (line 16).
- `waiting` is a mutable list of release futures guarded by `synchronized(this)` for every access (line 17).
- `destroyed` is a Boolean guarded by the same monitor (line 18).
- The strategy is constructed once per executor-created I/O handler and is mapped by that executor in the `releases` map (factory lines 60–79; constructor association at 63–75). No removal from that map appears in this file.
- The wrapper delegates `prepareToDestroy()` unchanged. Its `destroy()` calls the wrapped handler before draining waiters; `completeAfterDestroy()` is reached only if delegate destroy returns normally (71–76). In observed NIO source, selector-close `IOException` is logged rather than thrown, so that path then returns to the wrapper.

Function behavior:

- `awaitRelease()` creates a new `CompletableFuture<Unit>` (20–21). Under the monitor, it either observes `destroyed` and declines enqueue, or appends the future to `waiting` (22–29). After unlocking it wakes the selector if queued; otherwise it completes the future with Unit (30–31). The wakeup call is outside the monitor and is not caught here.
- `calculateStrategy(selectSupplier, hasTasks)` takes a snapshot of `waiting` and clears it while locked (34–37). If the snapshot is empty, it delegates both arguments to `ordinary` and returns that result (38). Otherwise it calls `selectSupplier.get()` outside the lock (40–41). If this throws any `Throwable`, it restores the snapshot at the *front* of `waiting` while locked and rethrows the same throwable (42–45). If selection returns, it completes the snapshot futures outside the lock, in list order, then returns the selected integer (46–49). Any futures enqueued by completion callbacks are in the next `waiting` batch.
- `completeAfterDestroy()` sets `destroyed=true`, snapshots/clears the current waiters under lock, and completes the snapshot outside the lock (51–57). Later `awaitRelease()` calls observe destroyed and complete immediately.
- `datagramPortReleaseIoFactory(releases)` returns a factory (60–62). Each `newHandler(executor)` builds a `DatagramPortRelease` whose wakeup closure calls the eventual handler's `wakeup()`; it creates a NIO handler with the system `SelectorProvider` and a factory returning that release object as strategy; then stores `releases[executor]=release` (63–70). Returned `IoHandler` delegates all operations except `destroy()`, which first calls delegate `destroy()` and then `release.completeAfterDestroy()` (71–76). `isChangingThreadSupported()` returns true (79).

Selector API boundary (OBSERVED from Netty source):

- `NioIoHandler.run()` invokes `calculateStrategy(selectNowSupplier, !context.canBlock())`. `CONTINUE` returns zero without a select; `SELECT` and `BUSY_WAIT` use the selector wait path; a nonnegative count goes to selected-key processing. The supplied `selectNowSupplier` calls NIO `selectNow()`; its returned selected-key count is used by the run loop (Netty `NioIoHandler` xref lines 418–496; `SelectStrategy` Javadoc lines 83–95).
- Strategy failures that escape `calculateStrategy` are caught by NIO's loop: `Error` is rethrown; other `Throwable`s are logged and handled with a one-second pause, and the current handled count is returned. NIO does not itself complete `DatagramPortRelease` waiters on that exception path (same xref lines 491–509).
- Netty calls `prepareToDestroy()` on shutdown iterations before task processing; NIO snapshots registrations and closes them there. Event-loop cleanup invokes `ioHandler.destroy()` on the event-loop thread; NIO's `destroy()` closes its selector and logs an `IOException` rather than propagating it (SingleThreadIoEventLoop xref lines 200–232, 317–322; NioIoHandler xref lines 519–526, 599–620).
- NIO handler construction opens the selector; the custom release map is populated only after `newHandler()` returns (NioIoHandler xref lines 118–127, 778–819; factory `DatagramPortRelease.kt:63–70`). In NIO `run()`, an `IOException` in strategy/select handling follows selector rebuild + logged pause + return-zero; other throwables reach the outer catch, where `Error` is rethrown and non-Errors are logged/paused before returning the current handled count (NioIoHandler xref `:418–509`). Since this strategy restores waiters before rethrowing (`DatagramPortRelease.kt:40–45`), that Netty catch does not fail them; a later strategy pass can take them again.
- `prepareToDestroy()` is called on each shutdown iteration before task processing (`SingleThreadIoEventLoop` xref `:200–232`). NIO `prepareToDestroy()` calls `selectAgain()`, copies current keys/registrations, and closes them (`NioIoHandler` xref `:599–613`); `selectAgain()` clears its flag and catches/logs any `Throwable` from `selectNow()` (`:769–777`). Event-loop cleanup then calls wrapped `destroy()` on the loop thread (`SingleThreadIoEventLoop` xref `:317–322`); NIO destroy closes the selector and catches/logs IOException (`NioIoHandler` xref `:519–526`).
- Source links: [Netty NioIoHandler 4.2 source](https://netty.io/4.2/xref/io/netty/channel/nio/NioIoHandler.html), [SingleThreadIoEventLoop 4.2 source](https://netty.io/4.2/xref/io/netty/channel/SingleThreadIoEventLoop.html), [SelectStrategy Javadoc](https://netty.io/4.2/api/io/netty/channel/SelectStrategy.html). The repository pins `io.netty:netty-transport:4.2.10.1-codexcoder21-auto-read` (README lines 453–454); the public Netty source view opened here is its 4.2 branch rather than that custom artifact source.

## `NettyChannelClose.kt`

State and operation boundaries:

- Each channel receives one `ChannelCloseState` through a Netty channel attribute. It owns `original`, the first close attempt future, and `latest`, an atomic reference initially pointing to `original` (13–16). The two attribute keys are `closeOperation` and a shared atomic close-notification depth (18–20).
- `observeConnectionClose(channel, source)` creates/reuses the per-channel depth counter, creates a distinct future, and attaches a `whenComplete` to `source`. The callback increments depth, completes the returned future with Unit or the same failure, and decrements depth in `finally` (23–39). The returned future exposes completion of that wrapper callback and its synchronous dependent callbacks.
- `closeNettyChannelOnce` asks `closeOperation(retry=false)` and returns a dependent Unit future (42). `retryNettyChannelClose` does the same with `retry=true` (45).
- `closeOperation` first `setIfAbsent`s a new state (47–52). If it installed the state, it calls `requestPhysicalClose` on `original` and returns it (53–57). If state already existed and retry is false, it returns `original`, even if a later retry exists (58). With retry true it loops reading `latest`; any future not exceptionally complete is returned, so callers share both pending and successful attempts (59–61). If latest is exceptional, it CAS-publishes a fresh future as latest; only a successful CAS starts another physical close; failed CAS loops (62–67).
- `requestPhysicalClose` (70–91): if Netty `closeFuture()` is done, then when close-notification depth is positive it adds a listener to that already-done future to complete the operation; otherwise it completes Unit immediately (75–82). If closeFuture is not done, it invokes `channel.close()`, converts its Netty future to a `CompletableFuture<Unit>`, and maps success/failure to the owned operation (83–87). A synchronous thrown `Throwable` completes the operation exceptionally (88–90).
- Both public close helpers return dependent futures, not the stored owner futures (42, 45), so caller completion/cancellation affects that caller's view only. Both transport `close()` methods also return dependent views (Plain `:238`; QUIC `:205`).
- `transportCloseResult(operations)` visits the list in order. For each it waits using `operation.handle { _, failure -> failure }.join()`, unwraps one `CompletionException`, and deduplicates non-null throwable objects by identity (93–100). The first distinct failure becomes primary; each later one is added as suppressed unless already present (101–106). If suppression did not retain it, the function replaces primary with `IllegalStateException("Transport shutdown reported multiple failures", first)` and suppresses the later failure on that wrapper (106–112). It throws `CompletionException(primary)` after all entries settle, or returns Unit if there were no failures (113–116).

## `PlainNettyTransport.kt`

Fields and ownership:

- `closed` is volatile; `shutdown` holds either null or the one shared shutdown future; `connectTimeout` defaults to 15 seconds (44–52).
- `listeners` maps Multiaddr to listener Channel; `channels` tracks transport child Channels. Both are accessed for mutation/snapshot under the transport monitor; corresponding close-future callbacks remove entries (53–59, 174–178, 282–286, 342–354).
- `workerGroup` refers to the process-wide lazy shared daemon worker group and is not shut down by an instance; `bossGroup` is per-instance and lazy daemon (61–78). `client` and `server` are lazy Netty bootstraps using those groups (80–93).
- `readiness` is one future. Lazy `preparation` snapshots startup preparation functions; empty means complete immediately; otherwise one daemon thread invokes them sequentially, completing readiness on success or exceptionally on the first thrown `Throwable` (106–124).

Functions:

- `activeListeners` and `activeConnections` return the current map/list sizes without synchronizing (95–98). `listenAddresses()` maps current listener channels to local socket Multiaddrs (100–104).
- `initialize()` returns if already closed; otherwise forces preparation. If not running on a Netty event loop it joins preparation and throws the cause of `CompletionException` when available; inside a Netty callback it does not block (126–137).
- `ready()` returns an already-exceptional future with `Libp2pException("Transport is closed")` if closed; otherwise maps preparation to Unit (139–140).
- `close()` creates a candidate and CAS-claims shutdown; later callers return a dependent future of the first shutdown result (142–145). The winner synchronizes, sets closed, and copies listener/channel inventory (147–178). It calls one-shot close for each; `allClosed` covers their union (180–189). Regardless of allClosed success/failure, its `handle` starts `bossGroup.shutdownGracefully(0, 5 seconds)` and converts the result (191–227). Once group close settles, another `handle` aggregates every listener/channel close and group close using `transportCloseResult`; its result completes the shared `completion` successfully or exceptionally (228–233). Only after cleanup continuations are installed, it completes readiness exceptionally with `Transport is closed`, then returns a dependent future (234–238). The process-wide worker group is not part of per-instance shutdown.
- `listen()` checks closed, then awaits readiness by recursive composition if preparation is incomplete/failed (241–249). It synchronizes across second closed check, handler construction, bind submission, listener map insertion, and close callback registration (251–291). Closed within lock throws `Libp2pException`. Listener channel close removes by the address key under lock. Returned bind Netty future is converted to Unit (290).
- `serverTransportBuilder` and `clientTransportBuilder` are abstract extension points (293–301, 329–333).
- `unlisten(addr)` one-shot-closes the mapped listener, or throws `Libp2pException("No listeners on address $addr")` when absent (298–301).
- `dial()` checks closed, recursively waits for preparation if needed, checks closed again, makes a connection builder, resolves/constructs remote socket, connects via client bootstrap, registers the channel immediately from the Netty future, then composes with `connectionBuilder.connectionEstablished` (303–327).
- `registerChannel(ch)` holds the transport monitor for closed check and insertion (334–355). If closed, it calls one-shot close and returns while still inside the monitor. Otherwise it appends and attaches a close-future listener that removes the channel under the same monitor.
- `makeConnectionBuilder` forwards arguments to the builder constructor (357–369). `handlesHost` recognizes IPv4/IPv6/DNS4/DNS6/DNSADDR (371). `hostFromMultiaddr` resolves and uses first address; empty results throw a descriptive Libp2pException; absence of IP4/IP6 after resolution also throws (373–382). `portFromMultiaddr` reads TCP or throws (384–386). `fromMultiaddr` composes host and port into `InetSocketAddress` (388–392). `localAddress`/`remoteAddress` convert channel addresses; `toMultiaddr` is abstract (394–398). The companion owns the single lazy daemon shared worker group (400–409).

## `QuicTransport.kt`

Fields and ownership:

- `closed` is volatile; `shutdown` is the one-shot shared future; `shutdownChildrenSettled` is a future completed after the listener/registered-child close set settles (60–65).
- `listeners` is Multiaddr→listener Channel; `channels` tracks registered QUIC channels; `clientDatagramChannels` is parent Channel→`DialParent`; `terminationCloses` retains close futures started by event-loop shutdown hooks (66–71).
- `datagramPortReleases` maps Netty executor keys to each loop's `DatagramPortRelease`, via concurrent map (78). Worker group is per transport and lazy; its handler factory is the release-aware factory; allocator and protocol fields are lazy/defaulted separately (80–85).
- `DialParent` owns a parent UDP channel, a before-parent-close barrier, that loop's selector-release strategy, a release callback, and volatile optional child (622–625).

Functions and branches:

- Companion factories `Ed25519`/`ECDSA` construct with corresponding certificate algorithm (87–102). `createStream` wraps channel/connection/initiator and stores stream in the channel attribute (104–112).
- `client`/`server` bootstraps choose Epoll datagram channel when Epoll is available, else NIO; client also receives 15-second connect timeout (115–136). `activeListeners`, `activeConnections`, `listenAddresses` report map/list sizes and current bound local addresses (138–147). `initialize()` is empty (149–150).
- `close()` CAS-claims shutdown or returns dependent future of existing shared shutdown (152–155). Under transport monitor, winner sets `closed` and snapshots listeners, registered channels plus mapped owners' children (distinct), and datagram owners (157–165). It one-shot-closes listeners and child channels; `allClosed` tracks both lists (167–171). Regardless of their result, `allClosed.handle` completes `shutdownChildrenSettled`, then creates one `closeDialParent` future per snapshot datagram owner (173–181). It awaits all datagram operations; regardless of their result, shuts down worker group with quiet period zero / 5 second timeout (187–197). After worker group settles, it snapshots `terminationCloses` under lock and calls `transportCloseResult` over listener+child futures, the individual parent-close futures carried inside `datagramOperations.join()`, termination-close futures, and worker-group future (198–204). Shared `completion` reflects aggregate result; returned caller future is a dependent Unit future (202–205).
- `listen()` rejects if closed, constructs server handler, and has its channel initializer call `registerChannel` then install transport handler (208–224). Under the monitor it rechecks closed, submits bind, stores address→channel, and adds close callback removing that address key (226–237). Bind completion logs and maps to Unit (239–242). `unlisten()` one-shot-closes mapped listener or throws when absent (244–247).
- `dial()` starts with closed check and null `AtomicReference<DialParent>`, creates trust manager/SSL context/QUIC client codec (249–267). In parent initializer it captures parent and owning loop; adds a shutdown hook that snapshots same-parent children, starts `closeRejectedChannelOnLoop` for each, waits all child outcomes, then closes parent on that loop; it retains those futures in `terminationCloses` under transport monitor (269–299). Parent close-future listener removes this hook. It then binds UDP parent to ephemeral local port (300–321).
- On successful bind, parent `DialParent` is created. Its `beforeParentClose` is already-complete when no transport shutdown is claimed, otherwise waits `shutdownChildrenSettled`; `portRelease` is looked up by parent event loop; `released` synchronously removes the parent key from `clientDatagramChannels` (301–309). The owner is placed in the AtomicReference. Under monitor, if transport is open it enters the map; if closed, it is not inserted and `closeDialParent(owner)` is invoked after unlock (310–320).
- Bind future handler transforms outcome: when `closed`, throws `Libp2pException("Transport is closed")`, setting bind failure as cause if present; otherwise propagates bind failure as CompletionException or passes channel (321–330). Next stage rejects closed transport, else creates QUIC stream bootstrap, sets allocator/auto-read/remote/stream handler, and connects (331–341).
- On child creation, `registerChannel` false causes closed exception. Otherwise owner is read, child assigned, and child close listener invokes `closeDialParent` only if transport is not closed (342–348). It creates connection/muxer session, extracts the public key from identity multihash or remote cert, sets secure session, calls preHandler, checks `closed`, calls connection handler, stores connection channel attribute, and returns connection (349–375).
- Outer successful dial completion checks whether a connection close was requested, channel is not open, or transport closed. If so with owner, it waits on parent close but maps either completion to the same successful connection; otherwise returns completed connection (377–387). Outer failure unwraps one CompletionException; if transport closed it replaces the error with `Libp2pException("Transport is closed")` and attaches the unwrapped failure as cause. With no owner it returns that failure. With owner it runs parent cleanup, then aggregates the reported failure, an additional child one-shot close if present, and cleanup; composition returns the failed outcome (388–403).
- `registerChannel` atomically checks closed and inserts under monitor. Closed case one-shot closes outside lock and returns false; accepted case adds a close-future callback to remove under lock and returns true (406–426).
- `handlesHost` accepts IP and DNS host components (428). `hostFromMultiaddr` resolves, rejects empty list, selects first IP4/IP6 component or rejects (430–439). `handles` requires host, UDP, QUICv1, and not WS (441–445).
- `quicSslContext` chooses generated ECDSA or Ed25519 connection keys, builds Java key/certificate from local key, configures client or server context (server requires client authentication), trust manager, `libp2p` ALPN, and builds context (447–465).
- `serverTransportBuilder` creates trust manager/context and QUIC server codec (467–539). Per new connection it constructs `ConnectionOverNetty`, muxer session, and channel connection attribute. A first pipeline handshake waiter on `channelActive` requires remote certificate; on presence it extracts peer ID and public key, sets secure session, removes itself, invokes preHandler then connection handler, then calls `super.channelActive`. If absent it closes and throws IllegalStateException. Its `exceptionCaught` logs and closes. Builder sets stream/data limits and inbound stream handler.
- `QuicMuxerSession.createStream` creates v1 multistream negotiation, a controller future, opens a bidirectional QUIC stream, initializes `QuicStream` + protocol handler, forwards negotiated controller completion, maps created channel to its STREAM attribute and forwards exceptions; returns `StreamPromise` (541–568).
- `InboundStreamHandler.initChannel` obtains parent connection attribute, creates inbound `QuicStream`, constructs multistream handler from supplied protocol list, and starts negotiation (570–580).
- `NoTokenHandler.writeToken` always returns false, `validateToken` always -1, `maxTokenLength` 0 (582–601).
- `udpPortFromMultiaddr` requires UDP and parses integer or throws; `fromMultiaddr` resolves host and UDP port into socket address; local/remote address methods convert QuicChannel socket addresses; `toMultiaddr` converts inet address and adds QUICv1 (603–620).
- `closeDialParent(owner)` one-shot-closes owner child or uses an already-completed child future (628–629). Once child close settles either way, invokes `beforeParentClose`; then one-shot-closes parent (630–632). For parent-release future, only the branch `parentClose failed && parent still open` skips waiting for closeFuture/selector release; otherwise it waits on physical closeFuture and then `portRelease.awaitRelease()` (633–641). `parentClosed` waits parentClose and parentReleased, then aggregates those two futures (642–645). The map release callback runs only when that aggregate succeeded and child close was not exceptional (646–648).
- `closeRejectedChannelOnLoop(channel)` starts one-shot close; if physical close future already done or close operation is not exceptionally complete, returns that future (652–654). For a failed close still needing physical close, creates a `DefaultChannelPromise` with immediate executor and performs `channel.unsafe().close(physical)` directly; if that call throws it fails physical and appends an additional exceptional future. It waits all outcomes and aggregates them via `transportCloseResult` (655–668).

Late-child/shutdown inventory sequencing (OBSERVED):

- `close()` sets `closed=true` while holding the same monitor used by `registerChannel` and owner publication, then snapshots existing registered channels and existing owner children (`:157–165`). A child that has already been registered is in the snapshot. A later `registerChannel` sees closed under that monitor, calls one-shot close after unlocking, returns false, and the dial stage throws `Transport is closed` (`:406–419`, dial `:342–344`).
- Dial UDP parent publication also checks closed while holding the transport monitor. If close got the lock first, the owner is not inserted and `closeDialParent(owner)` is called immediately after unlocking (`:310–320`). Otherwise it is present in the close snapshot.
- The transport-close owner’s `beforeParentClose` callback returns completed Unit when no shutdown was claimed; after shutdown is claimed it returns `shutdownChildrenSettled` (`:304–306`). The transport close path completes that barrier only after all snapshotted listener/child close attempts settle, irrespective of their result (`:173–179`).
- Each UDP loop has a shutdown hook installed before parent bind publication (`:272–299`). If the parent has not closed and the hook runs, it snapshots currently registered channels plus the mapped child for that parent, filters by `channel.parent() === parent`, closes those child channels on the loop, then closes the UDP parent on the same loop after the child futures settle. It appends those child/parent close futures to `terminationCloses` under the transport monitor (`:277–299`). The parent close-future listener removes the hook (`:295–296`). Transport final aggregation copies that list only after the worker-group shutdown future settles (`:198–200`).
- The child close listener for a dial calls `closeDialParent` only while `closed` is false (`:345–347`); after close starts, the shutdown snapshot/hook routes own cleanup. The `clientDatagramChannels` snapshot includes only owners published before the close lock wins; an owner published after that point is handled by immediate `closeDialParent` and its loop shutdown hook, not by the map snapshot.

Retention/removal facts (OBSERVED):

- `clientDatagramChannels` insertion only occurs in the open branch of the synchronized bind publication at `:310–318`; the closed branch invokes close without insertion. The only map removal is the owner callback at `:304–308`, which `closeDialParent` invokes only after successful parentClose+parentReleased aggregation and non-exceptional childClose (`:642–648`). Any exceptional result of that test leaves an inserted map entry in place. Values remain in the map until this callback; map entries are copied at shutdown and not cleared by `close()` (`:157–165`).
- `terminationCloses` only grows when each loop shutdown hook executes (`:277–299`) and has no removal in this file. It is copied once near shutdown result aggregation (`:198–200`).
- Per-loop `datagramPortReleases` grows on each `IoHandlerFactory.newHandler` (`DatagramPortRelease.kt:63–70`); no map removal is present. Each strategy's waiter list is cleared on a selector pass, or on destroy; selector supplier failure restores the batch at its head (`:34–57`).

## Whole close-wrapper sources

- `ConnectionOverNetty.kt` lines 21–45 stores volatile selection and close-request flag. `enableConnectionSelection` creates and publishes selection/session, then rechecks close flag and forwards it; `recordCloseRequest` sets the flag then notifies current selection. Close future lazily wraps `super.closeFuture()` via `observeConnectionClose`; `close()` records request and returns one-shot channel close; `retryClose()` records and returns explicit retry (`:45–70`).
- `P2PChannelOverNetty.kt` lines 20–35 lazily wraps the underlying physical `closeFuture`; ordinary `close()` directly invokes `nettyChannel.close()` and converts to void future.

## Coverage ledger

- `DatagramPortRelease.kt`: lines 1–80 read in full.
- `NettyChannelClose.kt`: lines 1–116 read in full.
- `PlainNettyTransport.kt`: lines 1–410 read in full.
- `QuicTransport.kt`: lines 1–669 read in full.
- `ConnectionOverNetty.kt`: lines 1–78 read in full.
- `P2PChannelOverNetty.kt`: lines 1–39 read in full.
- `README.md`: lines 1–554 read in full.
- `FORK.md`: lines 1–125 read in full.
- `DocumentationRepository/CODE_REVIEW.md`: lines 1–232 read in full.
- `DocumentationRepository/PHILOSOPHY.md`: lines 1–93 read in full.
- `DocumentationRepository/architecture/TESTING.md`: lines 1–1062 read in full.
- `DocumentationRepository/playbooks/FIX_DID_NOT_HOLD.md`: lines 1–54 read in full.
- No AGENTS.md exists under `workspace`.

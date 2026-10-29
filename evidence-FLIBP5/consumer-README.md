# UrlProtocol

Low-level P2P protocol for opening raw RPC streams to `url://` services.

## Important: Most Users Should Use UrlResolver Instead

**In the vast majority of cases, you should use [`UrlResolver`](https://github.com/CodexCoder21Organization/UrlResolver) rather than UrlProtocol directly.** With UrlResolver, you only need to know the `url://` URL of the service you want to connect to:

```kotlin
val resolver = UrlResolver()
val service = resolver.openConnection("url://com.example.myservice/", MyService::class)
```

**You do not need to know peer IDs, multiaddresses, or any other low-level networking details.** The url:// protocol abstracts these away entirely. Peer IDs (like `12D3KooW...`) and multiaddresses (like `/ip4/.../tcp/.../p2p/...`) are internal implementation details that normal users never interact with.

**When should you use UrlProtocol directly?** Only in rare advanced scenarios:
- Building custom protocol extensions
- Implementing raw RPC stream communication
- Debugging low-level network issues
- Operating relay or bootstrap infrastructure

For normal application development, use UrlResolver and just provide the `url://` URL.

UrlProtocol pins the organization Netty build `4.2.10.2-codexcoder21` from
[the public Maven repository](https://kotlin.directory). It fixes registered root channels
remaining open when event-loop shutdown starts during a task, and keeps allocator events
unregistered until Java Flight Recorder is initialized. The fixes are tracked in
[the event-loop change](https://github.com/CodexCoder21Organization/netty-2/pull/1) and
[the fork release](https://github.com/CodexCoder21Organization/netty/pull/2). This version also
keeps the fixed transport and buffer modules selected when libp2p brings upstream Netty
transitively. Keep the production and test Netty pins aligned.

UrlProtocol uses `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-41` from
[kotlin.directory](https://kotlin.directory). This release preserves completed Netty future
results when the listener task queue is full, including connection close results. See
[the upstream change](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71).

`PeerRegistry.isInPeerWorkCallback()` reports whether the current thread is in this
registry's lifetime action or removal callback, or in a peer persistence callback.
It reads the current scope without acquiring registry or lifetime locks, restores nested
scopes on return and failure, and grants no permission to modify a lifetime. A coordinator
must use prepared state or reject a request instead of waiting for peer work from such a
callback. [UrlResolver](https://github.com/CodexCoder21Organization/UrlResolver) uses this
contract to prevent its initial network join from waiting on its own caller.

## Local RPC transport failures

`RpcStream.sendRequest` implementations return locally generated failures with a
`RpcTransportFailure` value in `response.error?.data`. Its `phase` distinguishes
`REQUEST_WRITE`, `CONNECTION_CLOSED`, `RESPONSE_TIMEOUT`, and `RESPONSE_READ`;
`cause` retains the underlying failure when one is available. A response read or
admission failure does not necessarily close the stream. Check this type to decide whether to replace a connection;
application error codes and messages do not identify transport failures.

The marker is local to the caller and is omitted when a response is serialized. A
response received from the service cannot acquire it through JSON. Existing error
codes and service-supplied messages are unchanged. Local capacity refusals carry the
`RpcFrameNotAdmittedException` diagnostic described below. Caller-side request serialization failures propagate
their original exception before transport begins; they are not RPC transport results.

`UrlProtocol.sendServiceRpcRequest` follows the same rule. It replaces the cached stream
and sends the call once more only for a `REQUEST_WRITE` or `CONNECTION_CLOSED` transport
failure; a connection that closes after the request was written can therefore run the call
twice. It never retries a `RESPONSE_TIMEOUT` or `RESPONSE_READ` failure, or a service's own
error, whatever its message says. An exception that leaves the stream open, such as a failure
serializing the caller's parameters or an interruption, propagates unchanged and the stream
stays cached; an exception that closed the stream is retried on a replacement stream. A caller
whose thread is interrupted is not sent again; the failure is reported with the interruption
still set, and the stream is still replaced for later calls. If close has begun when a call without
caller interruption would replace its stream, the call fails with the close instead of reconnecting;
this also applies to the replacement attempt. Either way the call is sent at most twice. A replaced
stream stops receiving new calls, lets the calls already in flight on it finish, and is closed when the last of them is
done (or when the `UrlProtocol` is closed); if that close fails, an
`EvictedRpcStreamCloseWarningEffect` is tossed. When the call finally fails, the thrown
`UrlProtocolException` carries the transport failure's `cause`, as the bytecode request
methods do.

`TcpRpcStream` keeps its connection open when an individual response deadline expires or
its caller is interrupted. Use a fresh request ID for each outstanding call: an ID whose
caller has left remains reserved until its late response is consumed or the connection
closes. Reusing that ID before then throws `IllegalArgumentException` without sending
another request. The reserved entry contains correlation state only, with no request
payload or response body. After 64 abandoned, unanswered IDs, new requests are refused
before writing. At most 256 IDs can be outstanding on one connection, matching the
server's default concurrent-handler limit; calls admitted before the 64-ID abandonment
threshold can still finish or expire within that fixed total budget. A refused call returns
`ResponseUnavailable` with `INTERNAL_ERROR` and the actual outstanding ID count.

When at least 64 abandoned IDs remain, the connection is retired after one existing
30-second response-deadline window without an inbound frame, measured from the later
of the earliest remaining expiry (or caller interruption) and the most recent inbound frame. Every frame
counts, including unsolicited frames and late replies; a single-attachment header counts
before its body completes. The retirement error names the unanswered count and silence
in milliseconds. A single expiry never retires the connection, and a peer that begins
answering after 40 seconds can serve siblings and later calls on it. A late reply consumes
and frees its reserved ID. Genuine read/write failures and explicit close still retire
the connection and settle its pending callers.

## Relay request failure logs

A relay writes one `[RelayRequestFailed]` line to standard error when a forwarded request
loses its response path. This is enabled without the debug property. The line names the caller's
`requestId`, this relay's `forwardRequestId`, caller/provider/relay peer ids, and both connection
and stream identities. `nextHopPeerId` names the authenticated peer on the provider-side connection;
on a multi-hop route this can be another relay rather than the final provider. `forwardRequestId` is the id the next hop receives; each relay restores its
caller's id on the response. These two ids let operators join records across relay hops.
`rpcRequestId` joins those transport ids to the caller's application RPC id when its adapter supplies
that id in the `resolverRpcRequestId` relay request header. It is `unknown` when that header is absent.
The relay preserves this diagnostic header across forwarded requests and chunks. The join remains
available when the provider never receives a request or completes normally before its reply is lost;
concurrent RPCs to one provider retain distinct caller ids. This field follows the same escaping and
length bound as the other fields and does not require reading or retaining the request body.

`closedSide` names `caller`, `provider`, or `relay`. `reason` records the event observed at this
relay: `remote-end-of-stream`, `idle-timeout`, `stalled-write-retirement`, `shutdown`, `local-close`,
or `error`; `cause` carries the observed exception when present. Remote EOF does not reveal the
remote process's own shutdown reason. Retirement and shutdown settle pending replies before writing
failure records, so a blocked log sink cannot keep their response-fetch capacity held.
A synchronous forward-write failure also returns its error reply before rendering its failure
record. Protocol exceptions keep their stored message; other write exceptions identify their
class in the reply, with their full message retained in the local failure record. Supplied message
getters and log sinks therefore cannot delay the error reply or later frames on its caller stream.
If response admission fails, the relay closes the affected source channel before printing its
debug diagnostic, so held debug output cannot postpone the caller's terminal transport signal.
Successful forwards add no failure line. A response queued
for transport remains eligible for a failure record until its write completes; queue admission
alone is not delivery. No request or response body is logged. Individual peer-supplied fields retain at most the first 1,024 input characters, then escape
backslashes, CR/LF and semicolons onto one line. Escaping can expand that retained prefix; when the
input is longer, the field also shows its original character count in a truncation suffix.

`RelayService.relayResponseDiagnostics()` returns a bounded, detached list of diagnostic
lines for stalled responses. Fetch rows name each issued request, application RPC id,
transfer, sequence, age in milliseconds, shared connection budget and source child.
Outstanding-forward rows join that request to the forwarded id, caller/provider children
and the provider's current registered child. Reader rows distinguish `starting`, `active`
and `ended` registration read loops, including a retired reader alongside its replacement.
Numeric mux ids and the direction-independent socket pair join the two endpoints of a child.

The snapshot is non-atomic and never changes admission or consumes a response. It returns
up to 64 fetch rows, 64 forward rows (with explicit truncation markers) and the last four
registration-reader records. Each peer-supplied field retains at most 256 input characters,
then escapes backslashes, line breaks and semicolons. No message body is retained.
For example, capture `service.relayResponseDiagnostics().forEach(System.err::println)`
before a request deadline; a later snapshot may already show released fetch capacity.

## Overview

UrlProtocol provides the low-level foundation for peer-to-peer service discovery and RPC communication over `url://` protocol URLs. It includes:

- **`UrlProtocol`** — Low-level API for opening raw RPC streams with direct protocol-level communication
- **P2P networking** — Built on libp2p with Ed25519 keys, gossip-based discovery, and universal relay architecture
- **Peer management** — PeerRegistry, gossip protocol, relay services
- **Service infrastructure** — Service registration, bytecode protocol for sandboxed execution

For detailed information about the network architecture, including routing, gossip propagation, and relay mechanisms, see [docs/NETWORK_ARCHITECTURE.md](docs/NETWORK_ARCHITECTURE.md).

Relay forwarding now maintains a link-state topology alongside the existing distance-vector table. Link-state advertisements list directly observed peer links and measured costs, are periodically re-originated before expiry, and are flooded through the existing gossip/relay substrate with sequence, timestamp, TTL, origin-count, and per-origin adjacency bounds (256 direct links by default). The fixed adjacency bound keeps both the retained LSA database and transient SPF graph linear in accepted origins rather than allowing dense peer-pair growth. Each relay computes its own SPF next-hop table from reciprocally confirmed edges only. Forwarding uses the link-state next hop when one is known, applies a hop limit to forwarded relay requests, and falls back to the distance-vector/relay behavior during convergence or when peers have not emitted usable LSAs. A re-flood pays one shared, bounded transport-admission interval for the whole fan-out rather than one interval per recipient, and it spends that interval on the thread that originated the advertisement: every recipient that recovers writability inside the interval is still delivered to, only a recipient still stalled when the interval expires is dropped, and an advertisement never waits on any other scheduler to run.
Local link-record updates take their list snapshot and allocate its version as one ordered transition.

Ordinary gossip fan-out is best-effort and enters a bounded serial outbox per registered peer. A
shared worker pool services one item from each peer lane per turn, so a backpressured peer neither
blocks the inline broadcast caller nor monopolizes the workers ahead of a healthy peer. The relay
grows its workers with the registered-peer count, up to sixteen workers; consecutive deliveries to
one registered peer reuse one worker. The relay
bounds each lane to eight messages and 12 MiB of estimated retained data, with a 24 MiB aggregate
bound across all lanes; only the saturated peer's excess copies are shed. Authoritative service
withdrawals use the separate reliable path below. The shared pool grows with registered peers,
up to 16 workers, and retires excess idle workers when peers unregister, disconnect, or expire,
without requiring another broadcast. A fan-out excludes its omitted destination from worker sizing.

`RelayService.relayWarningEffectsSnapshot()` includes the most recently published ordinary
gossip transport-admission failure as `RelayOrdinaryGossipDeliveryFailureWarningEffect`. It names
the peer, message ID, message type and failure cause, even when debug logging is disabled.
The service retains one immutable failure record across all peers; later successful sends keep
that failure evidence. Each identity/type/class field retains at most 128 UTF-8 bytes, and the
cause message retains at most 512 bytes, followed by `[truncated]` when needed. The record holds
detached text rather than the message payload or exception. It describes a failed asynchronous
send, rather than the fan-out method's earlier outbox acceptance or remote receipt.

Relay frames on Yamux remain admissible when only their child stream is flow-controlled and
its physical connection is writable, including frames larger than 256 KiB. Yamux's existing
connection-wide reservation bounds queued child writes. The shared compact-copy allowance remains
4 MiB per connection, with at most 256 KiB of copied readable bytes retained by one child during
ordinary flow control. The shared allowance still charges each copy in 64 KiB units. Yamux writes
reserve those shared units before copying; when the allowance is full, a writable sibling can use
the ordinary bounded event-loop handoff. If the physical connection becomes unwritable after a
compact handoff starts, up to four additional copied units on that child may join the same shared
allowance. Only committed pending writes establish that active handoff. Every compact admission
releases the caller's buffer after copying its readable bytes. Larger frames use the ordinary
bounded event-loop handoff. Mplex keeps its 256 KiB compact boundary and strict child admission.

### Inbound substream admission

A host bounds the inbound substreams it holds because the muxer creates a channel, pipeline,
negotiator and negotiation-timeout task before the admission visitor runs.
Factory hosts reserve separate per-connection budgets for request streams and background protocols.
The request soft cap (`MAX_INBOUND_SUBSTREAMS_PER_CONNECTION`) remains 256, with the same 64-stream
headroom: 320 negotiated request streams fit on one root. Identify (`/ipfs/id/1.0.0`), ping
(`/ipfs/ping/1.0.0`), gossip (`/urlresolver-gossip/1.0.0`), and relay
(`/urlresolver-relay/1.0.0`) share a separate 64-stream budget.
Unrecognized protocols use the request budget. Up to 64 additional children may negotiate at once;
negotiations and both admitted categories together remain under the unchanged 1024-stream host cap.
A protocol's capacity decision precedes its multistream acknowledgement, so an excess open fails
before its controller is returned. Refusal resets that child and reports
`InboundProtocolCapacityRefusedEffect`, naming its protocol, budget, occupancy and limit. Every
admitted child in either direction keeps the root alive; per-connection overflow retires the root
only after all its children have closed.

`currentGlobalLive()` and `maxObservedGlobalLive()` report admitted streams. Factory hosts admit at
protocol selection; `currentGlobalNegotiating()` reports their separately held pending children.
The two-parameter and clock-taking three-parameter controller constructors retain the earlier
all-child admission policy for custom hosts. The four-parameter constructor opts into separate
protocol budgets by specifying a positive `maxBackgroundSubstreamsPerConnection`.
At the request soft cap, the host disables automatic TCP reads while keeping one controlled read
pending, so peer departure remains observable and accepted streams continue to finish.

This preservation rule also covers failures while installing a pause, scheduling a controlled read,
or issuing that read. Admission restores automatic reads, renews read demand so a partially received
frame can finish even after a failed resume, and restores matching read-state accounting before
reporting the exact failure through the transport exception path. Excess children continue to be
refused. An accepted root's read failure records retirement after both stream directions finish;
connections dialled by the host retain their retirement exemption.

At the aggregate cap (`MAX_INBOUND_SUBSTREAMS_GLOBAL`, 1024), an excess inbound substream is reset
before protocol negotiation. An accepted root holding live work in either stream direction survives,
including when the same arrival also exceeds its per-connection limit. Its reads are throttled through the
controlled read pump for that aggregate generation. The generation drains below three quarters of
the cap, clearing its refusal throttles; available slots can be admitted before then. A late refusal
cannot publish a throttle belonging to a drained generation.

An accepted root is idle only when it has no live streams in either direction. An idle root asking
while full is retired; a busy root spared after refusal is retired when its last stream closes,
even if the cap has since drained. Host-initiated streams keep their accepted root alive without
consuming inbound aggregate capacity. Registration in either direction and retirement are serialized,
so an older stream's close cannot retire a root carrying replacement work, and a terminal root
cannot register new work. A host-initiated open refused on a terminal root fails both its stream
and controller futures with `ConnectionClosedException`, before protocol negotiation.
Only one close operation is outstanding at a time; if that operation fails,
the root remains terminal and a later refused visit can hand off another close through
`Connection.retryClose()`. Ordinary `Connection.close()` callers keep the result of their first
operation, including after a successful explicit retry. Root diagnostics read from a connection's
close callback already include its terminal classification and final child inventory, matching the
later `ConnectionClosedEffect`. Until physical
close completes, terminal roots retain controlled reads independently of aggregate-generation drain
and per-connection low-water resume.
`InboundSubstreamAdmissionRetirementFailureEffect` reports the exact cause and post-failure
retirement state to the effect scope that constructed the host. If that owner callback throws a
fatal `Error`, the exact error reaches the transport exception observer after the original close
cause. `shedIdleConnectionCount()` counts completed aggregate idle retirements exactly once;
deferred per-connection-only retirements do not contribute. Together with
the muxer's independent per-root child ceiling, this bounds retained resources from refused peers
without discarding their admitted work in either direction. Connections the host dialled are exempt
from these retirement paths.

`InboundSubstreamAdmissionCapEngagedEffect` reports aggregate engagement and refusals, with the live
count, configured cap and running refused total. Reports reach the effect scope that constructed
the controller and are rate-limited through its injected `Clock` to at most one per
`MIN_MILLIS_BETWEEN_INBOUND_ADMISSION_CAP_EFFECTS` (60 seconds). A backward clock adjustment starts
a new baseline without an immediate duplicate report; one interval in the new timeline permits
exactly one further report even under concurrent refusals. Per-connection-only refusals do not
emit an aggregate report or consume that reporting interval. Observer failures preserve reservation
cleanup. Existing two-parameter and no-argument constructors remain compatible with compiled callers;
the three-parameter overload accepts an explicit Clock.

The same counters are available on demand from `currentGlobalLive()`, `maxObservedGlobalLive()`,
`totalInboundSeen()`, `rejectedCount()` and `shedIdleConnectionCount()` via
`Libp2pHostFactory.inboundAdmissionControlFor(host)`. This diagnostic lookup weakly references both
the Host and its controller, so an owner effect handler may capture the Host without making the
factory retain that Host after it stops.

On a relay client's registered connection, peer scoring and relay-delivered gossip callbacks run
in a bounded serial lane for the authenticated relay peer. The connection reader decodes and
enqueues that callback work, then continues dispatching later protocol frames, so an application
callback cannot delay a later RPC frame on the same connection. The default lane retains 64
callbacks in addition to the callback currently running. `RelayConfig.receiveCallbackQueueCapacityPerPeer`
can set a bound from 1 through 4,096 before the `RelayService` is constructed. Ordinary callbacks
also share a 24 MiB estimated retained-data bound. When either ordinary-work bound is full, the
newest ordinary callback is dropped; `RelayService.relayWarningEffectsSnapshot()` exposes the
latest `RelayReceiveCallbackQueueOverflowWarningEffect` with the peer, message type, queue depth,
and retained bytes. Service announcements and withdrawals instead retain only the newest accepted
transition for each provider/service key according to the provider-authored timestamp; a withdrawal
wins an equal-timestamp tie, so an older or equal announcement cannot replace it. An accepted
transition remains retained, including its byte charge, while its callback is running. A reconnect
moves that same transition to the replacement lane unless a newer transition supersedes it.
If a newer transition supersedes an in-flight callback, the old payload remains charged until that
callback releases it even though only the newer transition remains eligible to apply.
Each accepted payload creates one charge, and that charge moves with the payload until its normal
terminal release after application, supersession, or close. At every queue mutation boundary the
charged bytes equal the sum of live entry sizes, and the ledger is exactly zero at quiescence;
closing a lane drops each entry through that lifecycle instead of clearing the ledger in bulk.
Same-key application is serialized across connection generations, and the catalog update and
watermark publication happen together after the application callback succeeds. The timestamp and
kind then remain recorded while the relay catalog retains a live entry or withdrawal tombstone for
that key. The watermark is removed by the same catalog lifecycle that forgets the key, so there is
no separate retention policy.
If those coalesced transitions fill the retained-data or key bound, their connection reader waits
for lane capacity, so a withdrawal is never dropped.
`RelayService.relayReceiveQueueSnapshot()` reports retained bytes, queued and active callbacks,
capacity-waiting service-state offers, active and retired peer lanes, and the configured per-peer
byte limit. Its `isQuiescent` value becomes true only after every retained payload, callback, and
capacity wait has finished.
Lanes belong to an exact connection generation. On disconnect or replacement, accepted coalesced
service-state entries transfer to the replacement lane with their recency intact, while ordinary
queued work is removed. Surviving entries keep their accepted arrival order across different keys.
A callback that already entered same-key application finishes before a newer replacement-generation
transition for that key can apply.
Callback failures,
including `Error` subclasses, appear as `RelayReceiveCallbackFailureWarningEffect`; its
error description is rendered outside relay locks and cached after completion. If its message
getter throws, snapshots retain a completed warning with a null `failureMessage`, the callback
failure class and the description error's class and message. The description error remains
attached to the original callback failure when suppression is enabled and is included in fallback
output. The reporter calls the supplied failure's own `printStackTrace(System.err)`, preserving
caller overrides. If that call throws, its fallback uses the cached warning for the root
description, prints the rendering error safely, and walks available frames, causes, and suppressed
errors without reading the original message getter again. Later snapshots reuse the completed
warning. Its
`staleGeneration` field distinguishes retired-lane failures, which do not close a replacement
connection, from live-generation failures, which close the affected generation. The failure record
is available before registration loss becomes observable. An explicit warning snapshot may render
the supplied error description on its caller's thread; the failure reporter renders it only after
settling the affected connection and replies. Concurrent snapshot reads do not wait behind a
reporter's error-message getter, and completed snapshots reuse the immutable warning. A getter that
reads its own warning snapshot receives the known failure identity with an in-progress description,
without recursively evaluating itself; its full description is available when the outer read completes. An external
`RelayService.close()` waits for callback workers to finish after interruption. A callback worker
that calls `close()` waits for every other receive worker, then returns without waiting for its own
executor task, which finishes after the callback returns. A replacement worker waiting for that
callback's same-key application lock is interrupted during close, so it cannot block the join.
Remote registration-close observations use separate workers that `close()` interrupts but does not
join, so a public refresh callback can wait for a concurrent close without making each side wait for
the other.

Service withdrawals are authoritative relay traffic rather than best-effort gossip. A relay gives all backpressured observers one shared, bounded transient-backpressure interval when forwarding a withdrawal, and reports any delivery failure to its publication gate so the withdrawal remains eligible for retry instead of silently leaving stale routes behind. Withdrawals received from registered peers enter that gate before external fan-out, so one stalled observer cannot make fan-out failure escape another peer's message handler and close the healthy source registration stream. During explicit shutdown, the client keeps its relay listener alive until the relay acknowledges that it parsed and committed the unregister request. The relay attempts every service withdrawal in that committed generation even if an unrelated recipient rejects an earlier withdrawal.

`RelayService.getCommittedRegisteredPeerInfo(peerId)` returns a detached snapshot only after
registration ACK admission completes. While a replacement ACK is pending, the previous live
committed registration remains available. An expired or removed registration and a closed relay
return null. The snapshot includes immutable service and address lists, the
provider lifecycle timestamps for those services, and an opaque generation value. Compare that
value and the service list with a fresh snapshot before publishing captured registration data:
every replacement receives a different generation, including replacements at the same clock time
that reuse the request ID and stream. Keepalive refreshes preserve it; an unsuccessful replacement
restores the preceding generation when its connection remains live. Registration refreshes reserve
provider lifecycle timestamps from the supplied `Clock`, just like initial registrations.

`RelayService.getCommittedRegistrationGeneration(peerId)` reads the installed committed owner's
identity for completion ordering without reading the clock or renewing its lease. It keeps that
identity visible after lease expiry, including while a replacement ACK is pending. Removed or
closed owners return null. This value identifies an owner within one `RelayService` lifetime;
it is not persistent or global order. Use the live snapshot getter before publishing registration data.

Relay registration recovery is single-flight per client. If an immediate recovery pass and the
background reconnect loop overlap after a relay restart, they converge on one live registration
stream; an unchanged service snapshot is reused, while a changed snapshot is acknowledged over that
same stream. This prevents a competing recovery from displacing the listener that serves forwarded
requests. An accepted registration acknowledgement clears the preceding relay failure history
and restores the base recovery delay before its connected identity becomes visible. If that stream
closes while registration is still finishing, recovery uses that acknowledged relay at the base delay.

Each public registration replaces the recovery task and restores its base delay, including when the
registration is rejected. A reconnect that already started may still contribute to the same relay's
failure history until an accepted acknowledgement ends that history. Its older result cannot change
the new public registration's delay. Both rejected and thrown reconnect results follow this rule.

| Recovery state | Owner and terminal boundary | Observable behavior |
| --- | --- | --- |
| Registration intent | Public registration, candidate commit, or unregister replaces it; close is terminal. | An obsolete waiting worker cannot start or schedule a successor. |
| Same-relay failure history | An accepted acknowledgement ends the preceding failure generation. | Started failures remain counted across unacknowledged public registrations. |
| Recovery delay | The current public registration restores the base delay and owns later changes. | A rejected or thrown older result cannot double the replacement delay. |
| Alternative relay choice | The client commits its choice and failure reset together after the selector returns. | A selector that replaces or cancels the intent cannot restore the older choice. |
| Clock task | The client cancels its task on replacement, unregister, or close. | A failed current registration schedules one replacement task. |

`RelayService.sendViaRelayTo(..., output)` delivers a forwarded response in order to a caller-owned
`OutputStream`. Chunked responses use memory proportional to the chunk size and a fixed fetch window.
The method does not flush or close the output; failures may leave a received prefix in it.
`sendViaRelay` remains available for callers that need the complete response as a byte array.
Requesters retain the legacy `__relay_supports_response_chunking__=true` capability and advertise
`__relay_response_framing__=opaque-v2` separately. Current responders select length-tagged chunks or
an explicitly tagged whole body, and keep that selection for every continuation of a transfer.
Published 0.0.608 responders receive the exact legacy capability and return their existing untagged
128 KiB chunks. The receiver accepts that representation as well as current framing; small old
whole replies remain literal, including bytes resembling a chunk header. Older requesters receive
the legacy representation from current responders. Older intermediaries preserve the framing
selection in the existing optional response text field.

Sequential requests to a relay-only target reuse an exclusively held relay request stream after its
complete response has been read. At most four clean request streams remain idle per relay; failures
and partly read responses close their stream, and closing the relay service drains the idle set.
Targets with a public direct address keep the direct/relay race and do not use this idle pool.

> **Note:** The high-level `UrlResolver` class has been moved to [`foundation.url:resolver`](https://github.com/CodexCoder21Organization/UrlResolver). Use UrlResolver for opening typed connections to services. UrlProtocol is for low-level stream-based communication.

## Building

```bash
./scripts/build.bash foundation.url.protocol.buildMaven
```

To build a fat JAR:

```bash
./scripts/build.bash foundation.url.protocol.buildFatJar
```

### Inbound RPC memory safety

Inbound JSON frames retain the protocol's independent 10,000,000-byte wire cap, and are
also admitted through a heap-scaled parse-memory budget before their body is allocated.
The budget conservatively charges seven bytes of transient parser footprint per wire byte;
an estimate that fills or exceeds the concurrency pool reserves it exclusively, serializing
large parses rather than rejecting a frame that is safe to parse alone. Frames beyond the
independent wire cap still fail with a descriptive `IOException` containing the actual frame,
estimate, cap, and concurrency-budget sizes. Admitted frames wait under backpressure and hold
their reservation until parsing finishes or the stream reading them is closed, whichever comes
first: once a stream's `close()` returns — from any caller, including a second or concurrent
one — nothing that stream's reader admitted, JSON or attachment, is still reserved. A declared attachment size reserves future attachment capacity,
but bytes that have not arrived are not retained parse memory. While an attachment reservation is
open, another JSON frame may use its unused portion only after the attachment decoder pauses reads;
reads resume after the last JSON sublease is released. A Mplex borrower uses a lender on another
physical connection, because pausing its own connection would stop the remaining borrower bytes.
Yamux borrowers can use another stream on the same connection. A borrow pause does not consume the
lender's minimum-progress allowance; the last release starts a fresh allowance, while the absolute
frame deadline continues to use that frame's validated attachment declaration throughout admission
and borrowing. This lets independent JSON requests make progress without allowing retained JSON
and attachment bytes to exceed the shared cap.
An expired inbound attachment frame closes its stream and discards buffered input before returning
its admission reservation. Retirement still runs when both live event-loop queues are full, without
waiting for another clock check. Loop shutdown also discards the input and returns its reservation.
A reader that already carries a deadline admits through `acquireParseBudgetByDeadline` instead, so
the wait for admission ends at that deadline rather than when the pool happens to drain. On expiry
the queued reservation is cancelled — it stops holding a queue position and stops reserving future
releases — and the call throws `ParseBudgetDeadlineExceededException` naming the frame size, the
worst-case parse estimate, the permits it asked for, and the total budget. Relay reads use this
form: `RelayStreamController.readMessage(timeout, unit)` applies its own deadline to admission just
as it does to byte reads, and reports a missed admission as `RelayParseBudgetDeadlineException`. A
missed admission consumes no body byte, and the reader keeps the frame's length prefix, so the next
read resumes the same frame rather than losing it. A clean remote close preserves complete frames
already buffered by either timed reader, including a frame whose prefix was retained after missed
admission. After the last complete frame, a read reports end-of-stream promptly. A local close
discards buffered frames. A reader failure makes every later timed read report that failure. A relay
controller also discards its buffered bytes on failure, while a `NettyStreamController`'s plain
`input` stream still drains bytes received before the failure and then reports end-of-stream.
Explicit local close also cancels a queued parse admission
without waiting for the read deadline, including after an earlier remote close.
Interruption during admission retains the prefix too, including when the interrupted call was
resuming an earlier admission miss. A remote close wakes a queued admission immediately when its
body is incomplete; a complete buffered body keeps its admission and is delivered by that same
call. Transport errors wake admission and remain visible to later timed reads.
Concurrent `readMessage` calls take turns owning complete frames, with each waiting caller using
its own deadline. In the original overloads, if a byte wait expires before consuming any body
bytes, the prefix remains resumable. If it abandons a partly consumed prefix or body, the stream becomes terminal instead
of interpreting the remaining bytes as a new frame.
The overload `acquireParseBudgetByDeadline(bytes, deadlineMs, clock)` uses the supplied Clock's
millisecond domain for the deadline. The caller owns that clock. Admission cancels its scheduled
wake on every exit, and a late wake cannot change permit ownership. An immediate grant reads time
once and submits no clock task; only a reservation that has to wait schedules a wake. Absolute
deadlines are compared before deriving a remaining interval, which saturates at `Long.MAX_VALUE`. The two-argument overload
continues to use wall-clock deadlines and a timed wait.
Both timed stream readers also accept a caller-owned Clock for their byte, frame-ownership and
admission waits. In the original overloads, a grant that wins an expiry race may finish an
already-buffered frame; the read deadline does not preempt JSON parsing, but interruption during
parsing withholds the parsed frame (see below).
Interruption of either timed reader follows one rule: once a call has consumed a frame's body
bytes, interrupting it ends the stream. A read interrupted after its final body copy or while
parsing returns null with the interrupted flag still set, never returns that frame, closes the
stream and releases the frame's parse reservation, so a later reader sees end-of-stream rather
than the frame that followed. Interruption also wins over a frame deadline reached at the same
final copy: the call returns null instead of reporting expiry. A complete length prefix is not a
consumed body: an interruption arriving with only the prefix read retains that prefix, as above.

For callers that poll within a longer operation, both readers also provide
`readMessage(idleTimeout, frameTimeout, unit, clock, operationDeadlineMs)`. The idle timeout
bounds ownership, first-byte waiting, and parse-memory admission while only a complete prefix
has been consumed; these states can resume after a poll expires. A partial prefix or any consumed
body byte instead uses the frame allowance, continuing past the idle boundary until completion
or frame expiry, which ends the stream and reports its progress.
A zero idle allowance polls already-buffered bytes without waiting; an expired absolute operation
deadline still prevents a frame from starting. The prefix allowance starts at its first byte;
the body allowance starts at its first byte, so waiting for admission does not shorten it.
An admitted read waiting for its first body byte has the same frame allowance measured from
admission: if the body has not arrived by that deadline, it ends the stream. Before that wait
begins, admission is checked separately: a grant returning after its poll deadline returns the
reservation, keeps the prefix, and reports an admission miss so the next read can resume.
An incomplete frame whose prefix allowance has already expired instead ends the stream with
its complete frame-expiry progress message, whether admission has not started, misses, or grants.
A complete buffered body retains the admission-resumption behavior described above.
A grant winning exactly at poll expiry may finish an already-buffered message while the operation
deadline remains live; JSON parsing and caller-supplied clock callbacks are not preempted.
An optional absolute operation deadline in the supplied clock's millisecond domain caps every
allowance. Admission-only expiry retains the prefix; a subsequent call starts a new poll capped
by that same operation deadline. These bounds do not promise an immutable message lifetime
across separate resumed calls. Expiry after partial prefix or body consumption reports the
declared length (or that it is not yet known), consumed bytes, frame allowance and operation
deadline. Existing overloads retain their grant-at-expiry and untouched-body resumption contracts.
The frame-budget overload checks its deadline again after the complete body copy. A body copied
across that deadline ends the stream with the same frame-expiry error before JSON parsing.

Candidate registration uses the remaining preparation allowance for its frame budget, with the
existing one-second idle poll capped by that operation's deadline. Its polls, frame allowance,
absolute deadline and cancellation timer all use `RelayService`'s application `clock`, matching
`prepareRelayCandidateRegistration`'s clock-measured total budget. That clock also continues to
govern reconnect checks and chunk lifetimes.

The registered listener uses a separate `listenerReadClock`, defaulting to `SystemClock`, for its
idle, frame and admission waits. An application clock jump does not expire its network read.
The constructor overload with required `listenerReadClock` lets embedders control that clock
independently; both clocks remain caller-owned, and the existing constructors keep their signatures.
The listener retains its 60-second allowance starting at the first prefix or body byte, independently
of its 60-second idle wait. A listener has no finite overall lifetime. Initial registration keeps its
10-second acknowledgement deadline; ping keeps its five-second read allowance. Ordinary relay
responses and each request/response chunk read keep the caller-supplied allowance (30 seconds by
default). These limits bound waiting; they do not preempt JSON parsing or caller Clock callbacks.
`Libp2pRpcProtocol.parseMemoryBudgetSnapshot()` reports current and peak reservations, acquisition
and contention counts, queued pool reservations, ordinary-pool available bytes, and deadline
expiry counts with the last expired frame's declared wire length (null before the first expiry).
Each admission miss increments the expiry count once. Registered listeners emit
`RelayParseBudgetDeadlineWarningEffect` for every missed poll and keep their registration active;
candidate preparation likewise keeps polling until its own operation deadline. A warning handler
that throws does not end the listener (the failure is printed by the effects runtime as an
unhandled notification rather than delivered to the same handler again), while a blocking handler
holds up that listener's reads until it returns. A listener interrupted during warning delivery,
or whose handler restores the interrupted flag before failing, keeps its interrupted flag and
exits; `RelayService.close()` completes while a handler blocks.
The parse-memory byte limit reported by the snapshot includes the ordinary parse
pool and the bounded companion-frame allowance described below; `MAX_CONCURRENT_PARSE_BYTES`
remains the ordinary pool's capacity.
Accounted releases verify that their reservation was removed before returning permits to that queue,
so diagnostics cannot temporarily include both an old holder and the waiters that replace it.

The sequential stream reader reserves attachment capacity as each validated length becomes known.
Only one sequential attachment frame grows at a time; independent JSON frames can use the remaining
pool. Ordinary queued reservations allow at most eight fitting later admissions before waiting for
the older reservation to fit. Continuations of already admitted frames have priority so retained
bodies can finish and release their bytes; new attachment frames still take the fair frame turn.
A stalled or expired frame cancels its queued growth and releases both its reservation and frame
turn when its lease closes, even if closing the input does not wake the blocked read. A reader that
later continues cannot grow that closed lease, and its cleanup cannot release the turn a second time.
Turn waits are fair and interruptible, with no numerical bound on total queue time. A holder still
waiting for its initial eight-byte reservation has no progress watchdog yet: only capacity becoming
available or interruption ends that wait. Once admitted, the existing stall and absolute-deadline
guards also cover queued growth and free the turn without waiting for the holder's read to return.
Interrupting an admission wait cancels it; `TcpRpcStream.close()` interrupts its reader.
Callers supplying an arbitrary `InputStream` must interrupt a reader waiting for admission when
cancelling it: that interface provides no close notification, and peer EOF is observed only by a
read. Closing that input alone does not bound either a turn wait or an initial capacity wait.
Frames above the independent attachment aggregate limit are rejected before body allocation with
the declared total, aggregate limit, and pool size. A valid frame above the concurrency pool size
reserves the pool exclusively, preserving large transfers.

Completed chunked relay requests use a separate process-wide fair materialization budget.
`RelayService.relayRequestMaterializationSnapshot()` reports that budget's capacity, retained bytes,
and completed requests waiting for their turn without exposing scheduler or mutation operations.

### Awaited RPC response flow control

After an RPC handler completes, temporary connection backpressure does not discard its response or
close the substream. Computed responses wait in connection-scoped outboxes through a transient
stall; if a root connection remains non-writable with buffered output for the documented ten-second
grace, the server first replaces every buffered computed response with its pre-reserved
`OUTBOX_FULL` frame, then closes the affected substreams and retires that root. The same terminal
frame is sent before a child close or a failed first-chunk admission discards a computed response,
so a waiting caller receives a definite delivery-failure result instead of bare end-of-stream.
On Netty I/O event loops, an admitted response also keeps its delivery owner when the live root's
ordinary task queue is full. A separate bounded handoff resumes draining when that loop runs again,
without another admission or writability event. Other event loops use ordinary task submission. A rejected handoff on a running loop ends the
response through terminal cleanup; a shutting-down loop keeps one cleanup owner until termination.
Response dispatch schedules no clock retries. Waiting for the loop does not start a fresh retirement
grace: the child's original deadline remains in force. Closing the root or ending its event loop
cannot cause a retained task to write afterward.
When UrlProtocol closes a still-writable root with a completed
RPC response queued, it first replaces that response with a framed `RESPONSE_DELIVERY_ENDED` error
naming the same request. If the transport ends before any response frame completes and no frame can
still be written, the server emits one `RpcResponseDeliveryLossEffect` and one structured
`[RpcResponseDeliveryLoss]` stderr record containing the request ID, method, service, child stream,
root generation, close initiator, cause, and completed-frame byte count. The client does not replay
the request in either case. After that close completes, a later stream request excludes the retired
connection from peer connection selection and opens a fresh transport connection.
Transport adapters that initiate a protocol-owned RPC root close must use `closeProtocolRoot()`:

```kotlin
import foundation.url.protocol.closeProtocolRoot
import io.netty.channel.Channel

fun retireRpcConnection(root: Channel) = root.closeProtocolRoot()
```

The returned `ChannelFuture` reports the first protocol close's result, including the same failure
object when it fails. Repeated callers, completion callbacks, concurrent callers, and calls queued
during or after teardown share that result; only the first caller starts a physical close. This
also holds with no pending RPC responses, with the protocol's pipeline handler removed, and after
the root's event loop terminates, whether the first result is pending or complete. Outbound handlers may
forward the physical close with a different promise without starting another protocol close. The
optional promise argument must be a pending, non-void promise for that root channel. A supplied
promise becomes uncancellable once accepted and retains its own listener executor; callers that need callbacks after transport shutdown
must supply an independent executor. The default caller promise uses the root event loop.
The first caller claims the close before submitting work to the event loop; later callers do not
submit another task, and a claim is never re-claimed: every claimed close ends in exactly one
physical close attempt, or in one terminal result delivered to every caller, whatever the event
loop's state. If the root's live event loop rejects the submission because its task queue is full,
the close stays pending and is offered to the loop again at the
100 ms reconciliation interval described below until the loop accepts it; callers are
not failed by a busy loop. If the loop is shutting down, or accepts the task and then terminates
without running it, the loop's termination completes the close on a dedicated per-root daemon
thread: queued responses cannot receive a terminal frame and each gets one delivery-loss report,
and a root the transport already closed completes successfully without another close attempt.
Otherwise one close is attempted from that thread; if it fails, every caller receives an
`IOException` naming the root, with the attempt's failure as its cause.
An admitted response whose write is in flight when close is claimed gets exactly one logical
terminal result. A physical write that already succeeded is not reported lost. A paused answer is
retired immediately; a later physical notification cannot revive it or release its charge twice.
An in-flight write that remains writable keeps its existing completion contract.
If response
preparation or a delivery-loss notification handler throws, cleanup and physical close still run,
and the physical close promise and all protocol callers receive the first preparation exception.
Any distinct later preparation failure is suppressed on that exception, except that a fatal JVM
error (`VirtualMachineError`, such as `OutOfMemoryError` or `StackOverflowError`, or
`LinkageError`) becomes the primary result, with the earlier exception suppressed on it. After the
close bookkeeping has run and every caller is settled, the fatal error is rethrown to the thread
that ran preparation (the `closeProtocolRoot()` caller when it called on the root's event loop); a
fatal error from submitting the close is rethrown to the `closeProtocolRoot()` caller after the
claim has been protected. Failures from response preparation and its pending-write barriers are
recorded before physical close starts. A failure that arrives after the result has been selected
is written to stderr as a `[ProtocolRootCloseLateFailure]` record and is never added to the
exception callers already received. Notifications from independent transport teardown can arrive
after the result has been selected; they are not guaranteed to change a successful close, and
cannot change an already-completed caller promise.
Delivery-loss notification handlers run on the root's event loop during close preparation, or on
the root's dedicated daemon thread when the close runs without that loop. A handler that blocks
stalls that root's close until it returns, and on the event loop also every other channel on that
loop; callers stay pending, not failed, while it blocks. A fatal JVM error rethrown from a task on
the event loop is logged by Netty rather than propagated; it remains the close's primary result.
Promise completion and notification callbacks run outside the response outbox monitor.
Callbacks on a supplied response-write promise observe its outbox accounting already released,
so a callback can admit another response using the completed write's capacity.
A pending response write stops holding up protocol close when its child or root closes, or either
channel's event loop terminates. The outbox releases that write's accounting and observes terminal
changes to the promise it supplied independently of the child executor, including when a live
event loop's task queue is full. This covers handlers that preserve that promise and Netty's
automatic executor handoff. A handler that replaces or aggregates promises must propagate the
terminal result to the supplied promise without relying on a listener task that can be rejected.
Arbitrary promise replacement is outside this completion guarantee: ordinary `PromiseNotifier`
forwarding and the multi-output `MessageToMessageEncoder`/`PromiseCombiner` path can lose their
cascade when the live executor rejects its notification. Their downstream promises can finish
while the original promise and protocol-close callers remain pending. A pipeline-head observer
cannot recover a result completed by a retaining handler before reaching the head; a buffer wrapper
cannot observe a separate promise's terminal state, and an encoder can replace the buffer entirely.
A handler that drops a message without completing the supplied promise also leaves the write
pending while its channels remain live.
Pipeline listeners registered on a handler's executor retain that executor; registrations from an
unrelated worker use the channel event loop. Outbox completion does not move downstream handlers'
listeners to the thread completing a write. Child/root death is
still observed when a close-future notification is rejected or lifecycle handlers are removed.
Automatic stalled-root retirement observes these same write lifetimes before initiating its root
close. An outbound handler that retained the write still owns its
buffer and physical write promise; it must release that buffer. Writes that remain writable on live channels continue to
keep protocol close pending until they finish. A write that becomes paused after close was claimed
is retired immediately through that same logical terminal owner; it cannot become a permanent
close barrier. The existing reconciliation pass also observes that condition when pipeline
notifications are unavailable.
Child streams keep their separate stream-close contract.

The server host factory also accepts an idleClock for RPC read-idle expiry.
For example, pass idleTimeoutSeconds = 1 and idleClock = clock alongside the requestHandler.
Existing overloads use SystemClock. The idle clock controls both timer scheduling and elapsed
idle time, including time excluded while admission pauses the physical connection's reads.
Tests can advance a ManualClock to expire an idle child stream without waiting for real time;
peer bytes renew that child's interval, and stopping the host cancels its pending idle tasks.
Pending RPC handlers and their awaited response writes count as activity: read-idle expiry cannot
close their stream while an answer is outstanding. The default 120-second idle period starts again
after the last pending request finishes delivering its response. A peer close or transport failure
still ends pending requests, and the separate response-write retirement grace remains in force.
If a live event loop's full task queue rejects a completion notification, the completion remains
owned until the next idle check or transport callback applies it on that loop. Its original
completion time still determines the renewed idle interval. If the same overload rejects the idle
check, that check remains scheduled until the live loop can accept it; rejection never starts a new
idle interval. Closed streams and shutting-down loops do not re-arm idle checks or admission reads.
The default [SystemClock](https://github.com/CodexCoder21Organization/community.kotlin.clocks.simple)
dependency is 0.0.16, which releases cancelled callbacks before their deadlines so closed
streams do not remain retained by idle checks. This clock is independent of response-write reconciliation.

`Libp2pHostFactory.createServerHost` and `createServerHostWithRelayHandler` accept a
`reconciliationClock`, for example
`Libp2pHostFactory.createServerHost(requestHandler = handler, reconciliationClock = clock)`.
The host installs this clock on each physical root before relay or direct protocols can create
its outbox, so protocol initialization order does not select the clock. The default is `SystemClock`.
For hosts assembled directly with the libp2p DSL, the existing
`UnifiedRpcProtocolBinding(handler, reconciliationClock = clock)` constructor supplies a default
only when the root has no clock installed; it cannot replace an existing root outbox's clock.
Pending response writes share one reconciliation timer per root, which checks every 100 ms while a write is pending. The clock callback
runs on the clock's thread (for the default `SystemClock`, one timer thread shared by the process)
and only inspects completion, channel lifetime and paused writes already owned by protocol close;
it never expires a write on an open connection that has not begun closing.
When a write needs completing, it hands that work, including any physical close and caller
listeners that follow, to the root's event loop, or to the root's dedicated daemon thread once
that loop has stopped running tasks, so a blocking caller listener never stalls the clock thread.
Reconciliation failures are written to stderr as `[AwaitedResponseReconciliationFailure]` records.
A root registers one listener on its close future and one on its event loop's termination future,
not one per pending write, and removes the latter when the root closes; that removal is linear in
the number of roots sharing the event loop, once per root. Netty notifies loop termination on its
single process-wide `GlobalEventExecutor` thread; that listener only hands the reconciliation pass,
and everything it completes, to the root's daemon thread, so a blocking loss handler or caller
listener cannot stall termination notifications for other roots. A protocol close whose pending
write completes only after the loop stopped closes the root from that daemon thread as well, with
the same result as a close claimed after termination.

Raw third-party Netty closes can be intercepted only while the outbox handler is installed. An
idle root keeps the third party's existing close policy. Once a protocol close has started,
raw closes continue as independent transport operations. In
particular, `UrlProtocol.close()` delegates to `Libp2pNetworkProvider.close()` → libp2p
`HostImpl.stop()` → `NetworkImpl.close()` → `Connection.close()` →
`P2PChannelOverNetty.close()` → `Channel.close()`. These libp2p-owned shutdown calls do not use
`closeProtocolRoot()` and cannot promise the retained result after handler removal. Direct
`Connection.close()` calls have the same limitation.

Admission-control retirement is also a transport-level close, excluded from the routed protocol
close contract. After the last admitted stream closes, its path is
`InboundSubstreamAdmissionControl.visit()` → the stream's `closeFuture()` callback →
`releaseReservation()` → `retireConnection()` → `Connection.close()` →
`P2PChannelOverNetty.close()` → `Channel.close()`. Admission retains its own retirement and
close-failure policy. When that transport close physically retires a root, with or without the optional
interceptor still installed, it does not wait for or replace an already-retained protocol result: later
`closeProtocolRoot()` callers still receive the first protocol result, including the identical
failure object. The transport close's own result is outside that guarantee.

Periodic relay keepalives can pass a busy Yamux child while its physical connection remains
writable, using the same bounded queue as relay replies. They retain the 200 ms admission limit
and require the connection to be able to read their PONG. A stalled physical connection or a
missing PONG still counts toward the existing keepalive failure limit.

Retiring a registration listener closes that generation's stream, including after missing PONGs,
a failed send, replacement, or service close. Outstanding requests on the retired response path
fail promptly with an error naming the peer and request. Their chunk-fetch capacity is returned
through normal transfer cleanup, so another provider on the same physical connection can continue.
A late close from an older stream cannot settle a replacement stream's requests.

Root retirement initiated by the response outbox uses `closeProtocolRoot()`;
`NettyStreamController.close()` and relay-controller close operate on child streams, not the
shared root.
Before invoking a request handler, the server reserves the exact, request-scoped `OUTBOX_FULL`
frame; if that bounded reservation is unavailable, request admission pauses and the handler does
not execute until a completion signal can be guaranteed. Normal and small-priority responses use a
4 MiB per-connection pool, with the priority sublane capped at 1 MiB; the separate failure lane is
capped at 1.5 MiB per connection. Writable roots also share the documented 8 MiB normal/priority
and 3 MiB failure process-wide pools. Queued output paused at either a child stream or its root
releases its process-wide charge and remains subject to its per-connection bound. Before a resumed
queue writes, it reacquires the shared allowance, waiting for space if another connection is using
it. A frame already handed to the transport keeps its shared charge until its write completes or
ends. Each paused answer belongs to exactly one child-owned retirement deadline. Its ten-second
grace starts when output first pauses and renews only on a completed response write on that same
child while output remains outstanding. Writability flips, partial prefixes, sibling traffic and
keepalives never renew or remove an outstanding obligation. A rate-limited child whose complete
responses keep arriving within its grace continues normally. Starting a writable transport write
does not renew a queued answer's deadline. A write that has not paused since handoff retains its
completion barrier during close, with that original deadline still active. Once transport output
pauses, close retires it immediately even if credit returns before close observes it.

| Resource | Creator and owner | Terminal release and re-entry contract |
| --- | --- | --- |
| Paused-output deadline | The outbox attaches each unfinished answer to its child's deadline on first pause. | Only that child's completed response renews it. An empty child cancels it; expiry retires the root. A late callback cannot claim a replacement deadline. |
| Response buffer | The outbox owns queued bytes; the transport owns a handed-off frame. | Completion releases the transport frame; retirement closes its child to drop incomplete transport output. A retaining transport extension remains responsible for releasing its own buffer. No terminal JSON is appended to a partial frame. |
| Shared byte charge | The response owns its charge after transport handoff. | The response's single logical terminal result releases the charge exactly once, including on cancellation or close. Caller callbacks observe the released charge. |
| Handler permit | The handler transfers its permit to its completed response. | Completion or retirement returns it exactly once. Late physical-write notifications cannot return it again. |
| Connection close | The first protocol-close caller owns the root close. | Close immediately retires paused answers, even during brief returned credit. It never waits on their incomplete frames; re-entry joins the retained close result. Transport failure and child cancellation retire their answers through the same terminal result. |


A computed response that does not fit its lane right now is never discarded for lack of space: it
waits, in arrival order behind any earlier waiting response of the same connection, until queued
responses drain, and is then queued and sent. Ordinary direct libp2p handlers share an explicit
64-request allowance across the process. A handler takes one slot before running and its completed
response keeps that slot until the response is written or released, including after queue admission
or handler-coroutine cancellation. Thus at most 64 ordinary direct handlers and completed, retained
answers share this allowance; the dispatcher thread limit alone does not enforce it. Handlers that
opt into `LongPollRpcRequestHandler` use the separate long-poll allowance described below for their
declared requests. Relay forwards retain
their separate existing stream or handler admission while waiting for queue space. Writable output
still shares the existing process-wide byte pool; paused queued output releases its shared charge as
described above. If the
connection or child stream ends while a response waits, the response is released exactly once
through the same close handling as a queued response (a `RESPONSE_DELIVERY_ENDED` or reserved
terminal frame where one can still be written). A root with queued or in-flight output that stays paused at
either a child or the root is retired after the same ten-second grace. Only a response larger than its whole
per-connection lane, which can never fit, is still answered with the pre-reserved `OUTBOX_FULL`
result, stating that the handler completed and the request was not replayed.

#### Declaring long-poll requests

A handler passed to `Libp2pHostFactory.createServerHost`, `createServerHostWithRelayHandler`, or
`UnifiedRpcProtocolBinding` can implement `LongPollRpcRequestHandler` to declare requests that wait
for a change. Its `isLongPollRequest(request)` callback runs before admission on the stream's event
loop, so it must be quick, nonblocking and free of side effects. Declare paths from the server's
handler knowledge; a client-supplied flag must not grant this classification. Other handlers and
undeclared paths keep ordinary queueing, including paths whose names happen to contain `poll`.

```kotlin
import foundation.url.protocol.EffectPropagator
import foundation.url.protocol.Libp2pHostFactory
import foundation.url.protocol.Libp2pRpcProtocol
import foundation.url.protocol.LongPollRpcRequestHandler
import kotlinx.coroutines.CompletableDeferred

val changed = CompletableDeferred<String>()
val handler = object : LongPollRpcRequestHandler {
    override fun isLongPollRequest(request: Libp2pRpcProtocol.RpcRequest): Boolean =
        request.method == "example/waitForChange"

    override suspend fun handleRequest(
        request: Libp2pRpcProtocol.RpcRequest,
        effectHandler: EffectPropagator,
    ): Libp2pRpcProtocol.RpcResponse = Libp2pRpcProtocol.RpcResponse.success(
        request.id,
        if (isLongPollRequest(request)) changed.await() else "current value",
    )
}
val host = Libp2pHostFactory.createServerHost(
    listenAddresses = listOf("/ip4/127.0.0.1/tcp/0"), requestHandler = handler,
)
try {
    host.start().get()
    // Other application work eventually calls changed.complete(newValue).
} finally {
    host.stop().get()
}
```

Declared long-polls share **192 slots process-wide**, across hosts and services, independently of
the ordinary 64. This is a policy choice matching UrlResolver's chosen parked-request allowance,
not a measured capacity or a per-service guarantee. A suspended handler must suspend its coroutine
rather than block a request thread. Its slot remains held through resumption and response delivery
or retirement, including completed replies waiting outside the byte-accounted outbox and queued
`OUTBOX_FULL` replacements for oversized answers. Long-polls
still count as activity for read-idle expiry, but do not trigger the ordinary 100-request read pause;
ordinary requests on that same persistent stream can therefore still arrive.

A declared long-poll beyond 192 never invokes the handler and returns an RPC error with code
`LongPollRpcRequestHandler.NOT_STARTED_ERROR_CODE` (`LONG_POLL_NOT_STARTED`), no `data`, and message:

> Long-poll request was not started because all 192 process-wide long-poll slots are held by handlers or undelivered responses; retrying the same read is safe.

The wire form is an ordinary `response` frame with the original `id` and
`error: {"code":"LONG_POLL_NOT_STARTED","message":"..."}`. A client can check `response.error?.code`
against `NOT_STARTED_ERROR_CODE` and retry that same read with bounded backoff. UrlProtocol does
not automatically retry it. This code is emitted only before handler dispatch; a handler failure,
completed response, or transport failure does not acquire this admission meaning.

Refusal frames use the long-poll completion pool, shared with accepted polls' reserved error
frames, and go directly into the charged failure lane, never the uncharged waiting list:
1.5 MiB per root, 3 MiB across writable roots, and 64 KiB per frame, with the existing paused
output retirement rules. If a refusal cannot reserve those bytes or its request ID makes the frame
larger than 64 KiB, the child stream is closed promptly. A closed stream or missing reply alone is
not proof that a request did not start. Queued refusal frames are released when their stream closes.
Accepted declared polls reserve their `OUTBOX_FULL` completion frames from this same long-poll
pool, leaving ordinary completion capacity available even when accepted polls or refusal frames
fill their pool. If running handlers hold all of that space, excess polls close their child without
waiting for a handler to finish. Each reservation keeps its pool through response replacement,
paused output and release. Returning local or shared completion space wakes requests waiting to reserve it, including
when queued refusals are dropped on child close or paused output returns its shared charge.
Both failure pools share the existing writer turn. Accepted requests share the existing
normal/priority response-byte pools; separate count and failure allowances do not provide separate
network bandwidth.

For UrlResolver integration, its outer RPC adapters must implement this interface and forward the
classification from its server-side `LongPollServiceHandler` declarations. In particular,
`ClockStampingRpcRequestHandler` and `diagnosticProviderRpcHandler` must preserve the declaration
before passing the handler to the host factory. The transport does not infer resolver path names,
and changing a service wrapper alone does not opt an existing outer RPC adapter in.

Drain scheduling round-robins across the three lanes and, within each lane, across writable mux
substreams. A continuously replenished small-response or failure lane therefore cannot starve a
large normal response, and one busy substream cannot monopolize its lane. Each event-loop
invocation drains at most 32 entries before rescheduling so response recovery yields to unrelated
connection work.

Diagnostic outbox and writability-wait callbacks run only after every ownership scope on their
calling thread has returned: the outbox monitor, per-stream framing locks, capacity/charge gates,
and writer-owned large-response turns and parked-write permits. One exception-isolated reporter
handles these callbacks. Thrown nonfatal observer or reporting failures do not interrupt the drain
or bypass buffer and capacity cleanup. The reporter renders messages and stack traces inside its
own boundary; if rendering throws nonfatally, it writes a fixed record naming the exception
classes. If that sink also throws nonfatally, the root channel's String attribute
`foundation.url.protocol.observerReportingFailure` retains the latest complete fixed record,
replacing the previous record without more rendering or sink calls; later notifications continue.
`VirtualMachineError`, `ThreadDeath`, and `LinkageError` propagate after ownership handoff or
release, without further diagnostic rendering or dispatch of the scope's remaining notifications.
A fatal `DRAINED` callback ends its current task while already queued siblings retain a separate
drain owner. If the event loop rejects that sibling submission because its task queue is full,
the rejected scheduling claim is released and an accepted event-loop continuation tries the
ordinary scheduling claim after the current task exits. If that continuation is also rejected,
protocol close releases the queued responses and sends `RESPONSE_DELIVERY_ENDED` where a terminal
frame can still be written; otherwise the caller observes connection closure and delivery loss
is reported. The original fatal object still propagates; debug logging records a rejected sibling
submission by its exception class and message. Neither recovery path invokes observers while
holding the outbox monitor, a framing lock, or a writer permit.
A response that has to wait for space reports `WAITING:*` instead of `ADMITTED:*`, and `PROMOTED:*`
once it is charged and queued; every computed response reports exactly one of `ADMITTED:*` or `WAITING:*`.
`ADMITTED:*` and `DRAINED:*` have **no guaranteed arrival order**, even for the same entry.
The submitting thread reports admission after scheduling the drain and leaving its ownership
scopes; the drain worker reports `DRAINED:*` after attaching the completion listener and handing
the buffer to the transport (or completing synchronous write-failure cleanup), outside its own
scopes. Consumers must count events rather than infer per-entry order from arrival.
Writability callbacks likewise report a wait after writer ownership returns. Tests that need to
observe or interrupt a live wait use implementation-owned final passive records with read/await
access; callers cannot supply the signaling method or attach a callback to the private signal.

The raw-TCP optimized bytecode path identity-accounts the arrays retained by completed response jobs
from job creation through delivery, refusal, or connection teardown. This includes a complete job
waiting for shared writer capacity. One array allocation is recorded once while any response job
refers to it, whether the provider keeps, replaces, or releases its own reference. Concurrent
responses sharing one implementation JAR therefore do not multiply its recorded size, while fresh
arrays remain fully recorded until their last response releases them. The public snapshot reports
both all retained response bytes and the subset admitted to writer queues and charged against the
allowances. One process-wide lock serializes both sets of attachment reference counts, allowance
totals, and reported peaks across every connection; the lock is never held during socket IO.

An upgraded 1.1 client advertises support for interleaved large-response frames in a private
top-level request field. Only the literal JSON Boolean `true` enables the capability; an absent
field, `false`, `null`, strings, and numbers use the legacy writer. For that client, a response
larger than 1 MiB is a compact start frame followed by complete attachment-chunk frames of at most
256 KiB. The server retains the fair
per-connection large-response turn for the sequence, but releases the substream write lock at every
complete frame boundary and schedules the small-response outbox before the next chunk. A computed
small reply can therefore complete while the unrelated large response is still transferring,
without placing any response bytes inside another frame's declared attachment body. The receiver
holds one bounded reassembly lease and leaves a 1 MiB compact-frame lane for continuation frames.
An admitted assembly's continuations do not consume the bypass allowance for new memory readers;
a queued whole-pool reader can wait for an assembly, but cannot prevent its remaining chunks from
being admitted. New assemblies still use the ordinary bounded fairness rule.

Ordinary JSON replies retain the existing 10,000,000-byte wire limit while another response is
being assembled. Across the process, one companion frame at a time may use additional capacity
for its combined JSON estimate and attachment bytes. The extra allowance is bounded by
`MAX_JSON_FRAME_BYTES * PARSE_AMPLIFICATION + MAX_ATTACHMENT_AGGREGATE_BYTES - 1 MiB`;
the existing ordinary parse pool and assembly limits do not change. Actual extra bytes are charged
before allocation and included in `parseMemoryBudgetSnapshot()`, and released on delivery or close.
The frame's JSON and all attachment destinations share one accounting boundary. This preserves
valid ordinary envelopes without letting their JSON and binary storage each reuse the same lease. Interleaving is selected only when the fully serialized
start, including its four-byte frame prefix and escaped response envelope, fits that 1 MiB lane and
both JSON bodies pass the receiver's structural checks. Larger JSON envelopes use the legacy
writer, avoiding a multi-megabyte start before the first schedulable attachment chunk. A terminal
`OUTBOX_FULL` response releases any partial
assembly before it completes the request. Direct-stream response admission never waits on the
reader that must consume an earlier response's chunks: it reserves the combined destination-byte sum and response JSON parse budget
before parsing or allocation, returns a terminal `INTERNAL_ERROR` with the required and available byte counts
when that reservation cannot fit, and consumes the rejected transfer's later chunks without
retaining their payload. Explicitly closing the direct stream, or reaching a request timeout,
releases every partial assembly and active companion-frame reservation before waiting for the
transport reader to finish. Interrupting one request caller instead removes only that request's
pending entry, releases only its partial assembly and leases, and retains bounded discard state
for its later chunks. The stream keeps serving other callers while that discard state fits. Each
stream retains at most 64 abandoned-response records and 1 MiB of their bookkeeping. If another
interrupted request cannot install its record within either bound, the stream closes explicitly
with the complete bound error as its terminal result. The interrupted caller keeps its selected
interruption or send failure and carries the cleanup error as a suppressed failure; pending and
later callers receive that same terminal bound error. Buffered frames read after this terminal
transition are ignored. `interleavedResponseStateSnapshot()` reports the active assembly count,
abandoned-response count, and retained bookkeeping bytes for operational accounting.
This request-local cleanup is serialized with start-frame admission, so a concurrent start is
either admitted and immediately abandoned or recorded for discard before admission. Start-frame
attachment lengths must be exact JSON integers within the per-attachment limit; fractional and
out-of-range values are rejected without numeric truncation.

Older 1.1 clients do not advertise this capability, so the server keeps the legacy atomic frame:
the full header-and-chunk sequence holds the substream write lock, the drain skips that substream's
in-flight marker, and deferred responses run after the frame ends. Framed request, push, effect and
controller writers take framing ownership before large-write ordering. Independently committed
interleaved chunks do not register a multipart-order marker, so a push holding the framing lock
cannot wait on a chunk that needs the same lock. This compatibility path keeps
the established 1.1 wire bytes and frame-integrity rule unchanged across mixed-version peers.

### Complete frames declined before admission

`RpcFrameNotAdmittedException` is an `IOException` proving that an entire RPC frame was
locally declined for outbound capacity before any byte from that frame was admitted. Its
`frameSizeBytes` and `outboundCapacityBytes` report the complete wire-frame size and the
declining channel's outbound high-water capacity, whether it is the child or an ancestor.
The capacity is null if bounded write admission declines while no channel is currently unwritable. `reason` says why
admission was declined: the write ran on the channel's event-loop thread while it was unwritable, every parked-write
permit for the stalled connection was already in use, or the backpressure wait expired while the channel stayed open
(the peer was not reading). `remotePeerId` names the peer the frame was addressed to. The exception message carries
the size, peer, reason and capacity. `Libp2pRpcStream.sendRequest` preserves this
exception in the local `RpcTransportFailure.cause`. The controller's raw byte-array output also
reports this type for a locally declined write; its size and zero-admission guarantee cover only
that one write operation. Replaying a whole request requires that the operation contained the
complete request and no earlier operation admitted part of it. A caller can use its type as local evidence
that this frame did not reach the peer; exception message text is not admission evidence.
A failed transport write or a failure after an attachment header was admitted remains an
ordinary I/O failure with an uncertain outcome. Earlier writes are never covered by this
frame's guarantee. Best-effort frame writers keep their existing boolean result.

### Closed writes before admission

`StreamClosedBeforeWriteException` is an `IOException` proving that the stream closed before
any bytes from that particular write were admitted. The message and `streamDescription` identify
the stream. Netty controller outputs and in-memory stream outputs report this type at their
closed-before-admission boundary. A write that already admitted a prefix retains an ordinary
`IOException`; a successful earlier write is never covered by the later exception's guarantee.
Callers can safely replay a request only if the rejected operation contained the complete request
and no prior operation admitted part of it. A prior zero-byte probe does not reserve the stream.
In-memory RPC responses retain their detailed write failure message and this exception type;
their cause chain also retains the output exception and its write diagnostic.

### Transport failure attribution

A request that loses its response to a stream ending returns `Stream closed`, and a request whose
write fails returns `Failed to send request: …`. The prefix stays the same; a local capacity
refusal includes the typed non-admission diagnostic. These messages name no connection — which makes a recurring loss impossible to attribute when several peers serve the same
service and relays carry some of those connections.

Each failed request therefore also emits `RpcTransportFailureEffect`, carrying the service
identifier, the destination `remotePeerId`, the `relayPeerId` that carried the connection (null for
a direct one), the failing request ID, and the underlying transport cause. `RpcStream.relayPeerId`
exposes the same relay hop for callers that want it directly; `Libp2pRpcStream` derives it from the
`/p2p-circuit/` segment of the multiaddress it was dialed at, which `Libp2pNetworkProvider.dial`
supplies.

The effect is emitted from the requesting thread, so a caller's `handler { … }` receives it — the
stream's reader is a daemon thread that cannot reach caller-installed handlers. It is also written
to standard error, so the report survives a caller that installed no handler. Exactly one effect is
emitted per failed request: a request that receives its response emits nothing, an orderly close
with nothing in flight emits nothing, and a request cancelled by a local `close()` is not attributed
to the peer — neither one whose response was still in flight nor one whose write was interrupted by
that close.

A request whose deadline fires returns from either its write or response wait without waiting for
controller cleanup callbacks. It retains the snapshot or getter failure recorded by the deadline
and any cleanup failure already recorded when the caller selects its result. The deadline claims
terminal child-close ownership before releasing the caller, so automatic stream cleanup cannot
run the controller callback on that caller. A cleanup failure arriving later is reported through
`RpcTransportFailureEffect` and stderr on the deadline callback's thread; it does not change the
exception already returned to the caller. Physical child closure still releases an attachment reader
and its parse-memory lease; the host retains ownership of the shared root connection.

### Connection close records

Every host built by `Libp2pHostFactory` records why each of its root connections closed. When a
connection's channel closes, the host writes one line to standard error and, if the host was built
inside an effect scope, tosses `ConnectionClosedEffect` to that scope. Without an owner scope, the
stderr line is the complete record:

```
[ConnectionClosed] Connection connection@1b2c3d closed: initiator=REMOTE_END_OF_STREAM; direction=inbound; localAddress=/ip4/10.0.0.5/tcp/35000; remoteAddress=/ip4/10.0.0.9/tcp/51422; remotePeerId=12D3KooW...; closedBy=none; cause=none
```

`initiator` is one of `ConnectionCloseInitiator`:

- `LOCAL_CLOSE` — code in this process asked the channel to close. `closedBy` names the first
  calling method outside Netty and the libp2p channel wrapper only when the close was requested on
  the channel's own event loop. A close requested from any other thread (a worker thread, a
  `CompletableFuture` callback, most application code) is queued by Netty, so its record says
  `closedBy=unknown`.
- `LOCAL_EVENT_LOOP_SHUTDOWN` — this process shut down the event loop that owned the channel.
- `TRANSPORT_ERROR` — a read or write failed with an I/O error first, such as a connection reset
  by the peer; `cause` names it, and `closedBy` names a handler that closed in reaction, if any.
  `cause` only sees exceptions that reach the head of the root channel's pipeline (transport read
  failures, exceptions fired from the head, failed write promises); an exception raised inside
  another handler travels towards the tail and is not recorded.
- `REMOTE_END_OF_STREAM` — nothing local closed the channel and no I/O failure was seen, so the
  peer (or a hop between the two peers) ended the stream.
- `CLOSED_BEFORE_OBSERVATION` — the channel had already closed when the connection was handed to
  the recorder.

`direction` is `outbound` for a connection this host dialled. The identity `connection@<hex>`
matches the identity in this process’s admission records and errors. Across processes, join the
records by the pair of peer IDs and mux substream IDs with their initiator, then use endpoints and the opening
timestamp to distinguish links. The closing side records `LOCAL_CLOSE` or `TRANSPORT_ERROR`, and
the other side records how the close reached it. Only root connections are recorded, once each; no
payload bytes, keys or per-message events are logged.

These records append a `Root link` sentence containing `localPeerId`, `remotePeerId`, both
socket endpoints as this side sees them, `openedAtMillis`, the first eight direction-qualified
`substreamIds` (`outbound:<id>` or `inbound:<id>`) and the full `inFlightSubstreams` count,
`reason`, `initiatedBy`, and `remoteReportedReason`. When more than eight children are present,
the list is followed by `(+N more)`, where N is the omitted count. The immutable snapshot also
retains only that sample; use `inFlightSubstreams` for the total. Records about a particular
child, such as response-delivery loss, still name that child separately even when it is outside
the sample. `remoteReportedReason` is
`not received` unless an existing root-retiring terminal RPC frame carries a reason; then the
snapshot preserves that frame’s full error message. There is no new close-reason wire field. Opening time is when this host
observes the established connection. IDs describe live mux children, not RPC request counts.
Direction describes who opened the child relative to this side, so the opposite side reverses
`outbound` and `inbound` when joining records. Both directions can use the same number in Mplex;
they remain separate entries and closing one does not remove the other.
The snapshot preserves the sample and total count present when the physical root ends, even after child cleanup.
An attempted close has its own immutable snapshot. If that operation fails while the root stays
open, live child tracking continues and a later actual end records the children then present.
Relay close observations follow the same attempt ownership: a failed close releases only its own
observation, preserving other pending closes and any observed transport failure. Physical end is terminal.
A buffered terminal reply parsed after the physical end can add the received remote reason to a
later snapshot; a previously issued snapshot keeps the values it originally observed.

Native controllers and RPC streams implement the optional `RootLinkDiagnosticSource` capability;
`Connection.rootLinkDiagnostics()` exposes the same immutable local snapshot. A transport exception's
message getter may read that snapshot directly or through another thread. If a read arrives while
that message is being rendered, the terminal snapshot uses the exception class followed by
`description is already being rendered`; the close effect still renders the original exception.
The terminal classification and child inventory remain shared by all readers. A host built outside
`Libp2pHostFactory` can still report its peer IDs and endpoints, but fields it did not observe say
`not observed`. An RPC child can end before its root; until a root-end event arrives, its snapshot
has `initiatedBy=not observed`. Local `RpcTransportFailure` data and `RpcTransportFailureEffect`
carry this snapshot, and bytecode-fetch transport exceptions append it. Existing RPC error codes
and message text remain unchanged. Completed-response delivery-loss records retain their outbox
generation and append the same root identity. No identity is added to wire frames.

Raw TCP (`TcpRpcStream`) also implements `RootLinkDiagnosticSource`. Every local
`RpcTransportFailure`, including pending calls settled by close, carries an immutable snapshot
with a stable `tcp@<counter>` identity, the service and opening context, both socket endpoints,
and the opening time from its response clock. `connect` allocates the identity before dialing;
a socket passed to the constructor is observed at handoff. Concurrent connections to the same
endpoint have distinct counters. Endpoints and identity remain available after close, and an
individual request deadline retains the identity without closing the connection. TCP has no
libp2p peer identity or mux children: `localPeerId` says `not applicable (raw TCP)`,
`remotePeerId` is the stream's synthetic destination label, and the child inventory is empty.
`reason` retains the observed TCP origin or silence-retirement reason; a local close or observed
remote EOF/reset identifies that side, while other I/O failures leave the initiating side
`not observed`. No remote close reason is inferred.

If `TcpRpcStream.connect` fails, it throws `TcpRpcConnectionException` (an `IOException`)
with the original I/O exception as its cause and the same diagnostic capability. Its message
includes the snapshot and intended remote endpoint even when the connection never completes.
Existing RPC error codes and message text are unchanged; diagnostics remain local and add no
wire fields or negotiation.

Inbound admission also records its own decisions to close or refuse, with the same delivery (a
stderr line plus an effect to the scope that constructed the `InboundSubstreamAdmissionControl`, if
one exists):

- `[InboundAdmissionRetirement]` / `InboundSubstreamAdmissionRetirementEffect`, written once for
  each close attempt of an accepted root, after that close has completed or failed: the retirement reasons (`aggregate-cap-refusal`,
  `per-connection-overflow`, `read-failure`), the `InboundAdmissionRetirementTrigger`, the remote
  peer and address, the live substream counts on the connection and across the host, and the
  read failure's cause when there is one.
- `[InboundAdmissionRefusal]` / `InboundSubstreamRefusedEffect`, written for the first refused
  (reset) inbound substream on each root only, with the refusal reason (`aggregate-cap`,
  `per-connection-cap`, `connection-retiring`, `connection-closed`) and the live counts. Later
  refusals on that root are counted by `rejectedCount()` but not written individually. It is
  written after the refused substream has been reset and after any close that refusal started.

Every record is produced only after the close or reset it describes, so a failure while building,
writing or delivering it cannot prevent or hold up that action. Such a failure is still reported:
for the admission records through the root channel's exception path, as admission's other cleanup
failures are, and for `[ConnectionClosed]` through the thread's uncaught-exception handler, since
the channel is already closed. A fatal JVM error from an admission record is rethrown once the
reset and close are done. Delivery to a handler holds the monitor of the effect scope that built
the host, as admission's other effects already do, and every host built in that scope shares it.
Handlers for these effects must return promptly: a close that has already happened stays
completed, but a blocked handler (or a blocked standard-error stream) stalls the thread delivering
the record, usually a Netty event loop, and every later record delivery in that scope waits behind
it.

## Usage

> **Note:** For high-level typed connections, use [`UrlResolver`](https://github.com/CodexCoder21Organization/UrlResolver) from `foundation.url:resolver`. The examples below show the low-level `UrlProtocol` API for raw stream communication.

### Peer registry add provenance

`PeerRegistry.addPeer(peer)` remains the direct application API and records `CALLER` origin. Code
that feeds a registry from another source can use the additive overload with
`PeerRegistryAddOrigin`. UrlProtocol marks its own service and peer catalogs as `CATALOG`, peer and
LAN exchange as `DISCOVERY`, relay paths as `RELAY`, and restored records as `PERSISTENCE`.

`getPeerOriginState(peerId)` returns the current retained lifetime's latest accepted origin and a
monotonic `hasCallerOrigin` bit. A later network refresh changes the latest origin but never clears
caller authorship. That bit is persisted; on restart the latest origin becomes `PERSISTENCE` while
the prior caller-authorship fact is retained. Removal clears the state for that lifetime.

Peer-file writes are coordinated with the exact retained lifetime. Removal waits for a write that
already started before deleting its file, while a write that starts after removal skips the stale
lifetime. This applies both to accepted additions and to `saveAll()` snapshots, so a completed
removal cannot reappear after restart.

### Low-Level API (UrlProtocol)

### Selecting a Network Provider

`UrlProtocol` uses `Libp2pNetworkProvider` by default, preserving the existing TCP, Noise,
multiplexer, relay, and peer-identity behavior. Applications that need to control the provider
instance can pass it explicitly:

```kotlin
import foundation.url.protocol.Libp2pNetworkProvider
import foundation.url.protocol.UrlProtocol

val network = Libp2pNetworkProvider(keySeed = "stable-node-identity")
val protocol = UrlProtocol(network = network)

// UrlProtocol owns the supplied provider and closes it when the protocol is closed.
protocol.close()
```

`UrlProtocol.close()` is terminal and idempotent: it rejects new stream opens and service sends. A dial
that finishes after close is closed without being returned to its caller. External callers
wait for transport cleanup and all active service send attempts, with the same cleanup failure
reported to ordinary closers. A send issued after terminal close is rejected before transport.
An already-running send may still finish and return its result. A service send fails with
`Cannot send service RPC request to '<url>' method '<method>': UrlProtocol is closed.` in exactly two
cases, decided from the protocol's closed state and the typed transport failure, never from message text:
it is rejected at an admission check because close had begun (no cause), including network startup
before dialing, or an attempt ends with a failure that would otherwise replace the stream (a `REQUEST_WRITE` or `CONNECTION_CLOSED`
`RpcTransportFailure`, or an exception that left the stream closed) and close has begun when the
protocol decides not to send again. In that second case the cause is the transport failure's cause or
the thrown exception. This rule covers both the initial and replacement attempts. Direct
`openStream()` admission still reports `Cannot open stream: UrlProtocol is closed.`. An exception that
leaves the stream open propagates unchanged, and a service error or another transport failure is
reported as it is. A caller's own interruption keeps the same exception or RPC error and interrupt
flag on either attempt; it is never converted to the close failure or retried.
A send that calls `close()` excludes itself from the drain, and a sending
caller joining an existing close waits only for cleanup so concurrent senders cannot wait on each other.
Nested close calls from the cleanup owner, an owned stream cleanup callback, or a relay receive callback
being joined return before cleanup finishes and do not observe its final failure. External closers still wait for that callback
and the remaining cleanup and sends. If a relay callback initiates close, teardown can interrupt
that same callback while it is still running. After that self-close detaches the relay service,
a repeated close cannot join the callback's remaining work.
Frame admission is terminal too: closing a stream releases queued senders with a stream-closed
failure before they enter the supplied writer. When a libp2p child stream ends because its transport
failed, those senders instead fail with `Stream closed: transport failed: <message>` whose cause is
that transport failure, the same failure timed readers report. The interrupt a close uses to
release a waiting sender is consumed before that sender's stream-closed failure is thrown, so the
sender's thread is not left interrupted. `RpcStream.output` on the TCP and libp2p streams is a
wrapper: each call is admitted as one frame write, like `sendRequest`, and closing it closes the
stream. Synchronizing on the returned output
object does not exclude `sendRequest` frames; write a whole frame in one call instead. A compatibility controller's output getter is
captured once before admission, and its getter, writes, and flushes can call protocol close.
Controller lookup uses identity without calling the controller's `hashCode` or `equals`.
Raw complete frames and segmented RPC frames share one writer at a time, so their bytes cannot
interleave. Waiting sends retain their attachment references without making complete frame copies.
An admitted writer still has to finish or unwind before an external protocol close finishes draining it.
Service stream acquisition and provider startup also release their state monitors before supplied
getters, dialing, listening, registration, or cleanup. Close releases callers waiting for acquisition
before it drains their active outer sends.
A stream handle obtained from `UrlProtocol` ends its ownership once, through either `close()` or
`releaseAfterForwarding()`. Concurrent callers join that cleanup, and completed handles are removed
from protocol ownership. A forwarding release preserves the response's separate transfer lifetime;
the receiving writer or response owner finishes or closes that response. Automatic protocol cleanup
joins an in-progress release without cancelling its response. An explicit `close()` on the released
handle still cancels the live response, with concurrent callers sharing that cancellation's result.
An explicit close after a failed release also attempts that one cancellation in case the response
remains live. The original release failure is thrown, with a distinct cancellation failure attached
as suppressed, so callers can identify why the release failed and whether cancellation also failed.
If the release callback calls `close()` on its own handle, that close returns to the callback and
the release caller completes the requested cancellation before returning. This applies when the
release succeeds or fails, and uses the same failure order.
Concurrent closers share this result. Releasing after close does not repeat cleanup.

For `NettyStreamController.output`, a raw `write` may return after transport admission.
`flush()` waits for the outstanding raw transport writes and reports their first failure as
an `IOException`; a later successful write cannot hide an earlier failure. `close()` stops
new admissions, cancels writes awaiting submission and closes the child without joining transport
completion. A cancelled writer receives a stream-closed failure before it can submit bytes.
Unfinished submitted writes report the close failure to pending and later flush calls.
Close still reports a write failure
that occurred before it began; its own cancellation is a normal, repeatable close.
Call `flush()` before closing when transport completion is required.
This lets caller-deadline cleanup release writers before retained-resource cleanup.
An admitted write may progress beyond the five-second admission wait. Pending raw writes
share the existing 10-second attachment stall interval. The controller fails its pending
writes with one cause and closes its child only after both the stream and its physical
connection have made no qualifying progress for that interval. Stream progress includes
socket bytes accepted for that child and completed data-frame or raw writes. On Yamux,
positive receive-credit grants from the peer on any child of that same connection also
count as progress; on Mplex, physical socket write progress counts. Activity on another
connection, new admissions and repeated flush calls cannot renew the interval. Completion
cancels the controller's timer. A peer continually granting credit to siblings on the same
connection keeps a child waiting for credit; this rule does not impose a separate deadline
for each child. The caller's earlier deadline or interruption still applies,
and transport closure ends a pending wait. Each failed call receives its own
IOException with the original cause, so Java cleanup can attach a distinct suppressed error.
A flush or close that would wait for pending writes on any Netty event loop throws an `IOException`
instead of blocking that loop; perform those synchronous calls from a worker thread.


Close runs every cleanup step, then throws the first cleanup failure with later failures attached
as suppressed exceptions exactly once. If an owned
stream's cleanup callback initiates protocol close and then fails, external protocol closers wait for
that callback and receive its failure too. A stream cleanup that completed before protocol close
continues to report its failure only to its own caller.

Alternative network implementations implement `NetworkProvider`, whose type-neutral API covers
identity derivation, listening, timed dialing of an `RpcStream`, inbound protocol handlers, and peer
connection lifecycle events. The interface deliberately exposes no `io.libp2p.*` types, but its
string values are provider-interpreted rather than universal: identities and addresses are opaque to
`UrlProtocol`; `Libp2pNetworkProvider` interprets them as base58 peer IDs and libp2p multiaddresses.
Some existing `UrlProtocol` routing filters remain explicitly libp2p-multiaddress-specific while the
addressing model is migrated incrementally. Relay service and LAN discovery require
`Libp2pNetworkProvider` and fail loudly when enabled with an incompatible provider.

Accepted inbound streams wait independently for their frames. An idle stream cannot keep a later
accepted stream from reaching its registered handler. Closing the provider closes its transports
and releases accepted stream readers, including when a handler closes the provider itself.

A provider can declare its gossip format rule with `GossipInputStream(input, validator)`.
The validator receives the decoded message before application admission and can throw a
descriptive exception to refuse it. Ordinary provider and stream delegation preserves the
libp2p input's rule. An adapter that deliberately replaces libp2p identities with opaque names
can explicitly choose the default accept-any policy on its returned streams:

```kotlin
import foundation.url.protocol.GossipInputStream
import foundation.url.protocol.RpcStream

fun withOpaqueGossipNames(stream: RpcStream): RpcStream = object : RpcStream by stream {
    override val input = GossipInputStream(stream.input)
}
```

Wrapping a gossip input replaces its format rule; it does not apply the wrapped input's
validator again. Plain inputs keep the generic decoder's opaque-name behavior. No method was
added to `NetworkProvider` or `RpcStream`, so existing compiled implementations keep working.

#### Installing a direct libp2p RPC binding

Advanced callers that install `UnifiedRpcProtocolBinding` into their own libp2p `Host` must attach
the binding to the host lifecycle and use the returned host for startup and shutdown. This gives
PeerExchange requests one aggregate admission budget: each parsed request carries a conservative,
heap-scaled retained-byte charge across the existing 64 running and 64 pending count limits. A
request that cannot fit the byte or count budget is refused with a warning and its stream is
closed. The lifecycle wrapper also makes host shutdown wait for admitted request work:

```kotlin
val binding = UnifiedRpcProtocolBinding(requestHandler, gossipHandler)
val rawHost = buildHostWith(binding) // your libp2p Host builder
val host = binding.withHostLifecycle(rawHost)
host.start()
// ...
host.stop()
```

The underlying libp2p `Host` API does not expose a protocol-lifecycle callback, so stopping the
unwrapped `rawHost` cannot drain the binding's PeerExchange work.

#### In-memory network provider

`InMemoryNetworkFabric` runs any number of `UrlProtocol` nodes inside one JVM without opening real
sockets. Each `InMemoryNetworkProvider` derives a stable, non-cryptographic peer ID from its seed.
Addresses are opaque `memory://` strings understood by the fabric. Every stream direction has a
bounded byte buffer (4 KiB by default), so writes block under backpressure and fail at the
clock-driven write deadline instead of accumulating an unbounded payload. Dials blocked by a
non-dialable target or fabric partition likewise fail at their caller-supplied, clock-driven
deadline with the source peer, target peer, address, protocol, and timeout in the error.

Closed streams return their empty backing arrays to a fabric-local pool for later streams. The
pool retains at most 256 arrays, so the default fabric keeps at most 1 MiB of reusable pipe storage
while sequential request/response traffic avoids allocating two new 4 KiB arrays per stream.
`pipeBufferAllocationCount` reports how many backing arrays the fabric has actually allocated.

```kotlin
import community.kotlin.clocks.simple.ManualClock
import foundation.url.protocol.InMemoryNetworkFabric
import foundation.url.protocol.InMemoryNetworkProvider
import foundation.url.protocol.LanDiscoveryConfig
import foundation.url.protocol.Libp2pPeer
import foundation.url.protocol.RelayConfig
import foundation.url.protocol.UrlProtocol

val clock = ManualClock(1_000L)
val fabric = InMemoryNetworkFabric(clock = clock, bufferCapacityBytes = 4 * 1024)
val providerA = InMemoryNetworkProvider(fabric, keySeed = "node-a")
val providerB = InMemoryNetworkProvider(fabric, keySeed = "node-b")
val relay = RelayConfig(enableRelay = false)
val lan = LanDiscoveryConfig(enabled = false)
val nodeA = UrlProtocol(providerA, relay, lan, clock)
val nodeB = UrlProtocol(providerB, relay, lan, clock)

val addressA = nodeA.startNetwork()
val addressB = nodeB.startNetwork()
nodeA.addBootstrapPeer(Libp2pPeer.remote(addressB.peerId, addressB.listenAddresses))
nodeB.addBootstrapPeer(Libp2pPeer.remote(addressA.peerId, addressA.listenAddresses))

nodeA.announceService("example.service")
fabric.runUntilIdle()
check(nodeB.findPeersForService("example.service").single().peerId == addressA.peerId)

nodeB.close()
nodeA.close()
fabric.close()
```

Test harnesses can call `stallReader(nodeId)`, `slowReader(nodeId, bytesPerTick)`,
`resumeReader(nodeId)`, `kill(nodeId)`, `partition(nodeA, nodeB)`, `heal(nodeA, nodeB)`,
`setDialable(nodeId, false)`, `addAddressAlias(nodeId, address)`, and
`setAddressDialable(nodeId, address, false)` to reproduce slow consumers, abrupt loss, network
splits, whole-peer dial failure, and one stale address on a multi-address peer. Inbound handlers are
created through a configurable thread factory; hermetic failure-path tests can call
`configureInboundHandlerThreadFactory` before the first dial to make worker startup fail at an exact
launch. A failed worker start commits no connection, handler sequence, or success counter.
Successful inbound handlers are launched in deterministic FIFO order. The handler launch and stream
open become visible in one locked state change, while the virtual worker body begins asynchronously.
Inbound handlers cooperatively hand off the fabric turn when blocked, allowing a healthy reader to
drain frames larger than one bounded pipe while the writer remains backpressured. `runUntilIdle()` single-flights concurrent drains and waits for all
scheduled handlers and lifecycle callbacks; advance the injected `ManualClock` to release
slow-reader ticks and write deadlines. Closing either `RpcStream` endpoint (including its input or
output view) immediately makes both endpoint views unusable while preserving already-buffered bytes
for graceful input drain.

Impaired scenarios can use `drainReadyWork()` instead of polling. It runs all work that is ready at
the current injected-clock time and returns either `FabricDrainResult.Idle` or
`FabricDrainResult.WaitingForClock(nextDeadlineMillis)`, so the caller can advance a `ManualClock`
exactly to the next fabric-owned dial, write, or slow-reader deadline. `snapshot()` returns an
immutable `InMemoryNetworkSnapshot` built from maintained counters: node states, active and peak
streams, current buffered bytes, accepted bytes and opens by protocol/node/directed link, dial and
handler totals, the peak pending-handler count, and current pending callback/handler counts.
Snapshot reads do not run callbacks, inspect payloads, drain buffers, or advance time.

### Opening an RPC Stream
            path: String,
            params: Map<String, Any?>,
            metadata: Map<String, String>
        ): Any? {
            return when (path) {
                "status" -> mapOf("online" to true)
                "data" -> fetchData(params["id"] as? String)
                else -> null
            }
        }
    }
)

// Later, to unregister:
registration.unregister()
```

`unregister()` marks a registration inactive once. It then attempts handler shutdown, a withdrawal
broadcast for a global service when a timestamp was reserved, and local registry cleanup. A failure
while reserving the timestamp or in any cleanup step does not skip the later cleanup steps. After
all attempts, it rethrows the first failure and attaches later distinct failures as suppressed
exceptions. Concurrent and reentrant calls do not repeat cleanup. Local-only registrations do not
reserve withdrawal timestamps or broadcast withdrawals.

Registration lifecycle operations read the supplied clock and lifecycle-history callback outside
their monitors. Unregister marks the handle inactive before those reads, so reentry returns without
repeating cleanup. An announcement waiting on either read rechecks the active state before reserving
its timestamp and cannot publish after an unregister that completed during the read.

### Low-Level API (UrlProtocol)

### Opening an RPC Stream

> **Note:** The examples below show explicit peer IDs and multiaddresses for educational purposes. In practice, you almost never need to specify these — just use the default constructor and provide the `url://` URL. The protocol handles peer discovery automatically.

```kotlin
import foundation.url.protocol.*

// Recommended: Use default bootstrap peers (no peer IDs or multiaddresses needed)
val protocol = UrlProtocol()
val stream = protocol.openStream("url://my.service/")

// Advanced: Create protocol with custom bootstrap peers (rarely needed)
val bootstrapPeers = listOf(
    Libp2pPeer.remote(
        peerId = "QmBootstrapPeerId",
        multiaddresses = listOf("/ip4/192.168.1.1/tcp/4001/p2p/QmBootstrapPeerId"),
        advertisedServices = listOf("my.service")
    )
)
val protocol = UrlProtocol(bootstrapPeers)

// Open a raw RPC stream
val stream = protocol.openStream("url://my.service/")

// Send RPC request
val request = Libp2pRpcProtocol.RpcRequest(
    id = Libp2pRpcProtocol.nextRequestId(),
    method = "myMethod",
    params = mapOf("key" to "value")
)
val response = stream.sendRequest(request)

// Handle response
if (response.error != null) {
    println("Error: ${response.error.code} - ${response.error.message}")
} else {
    println("Result: ${response.result}")
}

// Clean up
stream.close()
protocol.close()
```

#### Choosing who reads replies

Existing constructors, `NetworkProvider.dial`, `UrlProtocol.openStream`, and
`openStreamToPeer` retain their default reply handling. Libp2p RPC and TCP streams start an
automatic reader for `sendRequest`. A caller that dispatches replies or unsolicited messages
itself must select `RpcResponseReader.CALLER` when opening the stream. This choice is made
before construction starts a reader; it cannot be changed after the stream is returned.

```kotlin
import foundation.url.protocol.Libp2pRpcProtocol
import foundation.url.protocol.RpcResponseReader
import foundation.url.protocol.UrlProtocol
import foundation.url.protocol.requireCallerResponseReader

val protocol = UrlProtocol()
try {
    protocol.openStream("url://my.service/", responseReader = RpcResponseReader.CALLER).use { stream ->
        stream.requireCallerResponseReader()
        val request = Libp2pRpcProtocol.RpcRequest("example", "status", emptyMap())
        Libp2pRpcProtocol.writeMessage(stream.output, request.toJson())
        val response = Libp2pRpcProtocol.RpcResponse.fromJson(
            Libp2pRpcProtocol.readMessage(stream.input)
        )
        println(response.result)
    }
} finally {
    protocol.close()
}
```

The same `responseReader` choice is available on direct `Libp2pNetworkProvider.dial`, both
`openStreamToPeer` overloads, direct `Libp2pRpcStream` and `TcpRpcStream` construction, and
`TcpRpcStream.connect`. Caller mode performs no input read before the caller's first read.
Its `sendRequest` convenience methods refuse before writing because the caller owns reply
correlation. Successful transport cleanup happens once; caller mode has no automatic reader
to stop. If a TCP socket close fails, the failure is reported and a later close can retry physical
cleanup. Overlapping closers wait for an in-progress socket close and retry if it failed.
The stream remains closed to new requests during that retry. Inbound provider streams
already belong to their registered handler.

`RpcStream.ownsResponseReader` reports the stream's choice, including through protocol wrappers.
`requireCallerResponseReader()` rejects a default libp2p or TCP stream with this full message
(using the stream's service identifier):

```text
Cannot read replies directly from RPC stream 'my.service': the stream owns an automatic response reader. Open the stream with RpcResponseReader.CALLER before reading.
```

Custom streams report ownership through the optional `RpcResponseReaderOwnership` interface.
Older streams without that capability are treated conservatively and the guard refuses their
unknown ownership. Custom network providers support caller mode through the optional
`RpcResponseReaderNetworkProvider` capability; the public `NetworkProvider.dial` overload
refuses unsupported caller mode before dialing, and preserves the old dial call for automatic
mode. `InMemoryNetworkProvider` has no background reader in its legacy mode and supports the
explicit caller mode for direct input/output use.
For a `NetworkProvider`-typed variable, import `foundation.url.protocol.dial` to use the
caller-mode overload. Import `foundation.url.protocol.ownsResponseReader` when reading the
ownership property through the `RpcStream` interface.

[`foundation.url:resolver`](https://github.com/CodexCoder21Organization/UrlResolver) persistent
connections will use this choice before starting their own response dispatcher. A reader that
has already begun consuming input cannot safely transfer ownership to another reader.

#### Opening a Stream to One Exact Peer

Code that already resolved a concrete provider can bypass service discovery and dial only that
peer's advertised addresses:

```kotlin
val stream = protocol.openStreamToPeer(
    peerId = provider.peerId,
    addresses = provider.multiaddresses,
    serviceIdentifier = "my.service",
    resourcePath = "status",
    connectionTimeoutMs = 5_000
)
```

The addresses are raced within one overall connection budget. A stale first address therefore does
not receive its own full timeout before another supplied address is attempted, and a failed call
names the requested peer, address list, method path, and budget. Reconnect code should call the same
API again with its explicit budget; this method never falls back to service discovery.

### Direct Stream I/O

Streams opened with the default settings use an automatic response reader. Send requests with
`sendRequest` so that reader can match each reply to its request:

```kotlin
val stream = protocol.openStream("url://my.service/")

// Create the request
val request = Libp2pRpcProtocol.RpcRequest(
    id = Libp2pRpcProtocol.nextRequestId(),
    method = "lowLevelMethod",
    params = emptyMap()
)
// Send the request and receive its reply through the automatic reader
val response = stream.sendRequest(request)

stream.close()
```

For caller-owned direct reads, see ["Choosing who reads replies"](https://github.com/CodexCoder21Organization/UrlProtocol/blob/c5edef610b9b75dde8733d9864dbab816516544a/README.md#choosing-who-reads-replies) in the reader ownership update.

Applications that share a memory budget between UrlProtocol and other components can pass an
`RpcStreamRetainedResourceScope` when they construct a `Libp2pRpcStream`. The scope returns one
`AutoCloseable` reservation for each retained interleaved response and compact frame. UrlProtocol
closes those reservations during normal completion or stream shutdown, after it has attempted to
release its own process-wide reservation. A reservation close failure is reported through the
stream's normal cleanup path without skipping the remaining reservations.

Applications that need separate accounting domains for independent stream groups can construct an
`RpcStreamMemoryBudgets` and pass it to each group's `Libp2pRpcStream` instances. Omitting that
parameter preserves the default process-wide shared frame and interleaved-response budget.

#### Request and Response Metadata

Both `RpcRequest` and `RpcResponse` carry an optional, additive `metadata: Map<String, String>` — a flat, generic string map for piggybacking out-of-band information alongside the method parameters or result without changing their shape:

```kotlin
// A server stamps extra information onto its response without touching the result.
val response = Libp2pRpcProtocol.RpcResponse
    .success(request.id, mapOf("value" to 42))
    .copy(metadata = mapOf("clock-timestamp" to "nodeA@42"))

// The client reads it back after deserialization.
val stamp = Libp2pRpcProtocol.RpcResponse.fromJson(responseJson).metadata["clock-timestamp"]
```

The channel is fully backward compatible:

- **Omitted when empty (`RpcResponse` only).** `RpcResponse.toJson()` writes the `"metadata"` field only when the map is non-empty, so the wire form of a response with no metadata is byte-identical to the pre-metadata format. (`RpcRequest.toJson()` differs: it always emits a `"metadata"` object, even an empty `{}`.)
- **Absence tolerated.** `fromJson()` / `fromJsonRaw()` decode a missing `"metadata"` field to an empty map, so a new reader parses an old writer's response, and an old reader simply ignores the unknown field on a new writer's response — no protocol-version negotiation is required.
- **Preserved across relays.** A relay carries the response as an opaque payload inside its `RELAY_RESPONSE` envelope, so metadata survives multi-hop relay forwarding intact.

`UrlResolver` uses this response channel to transport HierarchicalClock information — a serving clock stamps `clock-timestamp` and ships coverage receipts as `clock-receipts` — but the field itself imposes no schema on the keys or values.

### Remote AiCliPty Quota Reporting

`RemoteAiCliPty` mirrors the AI CLI PTY quota API without depending on the upstream
`aiCliPtyApi` module. Call `quotaStatus()` to fetch a `RemoteAiCliQuotaStatus`
containing supported state, fetched timestamp, raw text or error details, and any
reported quota metrics. Metrics use `RemoteAiCliQuotaMetric` with a stable id,
display name, quota window, optional model, unit, total/used/remaining quantities,
and optional reset information.

Providers expose the same call through `AiCliPtyAdapter.quotaStatus()`. Adapters
that do not implement quota reporting remain source-compatible and return
`supported = false` with a descriptive error message. The remote client tolerates
older or newer wire payloads: missing or JSON-null fields decode to null/defaults,
unknown quota window names decode to `RemoteAiCliQuotaWindow.UNKNOWN`, and closed
or failing remote connections return an unsupported status instead of throwing.

### Peer Management

```kotlin
val protocol = UrlProtocol()

// Add peers manually (optional - default bootstrap peers are added automatically)
protocol.addBootstrapPeer(peer)

// Find peers for a specific service (peer discovery happens automatically)
val peers = protocol.findPeersForService("my.service")

// Get peer count
println("Known peers: ${protocol.peerCount}")
```

Peer reputation is computed from bounded observation records rather than elapsed-time
windows. Each peer retains at most 64 checks, 64 direct/relay reach observations,
64 authenticated interaction severities, and 64 deduplicated recommendations.
Idle time does not change a score.

Recording evidence for one peer does not wait for another peer's history evaluation.
A mutation tracked by a registry publishes its observation snapshot and capacity score together.
Standalone observation updates avoid per-mutation registry scoring; the first registry
admission prepares retained history before capacity selection, while other peers can
continue recording evidence. Removing the last member stops per-mutation score preparation.
Removing and recreating an observation starts a fresh record; an older in-flight
writer cannot change that replacement's evidence or capacity score.

`PeerObservationLog.beforeScoreComputation` is an optional diagnostic callback for
observing score-history preparation. It runs on the mutating thread before the
replacement evidence is published. It may inspect public snapshots and work
diagnostics, but must not mutate observations. An observer failure is reported after the
accepted evidence, score and observation bookkeeping finish publication; it does not roll
back accepted evidence. During the first registry admission, preparation reuses unchanged
retained evidence and completes the accepted membership and capacity index before reporting
an observer failure.

```kotlin
val observations = PeerObservationLog()
observations.observePeer("12D3KooW...")
observations.recordSuccess("12D3KooW...")
observations.recordDirectReachability("12D3KooW...")
observations.recordAuthenticatedInteraction(
    "12D3KooW...",
    PeerInteractionSeverity.CLEAN
)

val scores = observations.scoreAllComponents()
val peerScore = scores.getValue("12D3KooW...")
println("bootstrap=${peerScore.bootstrapScore}, ostracism=${peerScore.ostracismScore}")
```

`bootstrapScore` favors witnessed tenure and direct reachability.
`ostracismScore` favors reliability and recommendation quality. Both multiply by
the recoverable spam factor. Failed checks are omitted entirely while this node
cannot confirm its own connectivity, and there are no score or calendar thresholds
that permanently remove a peer.

Health checks use `5 minutes × 2^min(consecutiveFailures, 10)` with a random
`0.8..1.2` jitter applied before the six-hour cap. Tests can inject a
deterministic `jitterFactor` when constructing `PeerPruner`.

#### Learned addresses, dial failures, and quarantine

Multiaddresses that arrive from PeerExchange, `PeerAnnouncement`, or
`ServiceAnnouncement` are third-party assertions, and every address the registry
retains later becomes an outbound dial target. `PeerRegistry.addPeer` therefore
drops addresses that can never identify a reachable TCP peer — a `tcp` port that
is not a number or cannot exist, IPv4 `0.0.0.0/8`, `169.254.0.0/16`, `224.0.0.0/4`,
`240.0.0.0/4`, IPv6 `::` and `ff00::/8`, and multiaddresses the transport cannot
parse. Relay addresses containing more than one actual `/p2p-circuit/` protocol component are
also rejected with a `PeerMultiaddressRejectedWarningEffect` that names the address. Protocol
values and opaque paths containing the text `p2p-circuit` do not count as circuit components.
The same check runs before relay-owned gossip fan-out and cached-announcement replay. A peer whose
every advertised address is rejected is not retained at all.
Loopback and private ranges are accepted: co-located mesh processes and peers on
a shared private network legitimately use them.

Callers report dial outcomes so a departed host stops being dialed:

```kotlin
val registry = PeerRegistry.inMemory()

try {
    // ... dial the peer ...
    registry.recordDialSuccess(peerId)
} catch (failure: Exception) {
    // Evicts and quarantines the peer once PeerRegistry.MAX_CONSECUTIVE_DIAL_FAILURES
    // consecutive failures accumulate with no intervening success.
    val evicted = registry.recordDialFailure(peerId)
}

println("quarantined peers: ${registry.quarantinedPeerCount}")
```

While this node has recent successful contact evidence, each accepted dial failure for a retained
peer adds one failed check to the same bounded, rank-decayed score history as successful dials. This lets
capacity eviction prefer a failing peer over a healthy peer before quarantine is reached.

The registry retains at most `PeerRegistry.MAX_QUARANTINED_PEERS` quarantine entries. If peer
identity churn exceeds that bound within the 15-minute window, it discards the oldest quarantine
entries first while continuing to quarantine the most recent failures.

An evicted peer is refused re-entry for `PeerRegistry.DIAL_FAILURE_QUARANTINE_MS`
however often the rest of the mesh re-advertises it, and `isQuarantined` reports
whether that window is still open. Without the quarantine, the next gossip message
restores the peer within seconds and the eviction removes no dials at all. A
recovered host rejoins on its own when the window lapses.

#### Outbound dial bound

Every outbound dial holds a socket until it connects or the kernel abandons the
unanswered SYN, so dialing is bounded process-wide at
`Libp2pNetworkProvider.MAX_CONCURRENT_OUTBOUND_DIALS` across all provider
instances. A dial that cannot be admitted within its own timeout budget fails with
`OutboundDialAdmissionException`, which best-effort callers such as gossip fan-out
handle like any other dial failure. `Libp2pNetworkProvider.pendingOutboundDialCount()`
reports how many dials currently hold a permit; a value pinned at the bound means
dials are being issued faster than they complete.

> **Note:** Peer discovery happens automatically in the background. When you call
> `openStream()` or `findPeersForService()`, the protocol will automatically join the
> network and wait for peer discovery to complete if it hasn't already.

### LAN Peer Discovery

`UrlProtocol` enables zero-config LAN discovery by default. Each node announces its peer
ID and listen multiaddresses on qualifying local interfaces, then connects to discovered
peers through the normal identity-pinned libp2p dial path. Service lists are not included
in LAN beacons; existing peer exchange and gossip carry service information after the
first connection.

```kotlin
// Default: LAN discovery enabled.
val protocol = UrlProtocol()

// Opt out, or confine discovery to selected interfaces/CIDR ranges.
val localOnly = UrlProtocol(
    lanDiscoveryConfig = LanDiscoveryConfig(
        enabled = true,
        mdnsEnabled = true,
        interfaceAllowlist = setOf("lo"),
        addressCidrsAllowlist = setOf("127.0.0.0/8")
    )
)

val noLanDiscovery = UrlProtocol(
    lanDiscoveryConfig = LanDiscoveryConfig(enabled = false)
)
```

`LanDiscoveryConfig` also exposes the service tag, announce/query interval, multicast UDP
port, multicast group, interface/address allowlists and denylists, and dial-abuse guards
for beacon-triggered connections. Set `mdnsEnabled = false` to keep the project-local UDP
beacon path while disabling mDNS binding; this is useful for hermetic tests that use
per-test UDP ports and must avoid cross-talk with host mDNS on port 5353. Discovery
startup is best-effort: if multicast or mDNS cannot start on a host/interface, `UrlProtocol`
continues without LAN discovery.

### Hierarchical Name Verification

Service gossip is treated as a reachability hint. `UrlProtocol` can also verify
service ownership through signed hierarchical name descriptors before routing to
an announcing peer. Existing namespaces remain free-to-claim by default for
backward compatibility; verified zones opt in by providing a signed TLD
descriptor and child delegation descriptors.

Rollout is intentionally an islands-of-trust model. If a resolver has not yet
seen a valid root-signed descriptor for a TLD, that TLD is treated as
free-to-claim so legacy services continue to work. Once a resolver sees a valid
`VERIFIED` TLD descriptor, it pins that TLD in memory and, when `UrlProtocol` is
created with a peer persistence directory, persists the pin in that same
directory. A pinned verified TLD cannot later downgrade to absent or
`FREE_TO_CLAIM`; it can only be replaced by a root-signed descriptor with a
strictly higher signed version. Operators that need to close the
pre-propagation window for reserved names should ship those descriptors through
`SignedDelegationResolver(hardCodedPinnedTldDescriptors = ...)` so the TLD is
verified before gossip arrives.

Names are authorized in their canonical spelling only. A canonical identifier is
a dot-separated series of segments that are non-empty, at most 63 characters, and
lowercase, with no whitespace and no empty segment. An identifier that names a
known TLD but is spelled non-canonically — `secure..service`, `SECURE.service`,
or `secure .service` — is rejected rather than normalized into `secure.service`,
and is rejected rather than being treated as naming some other, unknown TLD that
would be free to claim. Identifiers under a TLD that has no descriptor stay
free-to-claim whatever their spelling, so legacy names are unaffected.

```kotlin
import foundation.url.protocol.*

val rootKey = HierarchicalNameKeys.keyPair("local-root")
val tldKey = HierarchicalNameKeys.keyPair("local-secure-zone")
val providerPeerId = UrlProtocol.peerIdFromSeed("provider")

val resolver = SignedDelegationResolver(
    rootPublicKey = rootKey.publicKeyBase64,
    tldDescriptors = listOf(
        SignedTldDescriptor.create(
            tld = "secure",
            mode = NameDelegationMode.VERIFIED,
            childAuthorityPublicKeys = listOf(tldKey.publicKeyBase64),
            signer = rootKey
        )
    ),
    delegationDescriptors = listOf(
        SignedNameDelegationDescriptor.create(
            parentName = "secure",
            childSegment = "service",
            mode = NameDelegationMode.VERIFIED,
            authorizedPeerIds = listOf(providerPeerId),
            signer = tldKey
        )
    )
)

val protocol = UrlProtocol(
    nameResolutionConfig = NameResolutionConfig(
        rootPublicKey = rootKey.publicKeyBase64,
        resolver = resolver
    )
)
```

TLD resolver descriptors can be distributed through gossip with
`GossipProtocol.TldResolverAnnouncement`. A node verifies each TLD descriptor
against its configured root public key before storing it; descriptors that fail
verification are dropped and are not returned by `onTldResolverQuery()` for
re-propagation.

### Reading peer removals

`PeerRegistry.getPeerRemovalsSince(cursor)` returns committed removals in order, with each
peer ID, lifetime generation and sequence number. Start a cursor at zero, process the returned
batch and retain its `cursor` for the next read. Batches are detached and immutable; reads
register no callbacks and never access storage. Independent readers keep their own cursors.

The registry retains the latest 64 removals. If the cursor has fallen behind that window,
`overflowed` is true and `removals` is empty. Reconcile current membership once, then continue
from the returned cursor. A notice describes an ended lifetime, so check current membership
before discarding state that could belong to a replacement. Cursors belong to one registry
instance and must start over after a registry restart.

### Peer Persistence

The caller-supplied `Clock` is never sampled while any registry guard is held. A `PeerLifetime.runIfActive` action prepares its clock sample before entering its monitor; nested registry, freshness-window and observation calls using the same clock reuse that sample. Default `SystemClock` instances share this action sample. An operation using a different clock must run after the active action returns and reports an `IllegalStateException` if called inside it. Clock changes during an action are sampled by the next operation outside it. A lifetime action that joins an earlier monitor owner discards its earlier sample and prepares time again outside registry guards before admission. A Clock callback entering a lifetime action takes a fresh source sample before admission, even when the monitor is available immediately. A source read may re-enter a Clock through a finite chain of callbacks; each callback action takes a fresh source sample before admission. Completed reads retain their returned values, so a later time returned after a callback advances the timeline. The Clock is responsible for its own callback chain terminating. A storage callback cannot join a busy lifetime monitor, persistence owner, or unfinished peer-removal callback handoff while persistence ownership is held: it reports a descriptive IllegalStateException before waiting, and the caller must run that operation after persistence returns. Persistence prepares time again after joining a busy writer. Record writes for a replacement lifetime can proceed while an earlier lifetime is in a storage callback; when overlapping writes finish, the persisted record reflects the latest accepted record or removal. Freshness-history snapshots and writes remain ordered independently, so primary record publication does not wait for an earlier record through the history owner. Synchronous storage callbacks also reuse that prepared sample for nested registry, freshness-window and observation calls with the same Clock identity. A storage callback using a different Clock reports an `IllegalStateException` before reading it; run that operation after persistence returns. Both successful and exceptional storage exits release the prepared-sample scope.

```kotlin
// Create with persistence directory
val protocol = UrlProtocol(peersDirectory = "/path/to/peers")

// Peers are automatically loaded on construction
// and saved when calling savePeers()
protocol.savePeers()
```

Persistence version 2 keeps each peer header, bounded score observations,
direct-reachability timestamp, and registry discovery timestamp in that peer's
`<peer-id>.json` file. Updates are written to a temporary file in the same
directory and atomically renamed over the previous record.

On the first startup over version 1 data, `PeerRegistry` folds
`observation-log.json`, `direct-reachability.json`, and `first-seen-at.json`
into the matching per-peer files, removes the old shared files, and then writes
`migration-v2-complete`. Failed cleanup is retried on the next startup, so the
migration is idempotent. A malformed peer record is
reported through `PeerRegistry.getPersistenceFailures()` as a
`PeerPersistenceException` and emitted as a `PeerPersistenceWarningEffect`
without preventing valid sibling records from loading. Write failures throw
`PeerPersistenceException` to the caller.

When overlapping peer storage operations report multiple errors, the first original
`Error` propagates to the caller. If no `Error` occurs, the first original `Exception`
stays primary. An import never converts an `Error` into an import count. Each later
distinct report is attached to that original at most once per operation and target,
including storage callbacks that enter another registry and then return to the first.
Separate completed operations may attach that pair again. This does not read caller
diagnostic accessors or traverse caller graphs. Later storage
issues are also available through `PeerRegistry.getPersistenceFailures()` and are
emitted as `PeerPersistenceWarningEffect`. If a warning handler raises an issue, the
registry records it with peer and file context without invoking that handler again.
Warnings run after the current thread releases its registry persistence ownership and
lifetime monitors and prepared action Clock scopes, and after every removal handoff in
the current batch completes. A warning handler can add the removed peer again without
waiting for the operation delivering that warning. These delivery boundaries apply per
registry: an independent registry constructed inside a storage callback delivers its
startup warnings during its own construction, using its caller's handlers. If those
handlers raise errors, the errors remain pending until the enclosing registry finishes
its owned work, preserving its storage result and error ordering. An add of the removed
peer on another thread remains ordered after removal and waits until that removal's
storage callback returns; the callback must return without waiting for that add.
Each queued warning uses the handlers in scope when it was queued,
and every queued warning is delivered once even when another handler throws. A handler
exception never replaces an original storage `Error` already returning to the caller.
A warning-handler `Error` also reaches the caller as the same instance when earlier
storage failures were only exceptions, after reconciliation and queued delivery finish.
The handler's contextual report retains the earlier primary storage failure, so it
remains available through the report API even when that handler Error disables suppression.
A promoted storage Error likewise keeps the earlier primary on its storage report before
warning delivery.
When the original disables suppression, the JVM ignores attachments; the exact
original still reaches the caller and later storage reports remain available through
the report API. Loading and migration reports remain for the registry's lifetime;
subsequent reports, including warning-handler reports, retain only the latest 64 in
oldest-first order. The fixed window bounds retention even when peer identities change,
and every runtime storage report still emits its warning effect. A temporary-file cleanup
`Error` also reports the earlier write issue through this channel, preserving that exact
cleanup `Error` even when suppression is disabled. Reusing reports can form cycles, which the JVM's standard report
printing handles as repeated references. Removal callback reports also have a
registry-owned context wrapper that retains every later callback occurrence,
including repeated originals and originals with suppression disabled. When that
wrapper remains primary, later cleanup reports are attached to it; a fatal cleanup
`Error` instead stays primary. Removal completion retains the callback wrapper and
other completion failures on a registry-owned report before composing them, so an
original Error with suppression disabled cannot discard them. This includes storage
failures raised by a nested removal callback and later victims in a capacity batch.
Removal completion and capacity publication emit one completion warning after all
handoffs finish. A nested removal failure also keeps a completion report when no later
failure occurs, including an ordinary storage exception wrapped by its callback. Imports skip entries that cannot be decoded as peers; after decoding,
admission and removal callback failures propagate to the caller. The callback wrapper
keeps the exact nested failure, and the completion report remains available with its warning.
Discovery can skip that callback wrapper after its completion report and warning retain the failure.
A direct discovery storage failure propagates as `PeerPersistenceException`; a direct storage
`Error` propagates as the original instance.

### Peer Registry Capacity

`PeerRegistry` retains at most 1,000 peers after capacity publication. Capacity
eviction has its own diversity policy; bootstrap selection keeps its existing
greedy score-and-address ordering. Eviction first considers zero-score peers
(including missing observation records), then positive peers without a successful
observation, then positive peers with a successful observation, regardless of origin.
A zero score takes priority over caller or confirmation status; its existing ordering
receives no caller-origin preference. Network refreshes preserve caller history.

Within the first nonempty category, eviction compares direct evidence first:
peers without a successful observation, then lowest exact record-local bootstrap
score, then shortest witnessed tenure. For positive-score peers with equal evidence,
a network-learned peer is evicted before one with caller-authored history. Thus a
successful network-learned provider survives a cold caller-authored contact, and
score and tenure also take priority over caller origin. At every address-group level,
these comparisons precede population. Among candidates still tied, eviction chooses
the most populated address family, then network block (IPv4 /16 or IPv6 /32), then address
(IPv4 /32 or IPv6 upper /64). Populations count all peers in that tier's group.
Each peer occupies one canonical address: the smallest numeric
IPv4 address, otherwise the smallest IPv6 upper /64, otherwise the no-address
group. Population ties choose the group with the weakest selected descendant.
Remaining candidate ties use oldest insertion or refresh, then largest peer ID.
Local scores use recorded referral
bases rather than recomputing scores across the population; time passing alone
does not expire evidence. Persistence restores membership and evidence using
the existing load order, without storing a new historical refresh sequence.
If a retained peer's observation record was removed before saving, restart
creates a cold observation for that peer, as ordinary peer acceptance does.
Its persisted caller history still applies; the live-session missing-record
category does not persist across that restart.

The capacity index maintains these choices incrementally in O(log n) work per
membership or evidence update and O(k log n) for a batch of k victims. Its retained
state is O(n) in live membership and does not accumulate removed peer history.
A newly created successful observation uses one event timestamp for discovery
and success, preventing clock-sampling latency from manufacturing score differences.

`getEvictionWorkDiagnostics()` returns cumulative candidate counts for fallback
eviction ranking, the temporary repeated-IPv4 fallback index, and peer-list
snapshot publication. Its incremental-index fields report cumulative ordered
comparisons and candidate/group visits, current entry count, and current live
peer/group node count. Copies, equality, hashing and string output include all six counters.
Legacy fallback counters remain zero when those paths
are unused. `getFullSnapshotStagingMapCandidateCopies()` reports
temporary full-snapshot membership-map copies. These counters can be sampled
before and after a workload to check work without using elapsed time. Capacity
publication updates only changed peer keys using persistent indexes. Refreshes keep their
published position; removals and re-adds receive the usual membership order. A closed batch
publishes its complete removals, refreshes and additions together, and retained lists keep their
original membership and order. A single membership update uses O(log n) index work per changed
service as well as its peer and order paths; it does not read unrelated peer properties or copy
the whole registry. The snapshot-publication counter includes references copied into published
index nodes and entry records, plus service-catalog comparison parts examined for both accepted
and published snapshots, including failed accepted-state compare-and-swap attempts.
Peer objects in these lists keep their existing ownership: nested collections are not deep-copied.
Repeated `getAllPeers()` calls reuse the most recently published membership
snapshot until the registry changes. Peer, lifetime, origin and exact-service reads take an
immutable accepted-state snapshot without acquiring registry locks. As before, these keyed reads
can see an accepted pending peer before a closed capacity batch advances `getAllPeers()`.
Accepted service keys are captured at acceptance; changing a caller-owned service list does not
change the lookup index until the peer is added again. Removal callbacks captured before removal
begins run after publication, so their reads observe the completed membership transition even when
persisted cleanup fails. A callback registered after removal begins runs immediately on its registering
thread and may observe the pre-removal membership.
The registry owns an immutable snapshot of accepted multiaddresses (also exposed by removal candidates), while other nested peer collections keep their existing ownership.
For custom peers, admission also copies extra JSON metadata from `toJson()` before installing
membership. A metadata serialization failure rejects that admission without leaving sidecars.
Later serialization builds the standard peer header from public fields and the owned routes,
and returns a fresh copy of the accepted extra metadata without calling the custom serializer again.
Unrelated peer operations remain delegated. Built-in `SimulatedPeer` objects keep their concrete
identity and live simulation state while owning the same bounded routes and accepted extra JSON.
Caller route/service traversal, custom JSON capture, clock sampling and admission logging run before
the publication gate and peer-map transaction. Admission rechecks its expected peer, lifetime and
refresh revision before committing that prepared data. Caller preparation is not a completed
membership update and does not hold a closed publication batch open.
First storage and replacement retain at most `MAX_MULTIADDRESSES_PER_PEER` (20) routes,
in input order, counting duplicate entries within that prefix. A normal merge still prioritizes
incoming routes and deduplicates them before applying the same limit. Route evidence, serialization,
and IP indexes use that bounded accepted snapshot. Admission validates the exact snapshot it retains;
a route collection that changes between its admission traversals is rejected before membership is installed.
Peer-exchange admission conservatively charges requested-ID hash entries for treeified
buckets as well as ordinary buckets, including JVMs with compressed object references disabled.

Each retained peer also has one stable `PeerLifetime` token, available through
`PeerRegistry.getPeerLifetime(peerId)`. Registry refreshes keep that token; any removal—including
direct removal, pruning, dial-failure eviction, or capacity eviction—ends it, and a later re-add gets
a higher generation. `PeerLifetime.runIfActive { ... }` is ordered against removal, while
`onRemoval { ... }` callbacks registered before removal begins run synchronously once before the
same peer ID can publish its next lifetime. A callback registered after removal begins runs
immediately on its registering thread and is not joined to the in-progress handoff; it may overlap
the captured callbacks or finish after a later lifetime is published. Together these operations
let consumers record and clean peer-scoped state without a remove/re-add check-then-write race for
callbacks registered while the lifetime is active. Removal callbacks run after the registry has
released its peer, capacity, publication, and dial-outcome locks. If captured callbacks throw,
every captured callback is still invoked and the failures are reported together. If persisted
cleanup or another operation for an earlier peer in the same removal batch throws a fatal error,
later peers' captured callbacks and persisted cleanup still finish before their handoffs release
re-adds. The original fatal failure remains primary, with callback, persistence-cleanup, and handoff
failures from the rest of the batch suppressed.

`PeerRegistry.removePeer(peerId, expectedLifetime)` removes the peer only when the supplied token
is still that peer ID's current lifetime. The comparison and removal are atomic with other
same-peer registry mutations. It returns `true` after removal and the usual synchronous callback,
persistence, and membership-publication work complete; it returns `false` without changing state
when the peer is absent or has a different current lifetime. The existing unconditional
`removePeer(peerId)` operation is unchanged.

For a confirming check performed outside registry locks, capture
`PeerRegistry.getPeerRemovalCandidate(peerId)` before selecting or checking the peer, then call
`PeerRegistry.removePeer(candidate)` after a completed failed check. The final removal atomically
requires the same lifetime, route evidence revision, observation record, successful-observation event,
and bounded record-removal identity. An accepted address change or explicit caller refresh after capture
makes removal return `false`. Address comparison counts each address independent of list order.
Identical CATALOG or DISCOVERY receipts, including changed services or receipt timestamps, do not
invalidate the candidate: secondhand metadata is not liveness. A successful contact, reachability report,
or authenticated interaction after capture does invalidate it, even at equal timestamps or after clock rollback.
Recommendation contact protects the contacted recommender; it does not protect the recommended peer.
Failed checks and vouch updates on the same observation record do not invalidate the candidate.
Replacing or clearing the observation record conservatively invalidates it.
A successful observation that starts before a record clear and completes after candidate capture also
invalidates that candidate.
Rejected removal keeps membership, observations, active lifetime, and removal subscriptions intact.
An accepted removal completes callbacks and persistence outside the observation guards as usual.

An active lifetime action can nest another action for the same lifetime. It must finish before
acquiring a different lifetime through an action, removal subscription, removal or persistence.
Such a call throws `IllegalStateException` naming both peer IDs before waiting for the second
lifetime. This includes `saveAll()` when it reaches another peer and a persisted refresh of another
peer; the latter is rejected before changing membership. Saving only the action's own peer remains
supported. An already-ended lifetime still returns `false` from `runIfActive` without running it.

All registry paths follow one global guard order, skipping guards they do not need:
**lifetime → dial outcome → publication → capacity → peer stripe → peer-map bucket → observation
record → observation score**. No operation acquires a different lifecycle monitor while a lifetime
action is active. Service-index buckets and the quarantine monitor are leaf guards. The publication
coordinator is released before acquiring transaction guards or waiting for publication. Persistence
ownership follows its lifetime check, with no lifecycle wait or peer serialization under that lock;
removal joins admitted writes after releasing transaction guards.

| Operation | Guard order and decision |
|---|---|
| Admission | Prepare caller data first; publication read gate → stripe → bucket → observation score; release stripe/bucket before capacity/index publication |
| Candidate capture | Stripe → record → score; release all guards before the caller's confirming IO |
| Captured removal | Lifetime → dial outcome → capacity → stripe; validate under record/score and release them, acquire bucket, then recheck under record/score before ending the lifetime; release record/score before the map removes the entry |
| Unconditional / expected-lifetime removal | Lifetime → dial outcome → capacity → stripe → bucket; release bucket before observation cleanup |
| Observation writers / population reads | Record → score, or score alone; never acquire a peer bucket or registry stripe |

A success or record change between the first validation and the bucket-protected recheck makes
captured removal return `false`. Ending the lifetime and transferring its completion state is the
removal decision under the observation guards; the bucket-protected map update follows after they release. No peer-map
method runs inside an observation-guarded closure. Observation cleanup follows map publication;
callbacks and persistence follow release of all transaction guards.
For example, pass the completed-check function supplied by your caller:

```kotlin
import foundation.url.protocol.BootstrapPeer
import foundation.url.protocol.PeerRegistry

fun removeAfterFailedCheck(
    registry: PeerRegistry,
    peerId: String,
    completedFailedCheck: (BootstrapPeer) -> Boolean
): Boolean {
    val candidate = registry.getPeerRemovalCandidate(peerId) ?: return false
    return completedFailedCheck(candidate.peer) && registry.removePeer(candidate)
}
```

An active action must finish before it removes its own peer or adds a previously absent peer to the
registry; either same-thread operation throws `IllegalStateException` before changing registry
state because a new peer could evict the active peer at capacity. Calling `removePeer` for the
action's own peer is rejected before waiting on registry locks. Removal callbacks may make
bounded calls to the same registry, including operations on unrelated peers. If callback-context
work tries to add any peer whose prior removal callbacks are still unfinished, the add is rejected
immediately with `IllegalStateException` instead of waiting on a handoff the callback may be
responsible for finishing. A callback must not wait for work submitted to a pre-existing thread
pool that tries such a re-add, because pre-existing worker threads do not inherit the callback
context and ordinary non-callback callers intentionally wait for the ordered handoff.

A running action can read its peer or refresh an already published peer while another thread
waits to remove the action's peer. While waiting for
an active action, the registry holds no peer stripe, capacity lock, publication gate, persistence
lock, or dial-outcome stripe. Removal waits first, then checks the exact lifetime again and commits
membership removal under the registry locks. Reads never return a token whose removal has already
published, or a retained peer without its current lifetime.

An action cannot refresh a peer that is retained but not yet in `getAllPeers()`: that peer's add is
still waiting for publication and may need this very action to finish an eviction. This operation
throws `IllegalStateException`, naming the peer and its retained-but-unpublished state, before
changing it. A refresh of an already published peer remains allowed and publishes in place before
returning, preserving snapshot position and the capacity bound. It does not wait for unrelated
pending additions. Each capacity publication captures its completed pending generations and exact
eviction victims, releases the publication gate before waiting for those lifetimes, and publishes
only the captured generations. A selected victim stays active if another removal already freed the
needed capacity. Later additions retain their own ordered publication obligation.

Concurrent `saveAll()` calls serialize outside persistence ownership, then admit the owned record
only while the captured lifetime and descriptor revision remain current. A refresh during preparation
causes another preparation pass. A serializer may synchronously remove its peer; that removal makes
the later write admission a no-op. Removal joins previously admitted writes after releasing its
registry locks, then dispatches callbacks and deletes the peer file. A late write cannot recreate a
removed lifetime after restart. Storage and callback failure reporting follows the
[peer persistence rules](#peer-persistence) above.

A dial success from inside the exact active action that a threshold removal is waiting for returns
immediately. Its observation belongs to that pre-removal generation: it cannot cancel the decided
removal or quarantine, and removal clears the observation. An ordinary concurrent success waits for
the threshold membership removal without holding registry locks, then records the post-removal
outcome. The lifetime present after removal and the quarantine identity are captured at completion and
checked again when applying the success. A delayed outcome cannot clear a later generation's
quarantine or write its observations. Dial-removal
completion is signalled before callback dispatch, preserving bounded registry calls from callbacks.

### Local TCP Mode (localhost.tcp)

For container environments or local testing, you can bypass libp2p entirely by using the `localhost.tcp:PORT` domain pattern:

```kotlin
import foundation.url.protocol.*

// Client: Connect to a local TCP service
val protocol = UrlProtocol()
val stream = protocol.openStream("url://localhost.tcp:8080/")

val request = Libp2pRpcProtocol.RpcRequest(
    id = Libp2pRpcProtocol.nextRequestId(),
    method = "hello",
    params = emptyMap()
)
val response = stream.sendRequest(request)
println("Result: ${response.result}")

stream.close()
protocol.close()
```

```kotlin
// Server: Bind to a local TCP port
val protocol = UrlProtocol()

val binding = protocol.bind("url://localhost.tcp:8080/") { request, effectHandler ->
    when (request.method) {
        "hello" -> Libp2pRpcProtocol.RpcResponse.success(request.id, "Hello World")
        else -> Libp2pRpcProtocol.RpcResponse.error(
            request.id,
            Libp2pRpcProtocol.ERROR_UNKNOWN_METHOD,
            "Unknown method: ${request.method}"
        )
    }
}

// Server is now listening on port 8080
// When done:
binding.stop()
```

To let the operating system choose an available port, bind port `0` and read
`ServiceBinding.localPort`. The listener stays bound while the port is reported, so another
process cannot take it between selecting the port and starting the service. The requested
`serviceIdentifier` remains unchanged. Reading `localPort` before the listener starts or after
`stop()`/`close()` throws `IllegalStateException` with the configured port and lifecycle guidance.

```kotlin
import foundation.url.protocol.Libp2pRpcProtocol
import foundation.url.protocol.UrlProtocol

UrlProtocol().use { protocol ->
    protocol.bind("url://localhost.tcp:0/") { request, _ ->
        Libp2pRpcProtocol.RpcResponse.success(request.id, "Hello")
    }.use { binding ->
        val clientUrl = "url://localhost.tcp:${binding.localPort}/"
        protocol.openStream(clientUrl).use { client ->
            println(client.sendRequest(Libp2pRpcProtocol.RpcRequest("hello", "hello", emptyMap())).result)
        }
    }
}
```

By default, when a TCP connection ends, its `CloseAwareServiceClientConnection.closeCause` is published before
the transport interrupts request handlers owned by that connection. A blocked handler can therefore
record the connection close as the reason its work ended, while an unrelated caller-owned interrupt
remains distinguishable. `CloseAwareServiceClientConnection` is an optional capability, so code
must check the connection with `as?` before reading it. Close listeners run after handler
cancellation and are still notified exactly once; a slow listener cannot delay cancellation or
admission release, and a listener failure is reported without skipping later listeners.

Handlers that must finish accepted storage work can opt into
`TcpRequestDisconnectPolicy.FINISH`. A request is accepted for execution when a worker takes it
and confirms its connection is still open. Disconnect drops requests still waiting for a worker,
prevents further admission from that connection, and lets running handlers and their structured
coroutine work finish without transport cancellation or interruption. Each running handler keeps
its admission slot until it finishes; its reply is discarded and response resources are released.
Connection-close listeners still run promptly. Explicit pushes and effects still require a live
connection, so this policy suits work that can finish without another answer from that client.

```kotlin
import foundation.url.protocol.Libp2pRpcProtocol
import foundation.url.protocol.TcpRequestDisconnectPolicy
import foundation.url.protocol.UrlProtocol

UrlProtocol().use { protocol ->
    protocol.bind(
        "url://localhost.tcp:8080/",
        TcpRequestDisconnectPolicy.FINISH,
    ) { request, _ ->
        // Complete synchronous storage work here even if this client disconnects.
        Libp2pRpcProtocol.RpcResponse.success(request.id, "stored")
    }.use { binding ->
        // Keep the binding open for the application's lifetime.
    }
}
```

Direct users can select the same policy with
`TcpServer(8080, TcpRequestDisconnectPolicy.FINISH) { request, effects -> ... }`.
Local TCP clients and accepted server sockets enable TCP_NODELAY so segmented, length-prefixed RPC
frames do not wait for a delayed acknowledgement between writes.
Existing constructors and binding overloads keep `TcpRequestDisconnectPolicy.INTERRUPT`.
**Server stop retains its existing behavior for both policies:** it closes connections, cancels
and interrupts running work, and does not wait for application work to drain. This includes
FINISH work whose client disconnected earlier. Applications must finish their storage work
before stopping the server if that work must survive shutdown. Stop remains idempotent for its
listener generation; the existing listener restart behavior is unchanged.

Code that constructs `TcpServer` directly can pass port `0` to let the operating system choose an
ephemeral port without a probe-and-rebind gap. After `server.start()`, `server.localPort` reports
the actual bound port for clients to connect to. `stop()` closes the listener and all tracked
connections and waits for its accept loop to finish before returning to an external caller or
request handler. A fresh connection to that stopped listener is then refused. Repeated and
concurrent stop calls for the same listener wait for its completion, and caller interruption is preserved.
A stop invoked on the accept thread or from a reentrant socket/factory callback returns without
waiting for its own operation; external stop callers still wait for accept completion. Custom
socket and reader factories must return for an external stop to complete. Stop interrupts request
handlers rather than waiting for their application work to finish, so a handler can stop its own
server without waiting for itself. If closing the listener fails, stop reports that failure after
cleaning up tracked connections and retains the listener for a later explicit stop to close.
Starting again is rejected until that retained listener has been closed successfully. A later start
cannot keep an earlier listener alive or release an earlier stop before its accept has finished.

Requests on a local TCP connection execute independently. Each handler runs on its own reusable
daemon platform thread from an elastic pool, so blocking on an external dependency — including
blocking while inside a `synchronized` section — does not occupy a shared virtual-thread scheduler
slot or prevent an unrelated handler from starting. Code that constructs `TcpServer` directly can
pass port `0`, call `start()`, and then read `localPort` to use the operating system's assigned port.
Advanced direct users can pass a `TcpServerSocketFactory` together with a
`TcpServerReaderThreadFactory` when they need a custom bound-server-socket or reader-thread creation
policy; the ordinary constructors keep the default `ServerSocket` and daemon reader threads.
If the socket factory calls `stop()` before returning, `start()` closes the returned socket without
publishing it or starting an accept thread.
The pool admits up to
`TcpServer.DEFAULT_CONCURRENT_HANDLER_LIMIT` handlers by default (256, leaving ample headroom above
the 65-or-more concurrent blocking handlers supported by the earlier contract); callers can set a
different `concurrentHandlerLimit` constructor value. Requests beyond the configured ceiling are not
queued: a request beyond it receives a `CONCURRENT_HANDLER_LIMIT` RPC error explaining
the port, configured limit, request ID, and method, and the server emits a
`TcpServerConcurrentHandlerLimitEffect` for consumers that want to record the refusal. Idle request
threads are reaped after 60 seconds, and stopping the binding interrupts outstanding request work.
With the default INTERRUPT policy, ending one client connection removes its queued requests before
interrupting its running handlers, because that client can no longer receive their results. Stopping the server applies that ordering
across all connections. A handler that does not respond to interruption continues to hold its slot
until its complete invocation exits, including work moved to another coroutine dispatcher. Connection
cancellation keeps the request worker servicing its own coroutine continuations until that work and
its cleanup finish. Only then is its slot returned. Blocking cleanup from separate requests therefore
continues independently on their respective request workers, including after returning from another
dispatcher. With INTERRUPT, plain blocking waits on a request worker are still interrupted when its
connection ends, and the worker's interrupt flag is cleared before reuse. The handler ceiling remains an exact bound.
`TcpServer.activeHandlerCount` is a read-only operational metric reporting occupied admission slots, including queued handlers and unfinished cancellation cleanup.

The ceiling counts handlers that are running, which is what the refusal reports when it says a
request "was not started". A handler's slot is freed once it returns and before its response is even
handed to the connection's writer thread, so a caller holding a response is guaranteed the slot that
served it is already free. A caller that keeps at most `concurrentHandlerLimit` requests outstanding
is therefore never refused for capacity, including when it issues its next request immediately on
receiving the previous response.

### Bounded response delivery

Ordinary JSON replies are prepared once into immutable text on the handler thread: caller serializers
run exactly once, and failures surface before queueing. The job retains only that prepared text and
a copy of each attachment occurrence, taken inside the encoding lease before the reply is queued, so
changing an attachment array after its lease completes does not change the bytes a client receives.
Prepared text is stored in immutable chunks of at most 65,536 chars; String values of at least that
length are referenced without copying. Retained storage is therefore bounded by a small fixed
overhead plus a constant multiple of the charged wire size, however many writes a serializer hook
makes (empty writes retain nothing, and reading the hook writer's accumulated text retains nothing).
An exact UTF-8 length count precedes streaming to the socket through a bounded buffer. Wire bytes and
full-frame charges, including every attachment occurrence, are unchanged.
The 64 KiB buffer combines the length prefix, JSON and small attachments, so a small reply does not
wait for an acknowledgement between its prefix and body. A write of at least 64 KiB first drains
any buffered frame bytes, then sends the caller's byte range directly in one socket write.

A handler never writes its response to a socket. It computes the response, frees its slot, hands the
frame to its connection's single writer thread and terminates, so a peer that pipelines requests
without reading its responses cannot make handlers accumulate. (Effect propagation is the exception:
`propagateRequestEffect` and `propagateNotificationEffect` write to the socket directly on the
handler's own thread, so a handler that emits an effect to a peer that has stopped reading blocks
there while still holding its slot. It occupies capacity, not extra threads.) The bounds are exact:

- **Platform threads:** one accept thread, one reader thread and one writer thread per connection,
  and at most `concurrentHandlerLimit` request-handler threads for the whole server. The handler pool
  enforces that ceiling as its `maximumPoolSize`; its queue exists only to absorb the moment between
  a handler finishing and its worker becoming available again, and never holds more than
  `concurrentHandlerLimit` entries because a queued task still holds its admission permit.
- **Undelivered response bytes:** 4 MiB per connection and 16 MiB across all connections in the
  process, plus a separate 1.5 MiB per-connection / 3 MiB process-wide allowance reserved for the
  errors below so a saturated connection can still answer. A response offered while its connection
  has nothing undelivered is admitted under a higher 32 MiB process-wide ceiling instead of being
  refused, because it is not a pileup: that covers both a peer that is simply keeping up and a single
  response legitimately larger than the whole per-connection allowance, such as a multi-megabyte
  bytecode JAR. Streaming bytecode responses are queued as their JSON header plus their existing
  attachment arrays and emitted segment by segment, so they are never aggregate-copied into a single
  frame.
- **Reading:** a connection's reader stops consuming that connection's requests while its own
  undelivered responses exceed 1 MiB — including a response that is waiting for the shared allowance
  below. The peer's receive window fills and its writes block, so a peer that stops reading throttles
  itself and no other connection.
- **Waiting for the shared allowance:** a response whose own connection holds nothing undelivered is
  not refused the moment the shared process-wide allowance is spoken for. An empty outbox is the
  server's own evidence that this peer consumed everything computed for it, so the response occupies
  the allowance only for as long as its write takes; several such peers routinely ask for more than
  the ceiling at one instant and for less than it a moment later. The offer waits up to five seconds
  for the connections ahead of it to return what they are delivering, and is refused only if they do
  not — which is what a peer that has stopped reading looks like, since its bytes stay charged until
  its connection is torn down. Every refusal a connection earns against its *own* allowance, and
  every refusal reply, is still returned immediately, so a stalled peer never makes a handler thread
  wait.

When a computed response does not fit those allowances the caller receives a `RESPONSE_OUTBOX_FULL`
RPC error stating that the handler ran to completion, that the response was discarded, and that the
request was not retried — deliberately not the `CONCURRENT_HANDLER_LIMIT` refusal, which says the
request "was not started". The error names which allowance was exceeded and the bytes observed when
it was: only the per-connection case means this caller stopped reading, and the process-wide cases
routinely refuse a caller that is reading perfectly because other connections consumed the shared
allowance. The server also emits a `TcpServerResponseOutboxFullEffect`, whose `refusal` carries the
same allowance and observed byte counts.
`TcpServer.responseOutboxSnapshot()` reports the bytes currently and peak retained, the allowances,
the read-pause mark, and how many responses have been answered this way.
Advanced delivery diagnostics can pass a `TcpServerResponseOutboxObserver` to the matching
`TcpServer` constructor overload. Its queued callback runs only after the response is writer-visible
and its positive byte accounting is complete; its released callback runs after delivery or teardown
has returned every charge. Callbacks run without the connection outbox monitor, so they may overlap
and a fast writer may report release before the queued callback starts. Consumers should use them for
aggregate lifecycle diagnostics rather than pairing concurrent responses by callback arrival order.

### Environment-Based Binding

Containers can use the `URL_BIND_DOMAIN` environment variable to control their binding:

```kotlin
// When URL_BIND_DOMAIN is set to "localhost.tcp:8080", binds to TCP port 8080
// When URL_BIND_DOMAIN is not set, uses the default domain "my.service"
val binding = protocol.bindWithEnvOverride("url://my.service/") { request, _ ->
    // Handle request...
    Libp2pRpcProtocol.RpcResponse.success(request.id, "Hello")
}
```

This allows the same code to:
- Run standalone with full P2P networking (default domain)
- Run in a container with local TCP binding (when `URL_BIND_DOMAIN=localhost.tcp:8080`)

### P2P Relay for NAT Traversal

For peers behind NAT (home routers, firewalls), the protocol supports connecting via a public P2P relay:

```kotlin
import foundation.url.protocol.*

// Create protocol with relay support
val protocol = UrlProtocol()

// Connect to a NAT-ed peer via relay
// The relay peer ID is: 12D3KooWQE54k2VQzbLqDZLmfoEZ8AyAxz2GoxCE2muSpWF5UNDN
// The relay address is: /ip4/198.199.106.165/tcp/4001/p2p/...
val stream = protocol.openStream("url://w3wallet.daemon.12D3KooWTargetPeerId/")

// Send request - automatically routed through relay if direct connection fails
val request = Libp2pRpcProtocol.RpcRequest(
    id = Libp2pRpcProtocol.nextRequestId(),
    method = "list_profiles",
    params = emptyMap()
)
val response = stream.sendRequest(request)
```

**Relay Protocol: `/urlresolver-relay/1.0.0`**

The relay protocol uses the following message types:
- `RELAY_REGISTER` - NAT-ed peer registers with the relay
- `RELAY_REQUEST` - Client sends request to target peer via relay
- `RELAY_RESPONSE` - Response from target peer via relay

NAT-ed services (like W3WalletDaemon) automatically register with the public relay on startup, allowing clients to reach them without requiring port forwarding.

If a response-chunk fetch is explicitly refused as not started, the client resends only that
fetch sequence. The application request has already run and is never repeated. Repeated
fetch admission refusals share the caller's original response deadline, including time already
spent before the first refusal. Resends and their reply/admission waits use only the remaining
budget; a later chunk cannot start a fresh budget. First response waits, accepted
chunk-resend reply waits, and shared response-fetch permit waits observe the existing
`RelayService` Clock and the deadline captured at call entry. Advancing that Clock expires
an already waiting call with its usual deadline error and releases its pending replies and
fetch permits; interruption still ends the wait with the interrupted flag preserved. Normal
response errors and uncertain transport failures still fail without resending the request.
This applies to both forwarded and directly registered-peer downloads, with the existing
fetch-window and shared admission limits.

If the caller-configured Clock fails during request-initiated relay dialing or its reachability
probe, `sendViaRelay` and `sendViaRelayTo` throw `RelayRequestClockException`. Its `cause` is the
original Throwable, including an `Error`; this local failure must not cause route retry or
reconnection. Later cleanup failures are added to that original Throwable as suppressed diagnostics,
including failures from connections or streams that finish closing after the call returns.
Timer setup and cancellation are part of that dial outcome. The failed request
sends no application payload, and later calls can use the same RelayService. UrlResolver adapters
should catch this type and report its cause to the admitted request's caller. Independent
registration, periodic maintenance, and ping dials retain their existing failure behavior.

A custom `Host` adapter that reads a Clock while acquiring streams should also implement
`RelayStreamHost`. Its `newStreamWithClock(protocols, peer, clock, vararg addresses)` receives
the current dial's Clock for both existing-parent and cold acquisitions. Use that Clock for
all acquisition reads and timers, including asynchronous recovery, and keep it within that
operation. Concurrent requests receive distinct views; do not store a view on the shared host.
A Clock failure reported through an acquisition future also ends that request without another
host acquisition. The host retains ownership of the physical parent, which remains usable by
later requests. Existing Host adapters and RelayService constructor signatures are unchanged.

A keepalive ping declined by local write admission does not count as an unanswered remote
probe. For Yamux, relay progress is positive inbound DATA or WINDOW_UPDATE traffic on the
registration's own stream, including PONGs and flow-control credit returned while the relay
consumes a large answer. Bytes on sibling streams and local outbound writes do not refresh it.
Other muxers count positive inbound bytes on that registration stream. After a local admission
decline, the existing keepalive tick checks the inbound-silence threshold `L = I + D`, where `I` is the configured interval and
`D = W + T` is one missing-PONG window (`W = 200 ms` for bounded write admission and
`T = floor(2I/3)` for the PONG timeout). Successive checks are at most `C = max(I, D)` apart, so
the listener retires within `B = L + C = I + D + max(I, D)`, matching the main two-missing-PONG
bound without another scheduled task. With the default `I = 15,000 ms`, `T = 10,000 ms`,
`D = 10,200 ms`, and `C = 15,000 ms`, the bound is `B = 40,200 ms`.

Keepalive Clock reads happen outside the progress lock. Inbound progress only moves its timestamp
forward, and an expiry sample compares against that timestamp without changing it. A backward Clock
step cannot expire a registration early; if the Clock resumes advancing at its usual rate, a single
step delays silence expiry by at most the size of that step. A permanently frozen Clock does not
advance silence expiry, but an admitted PING that receives no PONG still retires the relay through
the existing two-missing-PONG limit.

The listener continues receiving requests while completed answers wait for outbox space. Admitted
pings keep their existing response timeout and two-failure handling. A closed transport still ends
the registration.

A registered peer runs each request the relay delivers in its registered request handler on a
virtual thread, and the registration connection never waits for a handler to finish: it keeps
reading requests, registration acknowledgements and pings however long admitted handlers take, so
requests that stay open (long-polls) cannot stop the peer answering anything else. At most
`RelayService.MAX_CONCURRENT_RELAYED_REQUEST_HANDLERS` (64) handlers run at once.
`RelayService.activeRelayedRequestHandlerCount()` includes running handlers and completed replies
waiting for outbox capacity. Handler admission and failure-frame reservation never wait on the
registration reader. A request that arrives while all slots are taken is refused without running,
and the requester's
`sendViaRelay` throws `RelayRequestNotDispatchedException` (so it is safe to retry) with this
message:

```
Relay request failed: A relayed request was refused without running it: this peer is already running 64 relayed request handlers, which is the limit of 64 (RelayService.MAX_CONCURRENT_RELAYED_REQUEST_HANDLERS). Retry after one of them finishes.
```

Refusals enter a separate charged outbox allowance: 128 KiB per connection and 256 KiB across
writable connections, with at most 64 KiB per refusal. Running handlers and their replies cannot
consume that allowance. Failure-reservation exhaustion also refuses without dispatch, with the
message `A relayed request was refused without running it: this peer has no response-failure
reservation capacity available. Retry after an earlier response releases capacity.` If the refusal
allowance is exhausted, or the refusal exceeds its frame limit, the registration closes immediately
instead of waiting on its reader. A queued refusal still depends on transport progress; it follows
the outbox's existing stalled-output retirement and close rules.

A completed reply keeps its slot while waiting for outbox space,
and returns it when admitted, before any writer can send it. At most 64 completed replies can wait
for admission per `RelayService`; each waiting frame is at most the outbox's 4 MiB normal-frame
limit (64 KiB in the priority lane), in addition to the outbox's charged byte allowance. A requester
that renews a long-poll the moment it reads the reply therefore finds its previous slot free. When the registration
connection closes, or the `RelayService` closes, the handlers that connection delivered are
interrupted, because their replies can no longer be sent.

Large `sendViaRelay` requests on a relay request stream are split into 128 KiB chunks. The sender
opens with up to two request chunks in flight, widens the window by one after each successful
acknowledgement up to the existing byte-derived cap, and consumes replies in stream order. Upload
time therefore does not include one full application-level round trip per chunk while the existing
bounded transport writes remain in use.

Relay operators can sample `RelayService.currentIncomingChunkAssemblyCount()` to monitor how many
incomplete incoming chunked requests are being assembled. Their raw chunks remain in temporary
file-backed storage rather than accumulating on the JVM heap, and `RelayConfig.chunkAssemblyTtlMs`
bounds their idle lifetime. Expiry and relay shutdown remove that temporary storage. The process
reserves one 128 KiB slot per declared chunk against a shared 1,610,612,736-byte temporary-storage
limit, so one maximum-size 12,288-chunk declaration fits and further declarations are refused until
storage is released. A deletion failure keeps its storage charged and is retried by the next
reservation, so the ledger never reports space that the filesystem still retains. If closing the
Okio `FileHandle` itself fails, Okio has already marked that handle closed and cannot safely retry
the close. The relay therefore keeps the path and its process-wide storage charge for the rest of
the process instead of deleting a file that may still be open. The initial failure and each later
cleanup attempt are exposed by `RelayService.relayWarningEffectsSnapshot()` as the latest
`RelayRequestTemporaryStorageCleanupWarningEffect`; the effect states the path, operation, attempt,
failure, and whether an earlier close already failed.

Embedders can provide `relayRequestFileSystem` and `relayRequestTemporaryDirectory` to
`RelayService`; production defaults to Okio's system filesystem and system temporary directory.
The existing five-parameter `RelayService` constructor and its Kotlin defaults remain available,
while the storage-injection overload requires both storage arguments. Per-transfer filesystem work
is serialized without holding the service-wide assembly-state lock, so a delayed file operation for
one transfer does not stop an unrelated transfer. `RelayService.close()` first stops new transfer
admission, then waits for an in-flight per-transfer filesystem operation to leave its gate before it
closes and removes that transfer. A transfer released after close observes the closed response and
does not reach the request handler. `RelayService.isClosed` becomes true at that initial permanent
close transition, before `close()` waits for in-flight work or releases resources. A completed
request is materialized for the existing handler contract only after every chunk arrives.
Materialization is admitted in FIFO order against a process-wide heap
budget of one half of the JVM maximum heap, with a 512 MiB ceiling; requests whose raw bytes plus
three times their Base64 size exceed that budget are refused. A chunk's Base64 text is refused
before decoding when it exceeds the exact 174,764-character ceiling for one 128 KiB chunk, while
the decoded-size check remains in place for payloads whose encoded length is within that ceiling.
`chunkSeq` and `totalChunks` must be JSON integer values within the signed `Int` range; omitted
fields retain the legacy defaults of zero and one. The separate
`outgoingChunkedResponseIdleTtlMs` setting bounds large responses awaiting chunk fetches and
refreshes only when a previously unserved chunk is delivered.
These lifetime values are preserved by `RelayConfig.copy()` and included in its equality and hash
code while remaining outside the legacy seven-argument constructor used by existing consumers.

Both directions of a chunked transfer between a requester and a relay are pipelined within a bounded
window rather than exchanged one chunk at a time. (The one-hop path a relay uses to push to a peer
already registered with it, `sendDirectlyToRegisteredPeer`, still exchanges one chunk at a time.)
`sendViaRelay` keeps several request-chunk frames in flight and issues one more for each interim
acknowledgement it reads, so an N-chunk transfer costs roughly `ceil(N / window)` relay round-trips
instead of N sequential ones. Sending a chunk and then blocking on its acknowledgement
multiplied every scheduling delay by the chunk count, which is what made a multi-megabyte upload over
a relay slow to a crawl on a busy machine while the same upload finished quickly on an idle one.

The request window is bounded in bytes rather than in frames, because a request chunk is a ~175 KB
frame while a response-chunk fetch is a few hundred bytes. In-flight request-chunk bytes stay at or
below the transport's 1 MiB write low-water mark, so a transfer never pushes its own channel into the
unwritable range where its next frame would fail admission outright. The window opens at two frames
and widens by one per acknowledgement, so senders that start together do not put a combined
opening burst on a relay's ingress; each paces itself to the capacity the path has demonstrated. Each
direction has its own connection-wide budget sized by its own byte footprint, so their sum respects
both the write watermark and the connection's parked-write permits. A transfer waits for a permit only
when it holds none of its own, so a transfer with frames in flight always keeps reading the
acknowledgements that release them.

A sender with several request chunks in flight identifies each reply by its own content rather than
by the order it arrives in, because nothing in the protocol guarantees that reply *i* answers chunk
*i*. Chunk assembly happens at the target, so a relay forwards every chunk onward and answers from an
asynchronous completion rather than from its per-stream critical section; multi-hop routes and older
relay versions widen that gap further. No reordering has been observed in practice, so this is
robustness against an ordering that was never promised rather than a fix for a seen interleaving.
Interim
acknowledgements carry `chunkAck` and an empty payload and are interchangeable; the target's response
is the one reply that is not an acknowledgement, and a transfer that produces a second such reply, or
none at all, is reported as a protocol error. Every reply is read before the call returns, so a
reused request connection never carries replies belonging to a finished transfer.

A successful `RelayRegisterAck` also means the relay has already recorded the registering peer's
direct contact in its `PeerObservationLog` and replaced that peer's registry membership and service
catalog with RELAY origin. Both changes complete before the acknowledgement is sent, so a client
that has been told its registration succeeded is immediately visible through the relay's peer
registry and is never treated as an unverified peer by peer-exchange sharing or any other observation
gate. Later messages, callbacks, withdrawals, and notifications remain ordered by the relay's
per-identity publication generations.

#### Refreshing an Existing Relay Registration

A client that starts advertising additional services after its relay connection is established can
replace the registration's service list without opening another connection. The asynchronous form
completes only after the relay has applied and acknowledged the replacement; the blocking form is a
compatibility wrapper around the same acknowledged operation:

```kotlin
val applied = relayService.refreshRelayRegistrationAsync(
    listOf("my.service", "my.second.service")
).get()

val appliedBlocking = relayService.refreshRelayRegistration(
    listOf("my.service", "my.second.service")
)
```

Both forms return `false` when there is no live relay registration or when the relay rejects the
replacement. The requested list becomes the desired relay catalog before connection inspection or
I/O; a successful ACK marks that exact revision committed, while any later reconnect sends the
newest desired revision even when the preceding refresh ACK was lost. If the registration stream
closes while a replacement is awaiting its ACK, that operation finishes from the close event; it
does not remain pending until the missing-ACK time budget expires.

A listener startup or read failure closes that listener's registration transport and clears its
selected relay and candidate state before pending refresh callbacks run. Those refreshes receive
the original listener failure, with any transport-close failure attached as cleanup detail. A fatal
`Error` from the listener read still leaves its worker after cleanup; an older listener's failure cannot clear or close a replacement stream.

An explicit `unregisterFromRelay()` or `close()` also reports a failure from closing its
registration stream when the retiring listener performed that close first, including when
retirement finished before teardown began. A close failure already reported as cleanup detail on
its listener failure or printed receive-callback failure is consumed there and is not reported
again at teardown. Public refresh outcomes are reserved before any completion callback runs;
a refresh canceled or completed by its caller, or an internal Boolean-only result, cannot consume
an undelivered close error. A close `Error` added during cleanup and delivered through a reserved
public refresh is not also thrown as a separate worker error.
Refresh rejection, acknowledgement failure, and send-failure cleanup reserve the returned
outcome before claiming their close detail, so a reportable close `Error` cannot also escape
from the retiring listener worker. Normal explicit close keeps its existing best-effort
handling of ordinary stream-close exceptions. Initial-registration and explicit replacement
cleanup also capture their own claims before closing. Automatic retirement records its
outcome without choosing a reporter. The registration's error record survives later stream
selection, including while an older stream is still closing. The first undelivered failure
stays primary; later close failures are attached as suppressed detail, with seven exact later
failures and a count of further failures. Selecting another stream does not consume the record.
Delivery clears its pending failure and details. A close finishing after that delivery remains
available for the next explicit teardown and does not change the already delivered failure tree.
Otherwise the first explicit teardown claims that error before invoking supplied code, including clock cancellation
and close callbacks; concurrent and repeated teardown cannot report it again. A newer
registration that reuses the same live stream owns its own first teardown claim; an older
unregister cannot take the newer registration's error. Close observers
may re-enter teardown without waiting for their own close to finish or taking its future error.
A late old close cannot close or clear the replacement transport, but its undelivered error
remains available to the registration's next teardown. Each stream has one physical close and
one outcome; delivered errors are released while an empty record remains available for late
close completions. If the stream's close is interrupted during retirement, the reported
`InterruptedException` names the stream instance and retiring thread and retains the exact
original interruption as its cause.

#### Relay-cache admission and direct withdrawal recording

Use `RelayService.cacheServiceAnnouncement(serviceIdentifier, message)` for announcements learned
through direct gossip as well as relay connections. For the same retained service/provider, a
strictly older announcement cannot replace the newer cached JSON or message ID. This applies to
registered and unregistered providers; registered providers also retain their inventory timestamp
check. Equal announcement timestamps may refresh the payload, but a withdrawal wins an equal
timestamp. Provider timestamps are compared only within the same service/provider lifecycle.
The existing cache, inventory, and withdrawal expiry and capacity limits still bound retention.
Admission takes a detached snapshot before reading ordering fields. The cache owns immutable
ordering metadata and serialized replay JSON; changing the original input or any value returned
by `getCachedServiceAnnouncements()` (including nested JSON) cannot change admission or replay.
This costs one JSON copy per admission and one per returned announcement on each snapshot. That
cost is acceptable at these ownership boundaries: retention is bounded, and the copies let callers
use mutable JSON without sharing the cache's ordering state.

A consumer handling a direct withdrawal should record it before notifying its handlers:

```kotlin
relayService.recordServiceWithdrawal(
    withdrawal.serviceIdentifier,
    withdrawal.providerPeerId,
    withdrawal.timestamp
)
```

`recordServiceWithdrawal` monotonically records that timestamp and removes a matching old-or-equal
cache entry before returning. Later old-or-equal announcements are rejected while the withdrawal
is retained; a strictly newer announcement survives that withdrawal. The method invokes no
application handler on its calling thread, including for expired disconnected-provider cleanup,
whose publication runs on a separate worker. Announcement and withdrawal handlers, including
inventory-eviction handlers, run after every caller has released the relay's provider, registration,
inventory and cache locks. Together these guarantees allow direct withdrawal recording while the
consumer holds its own service publication stripe. Per-stream delivery and same-key receive
callbacks remain serialized.

`cacheServiceAnnouncement` may synchronously invoke an inventory-eviction withdrawal handler.
Call it after releasing application locks that the handler may acquire. Its atomic timestamp
comparison preserves cache order if an older write was delayed across a newer announcement or a
recorded withdrawal. `removeCachedServiceAnnouncement` remains a callback-free removal-only
operation: it records no withdrawal timestamp and cannot by itself reject a delayed cache copy.

Explicit `unregisterFromRelay()` captures the acknowledged registration on its relay stream.
A relay that supports generation-specific unregister keeps a later registration acknowledged on
that same stream, including when an older unregister write arrives afterward. Refresh ACKs may
arrive out of order without changing which registration a later unregister captures. This behavior
requires both the client and relay to contain the generation-specific unregister implementation;
older relays retain the original sender-only removal behavior. If the captured response path ends
while unregister awaits its ACK, that wait ends from stream departure. A live admission failure,
a negative ACK, and caller interruption remain visible to the caller.

For a service that must return a structured close reply for accepted calls, use the overload:

```kotlin
import foundation.url.protocol.RelayProtocol
import foundation.url.protocol.RelayService
import java.util.concurrent.CompletableFuture

fun stopRegistration(relay: RelayService): CompletableFuture<Unit> =
    relay.unregisterFromRelay { request ->
        RelayProtocol.RelayResponse.error(
            requestId = request.requestId,
            sourcePeerId = request.targetPeerId,
            targetPeerId = request.sourcePeerId,
            errorMessage = "The target resolver closed while handling this relay request",
        )
    }
```

`unregisterFromRelay(finalResponse: (RelayProtocol.RelayRequest) -> RelayProtocol.RelayResponse)`
stops admission and reconnection, claims the unfinished accepted requests, queues their supplied
replies, and queues unregister behind them. It returns `CompletableFuture<Unit>` without waiting
for application handlers, writes or an acknowledgement. The future reports the local unregister
write and stream retirement, including a write or cleanup failure. The reply supplier runs on the
calling thread outside lifecycle and transport locks; it must preserve the request ID and reverse
the peer identities. Supplied replies receive the same delivery-status normalization as handler
replies: a not-dispatched marker is removed because an accepted request's handler may already
have run. Each request has one response reservation: an earlier completed handler keeps
its reply, and a result arriving after close has claimed the reservation is discarded. The overload
adds no timeout parameter. The existing no-argument/default-budget overload retains its signature
and acknowledgement behavior.

#### Observing Disconnected-Peer Cleanup

A relay emits `RelayDisconnectedPeerCleanupEffect` after it has finished removing a disconnected
peer's stream-owned registration and cached announcement state. Each announcement accepted
through a relay stream is tagged with that stream's generation, so cleanup removes entries written
by the closing stream even when a newer stream from the same peer is already the current
registration; the newer stream's registration and generation-owned entries are kept. Direct cache
writes that did not come through a relay stream are not attributed to a stream close. The effect
carries the peer ID and `cachedAnnouncementRemoved`, which is `true` when cleanup removed at least
one cached service announcement owned by the closing stream. This lets operators and embedding
services observe registration reaping that was previously silent.

Install the specific handler before starting the relay so the service can carry that effect scope
to its asynchronous disconnect-cleanup workers:

```kotlin
import foundation.url.protocol.RelayDisconnectedPeerCleanupEffect
import kotlinx.algebraiceffects.Effective

Effective {
    handler { effect: RelayDisconnectedPeerCleanupEffect ->
        println(
            "Relay cleaned up ${effect.peerId}; " +
                "cached announcement removed: ${effect.cachedAnnouncementRemoved}"
        )
    }

    relayService.start()
}
```

`RelayService` does not handle or suppress this effect. A caller can handle the dedicated type at
the application boundary according to its own logging, metrics, or lifecycle policy.

#### Preparing Relay Candidates Before Selection

A caller that needs to compare several relays can prepare their registrations concurrently without
publishing any of them as the selected relay:

```kotlin
val first = relayService.prepareRelayCandidateRegistration(
    relayPeerId = firstRelayPeerId,
    budgetMs = 10_000,
    services = listOf("my.service"),
    handler = ::handleRelayedRequest
)
val second = relayService.prepareRelayCandidateRegistration(
    relayPeerId = secondRelayPeerId,
    budgetMs = 10_000,
    services = listOf("my.service"),
    handler = ::handleRelayedRequest
)

val acknowledgedFirst = first.acknowledgement.get()
val acknowledgedSecond = second.acknowledgement.get()
val firstWon = relayService.commitRelayCandidateRegistration(acknowledgedFirst)
val loser = if (firstWon) acknowledgedSecond else acknowledgedFirst
relayService.cancelRelayCandidateRegistration(loser).get()
```

`prepareRelayCandidateRegistration` returns immediately and performs the bounded dial,
registration, and acknowledgement wait on a per-service worker pool. Preparation registers only
the candidate connection's liveness; its service catalog is not sent until commit, so an
acknowledged loser cannot advertise a route without a listener. An acknowledged handle does not
change `currentRelayPeerId`. `commitRelayCandidateRegistration` atomically installs exactly one
acknowledged handle, starts its listener, and publishes that winner's service catalog over the
selected stream. It returns `true` only after the relay acknowledges that catalog; a late, losing,
or rejected commit returns `false` and releases the failed handle for cleanup.
`cancelRelayCandidateRegistration` changes the losing handle to `CANCELED` immediately and returns
a future for its asynchronous stream cleanup. After acknowledgement, an uncommitted handle remains
live for one additional preparation-budget interval and then changes to `FAILED`, closes its stream
asynchronously, and releases its admission slot. A handle that is neither committed nor needed
should still be canceled promptly rather than retained until that bound. After
`unregisterFromRelay`, the same `RelayService` can run another candidate selection lifecycle.

Use the blocking `registerWithRelay` API when the relay is already selected and candidate
comparison is not needed. Concurrent registration calls share one registration transaction at a
time. A caller interrupted while waiting for another registration receives a `RelayException`
with the peer and relay IDs, the original `InterruptedException` as its cause, and its interrupt
flag preserved. That caller sends no registration and leaves the active registration running.

### Server-Initiated Push to a Connected Client

A registered service normally only *replies* to requests. When a service needs to send **unsolicited** frames back to one specific connected client over its persistent RPC connection — for example live-projection deltas, or any long-lived subscription — it implements `ConnectionAwareServiceHandler` instead of `ServiceHandler`. The four-argument `handleRequest` additionally receives a `ServiceClientConnection`: a **durable, connection-scoped** channel to that exact client. Capture it during one request (e.g. a subscribe) and use it later:

```kotlin
import foundation.url.protocol.*
import org.json.JSONObject

class MySubscriptionService : ConnectionAwareServiceHandler {
    override suspend fun handleRequest(
        path: String,
        params: Map<String, Any?>,
        metadata: Map<String, String>,
        connection: ServiceClientConnection
    ): Any? {
        if (path == "subscribe") {
            // Reap the subscription the moment this client's connection dies —
            // no polling heartbeat needed (fires once, on graceful close OR abrupt reset).
            connection.onClose { subscriptions.remove(connection.connectionId) }
            subscriptions[connection.connectionId] = connection
            return mapOf("subscribed" to true)
        }
        return null
    }
}

// Later, when new data is available, push an unsolicited frame to that client.
// The frame's "type" must be one the client registered an inbound handler for.
try {
    connection.push(JSONObject().put("type", "my_delta").put("value", 42))
} catch (e: ServicePushException) {
    // The push FAILS LOUDLY (never silently drops) when the connection is closed,
    // reset, or unwritable — so a connection-scoped lease can reap on failure.
    subscriptions.remove(connection.connectionId)
}
```

Key properties:

- **Backward compatible.** Existing three-argument `ServiceHandler`s are unchanged and never receive a connection; server-initiated frames only ever flow after a `ConnectionAwareServiceHandler` explicitly calls `push`.
- **Authenticated peer identity when available.** `ServiceClientConnection.remotePeerId` is the authenticated libp2p remote peer id for libp2p-backed connections, taken from the secure stream identity rather than request metadata. It is stable across reconnects from the same client keypair. It is `null` for `localhost.tcp` and detached/in-process calls, which have no cryptographic peer identity; code that falls back to `connectionId` in those cases should treat that fallback as reconnect-unstable.
- **Validated type.** The pushed frame must carry a non-empty custom `type` that is not one of the transport's reserved types (`response`, `error`, `ping`, `pong`, the `stream_*`/`effect_*`/`observable_*` families — see `Libp2pRpcProtocol.RESERVED_MESSAGE_TYPES`). A missing/empty or reserved type is a caller programming error and throws `IllegalArgumentException` before anything is written, so a push can never spoof an in-flight RPC response or control frame at the client.
- **Loud failure, always bounded.** `push` throws `ServicePushException` when the connection is closed/reset/unwritable, rather than silently dropping the frame. The deadline is per transport but a push never blocks forever: on a **libp2p** connection the frame goes over the same backpressure-gated path as an awaited RPC response and is reported dropped if the connection stays unwritable through the delivery window; on a **`localhost.tcp`** connection the blocking socket write (which has no OS send timeout) is bounded by a watchdog that closes the socket after the same delivery window, so a client that stops reading cannot park the write forever.
- **Direct connections only.** Server-initiated push requires a direct connection (or a transport-level circuit-relay connection) to the client. A service reached through the *app-level* relay sees the relay-bound connection, not the true client's: the relay forwards a request and correlates exactly one response, with no unsolicited-frame path back to the original source, so pushes on such a connection will not reach the true client.
- **Connection-scoped lifecycle.** `onClose` fires exactly once when the connection dies (graceful close or abrupt disconnect); a listener registered after the connection is already dead fires immediately. Request cancellation never waits for these callbacks. Registered listeners run on a dedicated dispatcher rather than the transport's I/O or teardown thread, so a slow listener cannot stall other connections or transport cleanup; listeners should still be fast, and should be registered once per subscription (each call adds a listener). TCP connections also implement the optional `CloseAwareServiceClientConnection`, whose final `closeCause` is published before their request handlers are interrupted. `connectionId` is unique per live connection, so a sender captured before a client reconnects fails loudly on the old connection and can never write onto the new one.
- **Admission order.** Real TCP and libp2p service connections implement the optional `AdmissionOrderedServiceClientConnection`. Its positive `admissionOrder` is assigned when the RPC transport is admitted, before asynchronous request work, and remains unchanged after close. All built-in transports in the same protocol classloader share this order; it does not compare processes or restarts. Inbound `NetworkProvider` streams expose `AdmissionOrderedRpcStream` before their callback is queued, so a server adapter carries that value into its service connection. A custom transport can use `nextServiceConnectionAdmissionOrder()` once at admission. Missing capability means unknown order: consumers must not infer age from first handler execution or let an unknown-order connection replace a different existing owner based on that observation. Existing interfaces, constructors, and wire frames remain compatible. All handles for one admitted connection preserve that same positive ordinal, which identifies its lifetime as well as its order. The legacy transport constructor remains binary compatible and reports zero for unknown order.
- **Optional transport retirement.** Real TCP and libp2p service connections also implement `DisconnectableServiceClientConnection`. A connection-aware proxy can check for this subtype and call `disconnect()` when its downstream connection dies. The outside client then observes normal transport loss and can reconnect instead of remaining attached to a connection that cannot deliver more updates. This is an optional local API with no new frame type: existing `ServiceClientConnection` implementations and old peers continue unchanged.

For a persistent `localhost.tcp` backend connection, opt into non-response frames on `TcpRpcStream`:

```kotlin
val stream = TcpRpcStream.connect("127.0.0.1", 8080, "my.service")
stream.setInboundFrameHandler { frame ->
    when (frame.getString("type")) {
        "my_delta" -> applyDelta(frame)
    }
}
```

Response frames still use the existing request-id correlation path and never reach this handler. Other valid JSON frames are delivered unchanged, in wire-read order, on one per-stream worker rather than the socket reader. Callback completion is not ordered against later correlated responses: after an unsolicited frame enters the bounded handoff, the reader may complete a later response while that callback is still running. A non-fatal callback failure is reported and isolated to that frame so the worker can deliver later frames; malformed length-delimited JSON bodies are discarded after their bytes have been consumed so later compact frames retain alignment. The one-frame handoff is deliberately bounded: if the handler stops consuming, the reader stops draining the socket and the existing TCP write limits apply backpressure to the sender. Local close, remote EOF, and reader errors all close the socket and stop the dispatch worker, but a frame that was already read off the wire is still delivered: the worker drains the frames accepted before the close before its lane ends, so a peer that pushes frames and immediately disconnects cannot have those frames discarded while the response written behind them is still correlated (a handler can therefore run briefly after `close()` returns). A stream with no handler retains the prior response-only behavior and creates no dispatch worker, so existing clients and wire bytes are unchanged.

`ConnectionAwareServiceHandler` is what [`foundation.url:resolver`](https://github.com/CodexCoder21Organization/UrlResolver) builds its negotiated live-projection server-push on top of. Resolver's persistent reader continues to dispatch its own libp2p frames by `type`; `TcpRpcStream.setInboundFrameHandler` provides the corresponding opt-in path for persistent localhost TCP clients such as connection-aware proxies.

### Bytecode Fetching

UrlProtocol provides the `BytecodeProtocol` for requesting implementation bytecode from remote peers. This is used by [`foundation.url:resolver`](https://github.com/CodexCoder21Organization/UrlResolver) to support sandboxed execution.

When a service provider serves its **own** implementation bytecode (the optimized `supportsSandboxedExecution()` path), the response is streamed to the transport with bounded additional memory rather than materialized as a base64 `String` plus an inner JSON `String` plus a re-encoded `byte[]` (which previously held the payload ~5× in heap and could OOM a `-Xmx128m` service JVM under concurrent load). The serve path returns a `StreamingBytecodeResponse` carrier that the stream-write sites recognize. Attachment-capable transports send a small JSON envelope followed by the raw JAR bytes without first copying the complete frame into an aggregate byte array.

On raw TCP and an attachment-capable `/aicli-rpc/1.1.0` stream, that carrier is sent as a small JSON envelope plus a raw binary JAR attachment. This also bounds receiver-side parse memory: the multi-megabyte JAR never enters `org.json`. A negotiated libp2p 1.0 stream retains the legacy streamed base64 envelope for wire compatibility.

`Libp2pRpcStream` request parameters use raw binary attachments only when the stream negotiated `/aicli-rpc/1.1.0`. On `/aicli-rpc/1.0.0`, or when the controller does not report a negotiated version, `ByteArray` parameters use the compatible inline Base64 form. This also covers empty arrays and arrays nested in maps or collections; subsequent requests continue on the same stream.

The same negotiated behavior applies to ordinary RPC results: every `ByteArray` nested in a response is extracted into a binary attachment on `/aicli-rpc/1.1.0`, keeping the JSON envelope bounded. A 1.0 peer continues to receive the legacy inline Base64 representation. This includes a result kept as raw org.json, such as one a forwarder relays from `sendRequestRaw`: its inline Base64 byte-array markers (`{"__type":"bytes","__value":…}`) are sent as binary attachments to a 1.1 peer, so a forwarded byte array can use the chunked large-response path instead of one oversized JSON frame. A message carries at most 1000 attachments, so a raw value with more markers than the message has room left for keeps its inline Base64 form. The caller decodes the same `ByteArray` either way.

"Nested" here means reachable through any combination of `Map`, `Collection` (`List`, `Set`, …) and object-array values, on both the request-parameter and response-result legs. Encoding traverses those container types rather than handing them to `org.json`'s generic wrapping, which would render a contained `ByteArray` inline as an array of numbers — so a `List<ByteArray>` arrives as a `List<ByteArray>`, not as a `List<List<Int>>`, and its bytes stay out of the JSON envelope.

These container values must be acyclic: a `Map`, `Collection`, or object array that points to itself or an ancestor container is rejected with an `IllegalArgumentException`, because JSON has no representation for cycles.

Explicitly-null values are preserved as present keys holding a JSON null, so a service can distinguish "the caller passed null" from "the caller omitted this argument". This holds for top-level request parameters, for entries of a nested `Map`, for elements of a nested `Collection`, and for the response result.

Inbound v1.1 attachments are admitted under the same shared memory discipline as JSON parsing. Both the `InputStream` reader (`Libp2pRpcProtocol.readMessageFrameFromStream`) and the Netty `/aicli-rpc/1.1.0` decoder enforce, before allocating any attachment body: (1) the declared attachment count must equal the number of binary references in the JSON envelope (mismatch → `RPC binary attachment count does not match the JSON envelope: …`), and those references' `__index` values must form an exact **bijection** with `0..count-1` — validated with a strict integer parse that rejects coerced numeric strings, fractions, and Int-overflow (so a duplicate/sparse/non-integer index cannot leave an attachment unreferenced or double-referenced); (2) the declared per-frame aggregate must not exceed `Libp2pRpcProtocol.MAX_ATTACHMENT_AGGREGATE_BYTES` (heap-scaled `maxMemory/4`, clamped to `[32 MiB, 100 MB]`), checked at each length header so an invalid declaration is declined promptly without buffering; and (3) the `InputStream` reader grows its reservation to the validated running attachment total, capped at the whole pool. A fair frame turn lets only one sequential attachment frame grow at a time, so frames cannot each retain an earlier body while waiting for the other to release capacity. Independent JSON frames can use the remaining pool. The Netty decoder takes an exact-size lease for a one-attachment frame after its validated length header (capped at the whole pool) and retains its whole-pool lease policy for multi-attachment frames. Non-attachment frames are untouched. A legitimate tens-of-MB implementation JAR therefore still transfers on a small-heap consumer. The Netty decoder probes admission without blocking its event loop. When a reservation is unavailable, it pauses the child stream and registers callback continuations with the process-wide `SizeAwareReservationScheduler`; there is no blocking admission worker pool. The scheduler grants the oldest fitting request, allows at most eight later fitting requests to pass an older non-fitting request, and then reserves releases for that older request. On the send side, an attachment frame larger than `ATTACHMENT_SINGLE_BUFFER_FRAME_LIMIT_BYTES` (1 MiB) is written as bounded, backpressured header + attachment chunks rather than one aggregate `ByteBuf`, so a large legitimate response is admissible under the muxer's connection-wide write budget and never duplicates the whole payload in heap.

The libp2p dependency is pinned to `1.3.0-codexcoder21-snapshot-41`, which includes the
[child-read pause fix](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/42).
Server JSON and attachment admission, attachment borrowing, and the pause after 100 pending
requests all use the child stream's read pause. On Yamux that holds only the paused stream's
delivery and receive credit, bounded by its receive window; sibling streams keep flowing.
On Mplex the pause holds the physical connection until the child resumes because Mplex has
no per-stream flow control. Server pause ownership also holds explicit decoder read requests
until the final owner resumes, so an incomplete frame cannot read ahead of its reservation.
Explicit physical-connection pauses still hold every stream.
The pin also includes [explicit reads while paused](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/70),
so a caller can request queued data without enabling automatic reads. The RPC binding permits
explicit `Channel.read()` through a request-count pause, including when queued completion
notifications must be reconciled before automatic reads can resume. Frame reservation and borrow
pauses still hold both explicit caller reads and decoder continuation reads until capacity returns.

Connection turns remain FIFO, while process memory reservations use that bounded size-aware scheduling rule so a non-fitting large request cannot block every fitting small request and repeated small requests cannot postpone it without limit. A reader that holds a whole-pool lease also cannot retain it indefinitely: a **minimum-progress stall watchdog** (`Libp2pRpcProtocol.ATTACHMENT_STALL_TIMEOUT_MS`, reset by every inbound byte so a slow-but-progressing large transfer is not stopped) releases the lease and closes the stream after a real no-progress stall on both decode paths.

Awaited writes assigned to the compact handoff lane are copied once into exact-size lane-owned storage before queueing; the caller buffer is released immediately. The compact lane retains at most 64 KiB per entry because it owns copies, never caller buffers, and its 64-entry limit therefore retains at most 4 MiB per physical connection. The `ByteBuf` shape walk is only a routing aid: known large backing graphs, composite retained storage, and opaque multi-segment buffers keep the ordinary non-compact route without copying. It is not a claim that every caller backing allocation can be discovered through Netty's public wrapper graph.

A service **bound** directly with `bind("url://…/") { … }` gets the same streaming serve by using the bytecode-aware overload:

```kotlin
val binding = protocol.bind(
    "url://localhost.tcp:8080/",
    { request, effectHandler -> /* handle non-bytecode RPC methods */ },
    implementationJar = myImplJarBytes,
    implementationClassName = "com.example.MyServiceImpl"
)
```

With this overload the framework answers `__bytecode_request` for the bound service itself with a `StreamingBytecodeResponse` (streamed by the bound server's write path, peak heap ≈ `implementationJar.size`); the handler is never invoked for that method. This is the `localhost.tcp` / url-bind analogue of the `supportsSandboxedExecution()` streaming serve, and it replaces hand-rolling `__bytecode_request` in the bound handler — which would Base64-materialize the whole JAR per (re)connect and OOM a small-heap provider under a reconnect storm. When such a bound provider is reached through a proxy (e.g. ContainerNursery's UrlFacade), the proxy forwards the already-final streamed response unchanged rather than re-demanding the legacy `{jar, className}` backend keys.

A proxy that passes results directly to another RPC writer should use `RpcStream.sendRequestForForwarding`. For `__bytecode_request`, the TCP implementation returns after the validated response header and known attachment length arrive, allowing the outer writer to emit its response header while the attachment body is still arriving. Code that needs to inspect the raw JSON result should continue to use `sendRequestRaw`, whose result behavior is unchanged.

#### Conditional Requests (ETag-Style Revalidation)

A client that already holds a copy of a service's bytecode can make the request *conditional* — the `url://` analogue of HTTP `If-None-Match`/`304 Not Modified`:

1. The client digests its cached JAR with `BytecodeProtocol.computeDigest(jarBytes)` (canonical form: `"sha256:" + lowercase hex`) and sends it via `BytecodeProtocol.createRequest(serviceIdentifier, interfaceClassName, cachedDigest)`.
2. The server compares it against `ServiceHandler.getImplementationJarDigest()` (default: `computeDigest` over `getImplementationJar()`; override with a precomputed value to avoid rehashing per request). On a match, it replies with a few-hundred-byte `createNotModifiedResponse(digest)` frame instead of the multi-megabyte JAR.
3. The client parses with `BytecodeProtocol.parseConditionalResponse(json)`, which returns a `BytecodeFetchResult` — either `NotModified` (keep using the cached copy) or `Downloaded` (the bytecode changed; here is the new payload).

Both directions are backward compatible: old clients send no digest and always receive the full payload, and old servers ignore the `cachedDigest` field and reply in full, which a conditional client handles as `Downloaded`. The proxied/forwarding serve path (services without local JAR bytes) always serves in full, since answering conditionally would require materializing the backend's JAR bytes to hash them.

When the caller already knows the exact provider, `requestBytecodeFromPeer` and
`requestBytecodeConditionallyFromPeer` use the same provider-aware exact-peer dial path. Both accept
separate connection and request budgets, and the conditional form returns `NotModified` only after
validating that the peer echoed the submitted canonical digest. The request budget is enforced by
the caller-supplied `Clock` all the way through the real libp2p response wait; it is not shortened
to the low-level stream's historical 30-second default and does not expire while an injected
`ManualClock` is frozen.

### TCP request outcomes

`TcpRpcStream.sendRequestRawWithOutcome(request)` returns `TcpRpcRequestOutcome` with the
ordinary raw `RpcResponse` available as `response`. The constructor and the four-argument
`TcpRpcStream.connect(host, port, serviceIdentifier, responseClock)` accept a `Clock` for this
per-request deadline; existing callers use `SystemClock`. The response deadline begins after the
request frame has been written. A `ManualClock` expires the wait when that clock reaches the deadline. A forwarding caller that abandons a bytecode body returns its remaining declared frame to the
socket reader, using the existing attachment stall and lifetime limits. A body on a connection
already released by its owner still closes that terminal connection immediately on cancellation.
That explicit close keeps the body error `Forwarded bytecode attachment stream is closed` even
when its reader is interrupted during cleanup. Closing or releasing the owner also frees
an already abandoned body's forwarding reservation when a body read is still in flight.
A real transport failure or explicit close still ends every pending request:


- `Response` means a decoded peer reply, including application errors.
- `LocalDeadlineExpired` means this request's own 30-second response wait expired
  (`TIMEOUT`, `Request timed out after 30 seconds`). Its shared connection remains open for siblings
  and later requests, subject to the bounded unanswered-ID and silence rules above.
  A late reply is discarded without replaying the expired request and frees its ID.
- `RequestSendFailed` means an I/O error occurred while writing this request (`INTERNAL_ERROR`,
  `Failed to send request: <original I/O error message>`). Its mandatory `origin` is captured when
  that write fails; a later `close()` cannot change it.
- `ResponseUnavailable` also reports unanswered-ID admission refusal before any request byte
  is sent. Its error names the service and current outstanding ID count; a late reply can free
  capacity without replacing the connection.
- `StreamClosed` means a locally generated `INTERNAL_ERROR`, normally `Stream closed` result:
  silence retirement instead names the unanswered count and silence duration. The stream
  ended before selecting a reply. It carries the same mandatory, failure-time `origin` as
  `RequestSendFailed`, so reads and writes use one cause taxonomy.
- `TcpRpcTransportFailureOrigin.CLEAN_EOF` guarantees that the reader observed an orderly peer EOF
  before a response. It does not claim that the peer reset the connection or that this stream
  closed it locally.
- `TcpRpcTransportFailureOrigin.PEER_RESET` guarantees that the failed read or write observed native
  reset evidence (including a write-side broken pipe). Its derived `peerResetObserved` value is true
  and `closedLocally` is false, even if `close()` is called after that failure.
- `TcpRpcTransportFailureOrigin.LOCAL_CLOSE` guarantees that `TcpRpcStream.close()` or
  `releaseAfterForwarding()` caused the operation to end. It does not track a caller directly
  closing the socket passed to the constructor. Its derived `closedLocally` value is true and
  `peerResetObserved` is false. Queued frame sends retain the origin that ended admission, even
  before the reader processes the closure; a later local close does not replace an earlier peer origin.
- `TcpRpcTransportFailureOrigin.IO_FAILURE` means the failed operation had no clean-EOF, native-reset,
  or tracked local-close evidence. Direct caller closure of the constructor socket, framing I/O
  failures and bounded silence retirement use this value. Neither derived flag is true.
- `Socket closed` text alone never establishes a peer reset. Callers describing a peer reset should
  require `peerResetObserved && !closedLocally`; both values come from the immutable `origin`, not
  from the stream's later state.
- `ResponseUnavailable` retains the defensive `INTERNAL_ERROR`, `Response was null` result if a
  response wait completes without a selected response.

A backend's error stays `Response` even when its text exactly matches any of those transport
messages and the backend immediately closes the connection. Callers should classify origin by
the outcome type, never by message text, elapsed time, or the stream's later open state.

The existing `sendRequest`, `sendRequestRaw`, asynchronous, and forwarding APIs retain their
response shape and deadlines. The new method does not add retries or change wire messages.

`TcpRpcStream.sendRequestForForwardingWithOutcome(request)` preserves those same outcome types,
immutable origins, full error text and local failure details while allowing a bytecode attachment
to stream. It does not infer transport origin from error text or later connection state.

### Forwarding a bytecode response

`RpcStream.sendRequestForForwarding(request)` lets a proxy pass an accepted bytecode response
through before its backend body has finished arriving. `TcpRpcStream` returns a
`ForwardedStreamingBytecodeResponse` after validating the header and admitting its retained
resources. Ordinary `sendRequestRaw` continues to return a fully read raw result. Implementations
without incremental forwarding support use that ordinary raw path.

Pass the returned result to the outer RPC writer unchanged, preserving the response metadata.
Use `releaseAfterForwarding()` when the request handle is no longer needed but its accepted
response still belongs to the outer writer:

```kotlin
import foundation.url.protocol.Libp2pRpcProtocol
import foundation.url.protocol.TcpRpcStream

fun forwardBytecode(request: Libp2pRpcProtocol.RpcRequest): Libp2pRpcProtocol.RpcResponse {
    val backend = TcpRpcStream.connect("127.0.0.1", 8080, "bytecode-provider")
    return try {
        backend.sendRequestForForwarding(request)
    } finally {
        backend.releaseAfterForwarding()
    }
}
```

`close()` cancels the connection and its active body even after `releaseAfterForwarding()`.
This applies to direct transport streams and handles returned by `UrlProtocol` alike.
Closing the returned response
also cancels an incomplete body. A response permits one body reader and one outer writer; once
its exact body length has been consumed, an unreleased backend connection can carry another
request. Transfers are not resumed after a process restart.

`ForwardedStreamingBytecodeResponse.onCompletion(action)` binds a resource release to the response
lifetime. Register it after receiving the header and before returning the result to the outer writer.
Each registration runs exactly once on success, failure, cancellation or explicit close. Once a writer
claims the response, the action waits until that writer exits, after its final output write; reading
the final backend byte alone is insufficient. Closing an unread response without a writer completes
it, although the shared backend reader may still drain the discarded frame. A caller consuming
`jarInput` directly must close the response afterward. Late registrations run immediately.

Actions run synchronously outside library locks and may re-enter the API. Keep them brief. If an
action throws, cleanup and the other registered actions still run before the failure propagates;
additional failures are suppressed. A fatal JVM error takes precedence over an ordinary exception.

For example, a TCP facade can keep the typed outcome and attach its response resource release:

```kotlin
import foundation.url.protocol.ForwardedStreamingBytecodeResponse
import foundation.url.protocol.Libp2pRpcProtocol
import foundation.url.protocol.TcpRpcRequestOutcome
import foundation.url.protocol.TcpRpcStream

fun forwardBytecodeWithOutcome(
    request: Libp2pRpcProtocol.RpcRequest,
    onAttachmentCompletion: () -> Unit,
): TcpRpcRequestOutcome {
    val backend = TcpRpcStream.connect("127.0.0.1", 8080, "bytecode-provider")
    return try {
        val outcome = backend.sendRequestForForwardingWithOutcome(request)
        (outcome.response.result as? ForwardedStreamingBytecodeResponse)
            ?.onCompletion(onAttachmentCompletion)
        outcome
    } finally {
        backend.releaseAfterForwarding()
    }
}
```

The caller handles request exceptions and non-streaming outcomes directly, including ordinary
errors and buffered responses when streaming admission is full. The attachment callback applies
only when the result is a `ForwardedStreamingBytecodeResponse`. Preserve that value unchanged
when returning its response to the outer writer.

Streaming forwarding admits at most 32 transfers process-wide and bounds their estimated retained headers,
metadata and scratch buffers to one sixteenth of the maximum heap, with an 8 MiB minimum and
256 MiB maximum. Streaming admission never waits. When either limit is full, compatible responses
fall back to the ordinary buffered reader and its existing memory budget, returning the same bytes
after the body has arrived. The unread declared body does not consume the streaming reservation. Existing transport queues keep their own limits;
`forwardedTransferMemorySnapshot()` reports forwarding reservations and active transfers.
A demanded body read keeps the existing 10-second no-progress bound, while every held transfer
has the existing size-based absolute lifetime (a 60-second minimum and 4 KiB/second floor).
An output-capacity pause does not count as a backend read stall.

TCP and attachment-capable libp2p writers pull bounded body chunks. A capable libp2p peer can
receive small replies between complete forwarded chunks, including while the backend is paused.
Older libp2p peers receive incremental base64 within their JSON limits. If response preparation
fails, including when a valid attachment exceeds those legacy limits, the requester receives a
framed error after the backend is released. If that error cannot be sent, the affected stream closes.
A truncated body or a failed output write closes the affected stream; an early header does not
promise body completion, and error frames are never inserted into a partly written body.

### Sandbox Package

The `foundation.url.protocol.sandbox` package contains protocol-level types for bytecode exchange:

| Class | Description |
|-------|-------------|
| `BytecodeProtocol` | Creates and parses bytecode request/response messages, including conditional (digest-validated) requests |
| `BytecodeRequest` | Represents a request for implementation bytecode, optionally carrying a `cachedDigest` |
| `BytecodeResponse` | Contains implementation JAR bytes and class name |
| `BytecodeFetchResult` | Outcome of a conditional request: `NotModified` or `Downloaded` |
| `BytecodeProvider` | Interface for services that provide implementation bytecode |
| `JsonSerialization` | Serializes/deserializes objects crossing service boundaries |
| `SandboxException` | Thrown when sandbox operations fail |

> **Note:** `SandboxedConnection`, `SandboxedProxyGenerator`, `TypeMarshaling`, and the high-level `UrlResolver` class have been moved to [`foundation.url:resolver`](https://github.com/CodexCoder21Organization/UrlResolver), which provides typed connections and SJVM-based sandboxed execution on top of UrlProtocol.

## Default Bootstrap Peers

> **Note:** You do not need to know or configure bootstrap peers for normal usage. The default bootstrap peers are used automatically when you create a `UrlProtocol()` or `UrlResolver()` instance. This section is for reference only.

The url:// network includes two hardcoded bootstrap peers running on wasmserver.com (`198.199.106.165`):

| Peer | Seed Constant | Port | Service |
|------|--------------|------|---------|
| ContainerNursery | `WASMSERVER_BOOTSTRAP_SEED` | 35000 | Primary relay node |
| HardwareControlFabric Daemon | `HARDWARE_FABRIC_BOOTSTRAP_SEED` | 44605 | `hardware.control.fabric.daemon` |

These are used automatically by `UrlProtocol.withDefaultBootstrapPeers()` and `DEFAULT_BOOTSTRAP_PEERS`. Custom peers can be added via `addBootstrapPeer()` but this is rarely necessary.

```kotlin
// Use default bootstrap peers (recommended)
val protocol = UrlProtocol.withDefaultBootstrapPeers()

// Or create the HCF daemon bootstrap peer explicitly
val hcfPeer = UrlProtocol.createHardwareFabricBootstrapPeer()
```

## Network Architecture

### Peer Discovery via Gossip Protocol

Inbound one-way service, peer, relay, and TLD announcements, service and relay withdrawals,
and link-state advertisements hand their application callbacks to a separate executor owned by
that host. `ONE_SHOT_GOSSIP_PARALLELISM` limits this executor to 16 workers; ordinary RPC
request handling retains its separate 64 slots. Announcement application is normally short,
but it can wait on registry publication or a peer lifetime action. Sixteen workers allow
independent peers to progress while keeping those waits out of RPC request capacity; the
limit provides isolation rather than a throughput target. The executor uses the
`url-one-shot-gossip-` thread-name prefix and closes after the host's announcement jobs drain.
Their authenticated-interaction classification and recording run on that worker too, before
the message callback. The network event loop returns the decoder parse reservation before
one-shot child close becomes observable, so registry waits cannot prevent connection-close
handling. An admitted callback survives child or root close and cancellation of request-processing
work; closing a child or root does not acknowledge callback completion. Callbacks from one
authenticated peer run in arrival order in a bounded lane shared across its connections and across
roots with the same local and authenticated remote peer IDs. A replacement root joins that lane
while accepted work from an earlier root drains, so newer callbacks cannot overtake it. A late
callback cannot restore that sender's registry membership after its delivering root has closed;
synchronous registry adds
check that root under the same peer mutation owner used by removal. Removal also cancels a
first insertion that is still reading caller-supplied peer data or the clock, before a lifetime
exists. Such an insertion cannot later publish membership, service entries or persisted state;
a provisional observation created only by that insertion is released unless an independent
observation has already acquired it. Interaction observations,
including refusal observations, acquire their record under the observation owner's removal lock
after checking the same root. A callback already holding a record can finish on that detached
record without creating evidence for a removed sender or changing a replacement lifetime.
The shared self-connectivity tracker uses the observation's captured contact time, so delayed
completion does not create a new contact or replace newer connectivity evidence.
Third-party peer records
keep their existing behavior. Callbacks may enter registry APIs under the
[peer lifetime rules](#peer-registry-capacity).

Each authenticated peer can retain one active callback and at most 64 pending announcements,
shared across its connections and roots. Announcements also share a process-wide retained-work
allowance of `Libp2pRpcProtocol.MAX_CONCURRENT_PARSE_BYTES` (8 MiB on the default 128 MiB test heap).
They are charged at the parser's seven-times wire-size estimate until callbacks and error reporting
finish. A legal frame whose estimate exceeds this allowance takes the whole allowance exclusively;
the independent wire-size limit still applies. Under existing retained-work pressure, an
announcement can be refused before entering its callback.

Only a sender's own pending-announcement queue overflow produces a coalesced `SEVERE`
authenticated-interaction observation. Shared retained-work pressure leaves the sender's
observations and score unchanged. `AnnouncementAdmissionRefusedEffect` reports coalesced refusal
samples to the effect scope that constructed the host, after accepted work drains and off the
network event loop. Its `samplePeerId` identifies one refused frame rather than assigning shared
pressure to that sender; `peerQueueFull` distinguishes queue overflow from capacity pressure.
Refusal reporting retains at most one pending sample for announcements and one for withdrawals
in each peer lane. Peers with no existing work share a host lane with the same two-sample bound.
A sender's queue overflow takes priority over an earlier shared-pressure sample in its lane. An
unhandled report is written to stderr. Refused payloads are not retained.

Generic announcement and withdrawal decoders preserve provider-issued identity strings exactly in
`providerPeerId` and `peerId`. A custom `NetworkProvider` can withdraw the same opaque identity it
announces; these codecs do not impose another provider's format. The built-in libp2p provider's
gossip receiver, RPC receiver, and relay streams validate withdrawals before application admission.
Gossip validation also applies through ordinary Kotlin delegation of providers and streams.
These transports retain the built-in identity forms: a base58 peer ID encoding 32 to 50 bytes,
or an `in-memory-` identity containing the canonical unpadded URL-base64 encoding of a nonblank
UTF-8 seed. In-memory seeds retain their existing unrestricted length. Invalid values are refused
as malformed messages before callback admission, leave RPC and registered relay streams open,
and produce the existing `SEVERE` interaction observation. A malformed relay frame is refused
individually in either direction, so later valid frames on the same stream still arrive. Valid
peer-id text is preserved exactly; this check does not require that the withdrawal subject equals
its sender.

Service and relay withdrawals use the same ordered peer lane and have separate capacity from
announcements. A withdrawal that cannot reserve peer or process capacity is refused promptly
with `AnnouncementAdmissionRefusedEffect`. This bounded refusal policy remains in effect with
the current libp2p child-read pause support; a withdrawal is not parked for capacity. The refused
payload returns its parse charge before child close is observable, and local capacity pressure
does not lower the sender's score.

Accepted withdrawals retain a separate allowance until their callback and error reporting finish.
This allowance counts the JSON's seven-times wire-size estimate plus optional v1.1 extension
attachments. Active and pending withdrawals from one authenticated peer, across connections and
roots, can hold at most three quarters of `MAX_CONCURRENT_PARSE_BYTES`; all hosts in the process
share one withdrawal allowance of `MAX_CONCURRENT_PARSE_BYTES`. Direct decoded `MessageFrame`
and `ByteBuf` inputs use the same admission limits. Refusal reports retain bounded samples and a
scalar withdrawal count, so every refused withdrawal produces its effect without retaining the
payload. With no parked withdrawal, the existing read-idle deadline never waits for withdrawal
memory. Accepted removals leave parsing capacity and the connection's attachment-frame turn
available for ordinary RPCs. Host stop drains accepted withdrawals and returns their retained
allowance. It also owns refusal reporting for decoded frames that reach admission during transport
shutdown, and seals that reporting only after the transport has stopped.
Factory-created server hosts own these jobs: stop closes admission, interrupts the actions already
running, and drains accepted pending work before its future completes. An action that ignores
interruption remains owned until it returns. A completed stop leaves no queued or running
announcement job, and its dedicated executor shuts down. Direct users of
`UnifiedRpcProtocolBinding` must use `withHostLifecycle` and stop the returned host to obtain
this ownership. Callback failures use the original transport error-reporting path.
If that reporter also throws, host stop completes exceptionally with the total failure count
(saturating at `Long.MAX_VALUE`) and the latest sixteen diagnostics. Each diagnostic copies only
the exception class name (at most 256 characters) and message prefix (at most 1,024 characters)
for the reporter and original callback. A message getter which throws is recorded by its class.
The host keeps no original exception, cause, or suppressed graph from completed work, so returned
payload reservations cannot leave an uncharged input history behind.

UrlProtocol uses a gossip-based peer discovery mechanism. When peers join the network or register services, announcements propagate through the network:

1. **Service Announcements**: When a service is registered, the node broadcasts a `ServiceAnnouncement` message to all connected peers
2. **TTL-based Propagation**: Each message has a TTL (default: 7 hops). Receiving peers decrement the TTL and re-broadcast until TTL reaches 0
3. **Deduplication**: The `GossipMessageTracker` suppresses a message while its ID remains in the retained window. Its five-minute time TTL is paired with a hard 10,000-entry bound; a live-entry storm evicts oldest IDs first rather than growing until time expiry. At more than 10,000 unique IDs per five minutes (about 33.3 IDs/second), capacity can evict an ID before its time TTL, and a delayed copy of that ID is then processed again. The message's hop TTL (default: 7), not the tracker's time TTL, is the final bound on one re-forwarding lineage; repeated external re-injection after eviction can start additional bounded lineages.
4. **Peer Exchange**: Nodes periodically exchange their known peer lists, enabling organic network growth. Each `PeerInfo` may include a `serviceLifecycleTimestamps` map containing provider-authored lifecycle timestamps for its advertised services. Peer announcements and relay peer lists carry the same optional map so the ordering information survives each discovery path. A missing map or a missing service entry means that lifecycle value is unknown, as it is for catalogs written by older peers. The map is an additive JSON field, so older readers continue to consume the peer ID, addresses, and service list. `PeerInfo` exposes an immutable snapshot of the map, and its copy, equality, hash, and destructuring operations include that snapshot. A PeerExchange response is a durable acknowledgement: request-side received peers are applied before the response is written, while response-side follow-up processing remains fire-and-forget. A request may set `requestedResponsePeerIds` to receive only the submitted peer records it needs to confirm, plus the responder's own record when its Noise-authenticated peer ID is explicitly requested. Other third-party records remain excluded; omitting the field preserves the full-view response used for discovery.

```
Node A registers service → broadcasts to Node B, C
                          ↓
                    Node B re-broadcasts to Node D, E (TTL-1)
                          ↓
                    Node D re-broadcasts to Node F (TTL-2)
                          ...continues until TTL=0
```

#### Late-Joining Peers: Best-Effort Replay and Eventual Consistency

Relays replay a bounded, recent set of service announcements when a peer registers. This is a
best-effort acceleration for late joiners, not a durable history: each relay retains at most 1,000
distinct service-announcement payloads, refreshes move an entry to the newest position, and the
oldest entry is evicted when the bound is exceeded. A late joiner therefore discovers services
inside the retained window immediately, while services beyond that window become visible through
normal gossip, an explicit query/peer exchange, or a later provider re-announcement.

The recovery contract is eventual consistency through periodic re-announcement. A global service
registered with the default `ServiceRegistrationConfig` re-announces every five minutes, so an
announcement omitted from a relay's bounded replay window is repaired on a later cycle. Setting
`ServiceRegistrationConfig.reannounceIntervalMs` to `0` disables that recovery path; an operator
that does so must arrange explicit calls to `ServiceRegistration.reannounce()` or accept that a
peer joining after the original announcement was evicted may not discover the service until some
other query or fresh announcement occurs.

When a registered client sends gossip into a relay, `RelayService` retains sole ownership of the
registered-client fan-out decision and transport. Relay-aware gossip handlers receive the sender
and ingress-relay identities plus that ownership flag, allowing their registry rebroadcast path to
exclude both peers without duplicating the hub-to-leaf distribution. Ownership does not promise
delivery when relay validation rejects the message.

Compatibility is directional. A new `RelayService` still invokes the legacy callback for a handler
compiled before `RelayIngressGossipHandler`; the relay also keeps its own fan-out so that mixed
deployments may duplicate a message but cannot lose registered-client delivery. A handler compiled
against `RelayIngressGossipHandler` requires a protocol version containing that interface at runtime,
so consumers must upgrade the protocol dependency before (or together with) the relay-aware handler.

### Universal Relay Architecture

**Every node in the UrlProtocol network participates as a relay by default.** There is no dedicated relay infrastructure - instead, any node with a publicly routable IP address automatically serves as a relay for NAT-ed peers.

#### How It Works

1. **Public IP Discovery**: When a node starts, it learns its public IP address through peer observations (the Identify protocol reports what address peers see). After the first peer connection, `queryObservedAddress()` queries the Identify protocol to learn our observed public address. Nodes can also set their public IP explicitly via the `PUBLIC_IP` environment variable.

2. **Automatic Relay Capability**: Nodes with public IPs automatically accept relay registrations from other peers. The `RelayService` enables this functionality when `isPubliclyRoutable()` returns true.

3. **NAT-ed Peer Registration**: Peers behind NAT register with one or more publicly-reachable peers.

#### Public IP Detection

The `PublicAddressDiscovery` utility handles public IP detection with this priority:

1. **Explicit Configuration**: `PUBLIC_IP` environment variable (highest priority)
2. **Peer Observation**: IP addresses reported by connected peers via the Identify protocol (`/ipfs/id/1.0.0`)

`getMostObservedIp()` is always non-blocking — it returns the best known IP from explicit configuration or peer observations. Current-address reads do not wait for observation writers or their diagnostic output. The most frequently observed address is updated as observations arrive; `clear()` resets both the observation counts and the current observed address, while leaving an explicit public IP unchanged. On initial startup before any peers are contacted, the public IP may be unknown; wildcard listen addresses are filtered out and relay addresses are used instead. Once the public IP is discovered via peer observation (Identify protocol), subsequent address lookups include it and an updated PeerAnnouncement is broadcast.

With `-Dfoundation.url.protocol.debug=true`, each recorded observation writes its address and
committed count to standard output. Snapshot reads continue while that output is blocked.
Concurrent debug lines may appear out of count order; each line keeps its own observation's count.
Both `recordObservedAddress` overloads report output failures and thread interruption after storing
the observation, so a reported output failure does not undo the count. Invalid address strings
remain ignored.

```kotlin
// Explicitly set via environment variable
// PUBLIC_IP=198.199.106.165 java -jar UrlProtocol.jar

// Or let Identify discover it automatically after connecting to peers
// queryObservedAddress() is called automatically after successful connections
```


## Error Handling

The following exceptions may be thrown:

- `IllegalArgumentException` — Invalid URL format (wrong scheme, missing service identifier)
- `UrlResolutionException` — Service cannot be resolved (no peer found advertising the service)
- `UrlProtocolException` — Low-level protocol errors (connection failures, stream errors)
- `RelayRequestNotDispatchedException` — A relay request failed before any target handler received
  it. A caller may re-resolve or reconnect and resend after this specific exception.
- `RelayException` — A relay failure whose delivery state is unknown. Timeouts, target failures,
  and lost replies use this base type; callers must not automatically replay non-idempotent work.
- `SandboxException` — Sandbox creation or execution failures. The exception defines two
  read-only, nullable fields for describing the application exception behind a remote failure:
  `remoteExceptionClassName` (its fully qualified class name) and `remoteExceptionMessage` (its
  message, null when it had none). The sandbox implementation (UrlResolver) fills them in only
  for a failure the remote service itself reported (see "Remote application failures on the wire"
  below); they are null otherwise, including for every exception built with the two-argument
  constructor. Existing `message` and `cause` text is unchanged and remains available for
  diagnostics.

```kotlin
import foundation.url.protocol.UrlResolver
import foundation.url.protocol.UrlResolutionException

val resolver = UrlResolver(bootstrapPeers)

try {
    val service = resolver.openConnection("url://unknown.service/", SomeType::class)
} catch (e: IllegalArgumentException) {
    println("Invalid URL: ${e.message}")
} catch (e: UrlResolutionException) {
    println("Service not resolved: ${e.message}")
}
```

### Results that cannot be encoded

Request results travel as JSON, so they must be JSON-representable: `NaN` and infinite numbers
cannot be sent, at the top level, nested in a list or map, or inside a primitive array such as a
`DoubleArray`; neither can a value that contains itself. Receivers refuse any message whose JSON
nests deeper than `MAX_JSON_NESTING_DEPTH` (64) levels, counting the message object itself, the
`params` object of a request, and the small object that carries each byte array. So a result may
hold at most 63 levels of nested maps, collections, and arrays (62 if the deepest value is a byte
array), and a request parameter one level fewer (62, or 61 above a byte array). The same count
covers raw `JSONObject`/`JSONArray` values, including the Kotlin lists, maps, and primitive arrays
stored inside them, which org.json writes as nested JSON, and an `RpcError`'s `data` (which sits
inside the message and error objects). A value that would nest deeper is refused by the sender with
an `IllegalArgumentException` naming the depth it would reach; for a request parameter the caller
gets that exception before anything is sent.

Any other leaf object, such as a `Pair` or a data class, is sent exactly as org.json has always
written it: as its `toString()` text (`Pair(1, 2)` arrives as `"(1, 2)"`), or as `null` if it equals
`null`. Inside a raw `JSONObject`/`JSONArray` tree, an object held in a Kotlin/Java `Map`,
`Collection`, or array is converted by org.json as before, by reflection, into a JSON object of its
getters. That text is produced while the value is encoded. On libp2p and TCP server paths, anything
`toString()` throws (other than a fatal JVM error such as `OutOfMemoryError`) fails only that
request, with the error reply described below. A response leaf that throws is reported as a
`JSONException` naming its field path, for example `Cannot encode RPC value at 'result.v':
IllegalStateException: lazy field not loaded`, with the original exception as its cause. Lists and
arrays use indices (`result.items[1]`); keys containing dots or quotes use JSON bracket notation.
Raw JSON writer failures retain that location too, including when earlier raw JSON text is
malformed. Raw floating-point arrays name the exact index and NaN/Infinity value; raw cycles
within the receiver's depth limit name the first ancestor back-reference. Shared acyclic containers are accepted. Diagnostics follow
the actual encoded tree: discarded extra fields on an accepted binary marker and the container
contents hidden by a `JSONString` serializer are not treated as written values. Field paths are computed only after encoding fails and retain at most 1,024 characters,
with `<truncated: N chars omitted>` counting omitted rendered path characters between a bounded
beginning and ending. Literal `<` characters in keys use JSON `\u003c` escapes, so real key text
cannot look like an omission marker. Counts include text hidden by earlier omissions. The path keeps
the failing leaf, and each key segment is bounded too. Non-string and comparator-sorted map keys
reuse the text produced when the encoder first renders them. If a typed map key's first rendering
fails, its class name supplies a safe segment. Diagnostic failures are suppressed on the original
encoding failure; they never replace it or prevent its error reply. Reporting never serializes a
key or value again. Each leaf occurrence calls `toString()` at most once. A `toString()`, or a chain of getters, that recurses without bound (for example on
a 200 000-deep chain of `Pair`s) overflows the stack, as it always has: that is a defect of the value
itself, and the `StackOverflowError` is not caught.

Finally, the libp2p server checks the exact JSON bytes of every computed response, and
`Libp2pRpcStream` checks every outgoing request against the limits receivers enforce before parsing: at most
`MAX_JSON_FRAME_BYTES` bytes, `MAX_JSON_NESTING_DEPTH` levels, `MAX_JSON_ELEMENT_COUNT` elements, and
`MAX_JSON_STRING_LENGTH` bytes per string. This also covers a `JSONString`'s own text. A message
that would break one is refused with an `IllegalArgumentException` that gives the receiver's reason,
for example `Cannot send RPC message: every receiver would refuse it. Refused JSON message: element
count 1000001 exceeds MAX_JSON_ELEMENT_COUNT=1000000`.

On protocol 1.1 a result also cannot carry more than 1000 byte arrays.

On the libp2p server path, a result refused for any of these reasons fails only its own request,
at once, and the stream keeps serving later requests. A raw `RpcRequestHandler` served by
`Libp2pHostFactory.createServerHost` answers with an `INTERNAL_ERROR` reading `Internal server
error: the result returned for '<method>' could not be encoded for transport: JSONException: JSON
does not allow non-finite numbers.` (the same text UrlResolver uses for services it serves itself;
it says `the error returned` when the handler's own error response is what could not be encoded,
and is cut to `MAX_REMOTE_FAILURE_MESSAGE_CHARS` characters). This reply carries no
`remoteApplicationFailure`, because encoding the result is not the service's own exception.

Raw numeric and ancestor-cycle refusals expose `RpcValueEncodingException.fieldPath` and `problem`.
The exception retains its absolute envelope path, such as `result.raw.samples[1]`; the default
server reply describes the returned value directly: `the value at 'raw.samples[1]' is NaN, a
non-finite number, which JSON does not allow.` An owner returning its own failure message still
controls that text. Ordinary exception diagnostics retain their existing class/message format.

When a result is refused, its forwarded bytecode attachments and input streams are released at
all positions in maps, collections, object arrays, and raw JSON trees, including beyond the
refused nesting depth and inside cycles. Repeated references to the same stream are released
once. A failed close is attached to the original encoding failure, and the remaining resources
are still released before the libp2p error reply is sent. Streams already read and closed during
encoding are not closed again when a later value is refused.

A request handler that registers resources outside the transport can implement
`RpcResponseEncodingFailureHandler` and pass the same object to
`Libp2pHostFactory.createServerHost` (protocol 1.0 or 1.1) or `TcpServer.withHandler`.
The optional interface leaves `RpcRequestHandler` and existing host constructors unchanged:

```kotlin
suspend fun onResponseEncodingFailure(
    request: Libp2pRpcProtocol.RpcRequest,
    response: Libp2pRpcProtocol.RpcResponse,
    effectHandler: EffectPropagator,
    failure: Throwable,
): String?
```

The callback runs exactly once for a response whose encoding fails, including optimized bytecode
headers, before its error reply is written. It receives the original request, computed response,
effect handler, and encoding failure. Transport-owned refused streams have been released first.
The callback runs outside transport accounting and write locks, may re-enter the connection, and
may suspend even after its request is cancelled. Closing a connection can prevent the reply but
does not abandon cleanup. Successfully encoded responses, including ones whose later delivery
fails, do not invoke this callback.

Release only registrations belonging to the refused response. Return the complete `INTERNAL_ERROR`
message, or null to retain the transport diagnostic; text is bounded by
`MAX_REMOTE_FAILURE_MESSAGE_CHARS`. An ordinary callback failure is suppressed on the encoding
failure and included alongside it in the error text. Fatal JVM failures propagate after cleanup.
An adapter wrapping a handler must retain this optional interface. `RelayService` handlers carry
already encoded payload bytes, so the RPC host or adapter that encodes those bytes owns this
notification; forwarding an encoded error does not invoke cleanup again.

If a response source is also owned by a registration, implement
`RpcResponseEncodingResourceHandler` (which extends the failure interface) and return a fresh
`RpcResponseEncodingLease` from `responseEncodingLease(request, response, effectHandler)`.
The host requests it before encoding, even if the connection has just closed. List every
registration-owned `InputStream` reachable in the result or error data. The lease copies these
identities immediately; changing the supplied collection cannot transfer ownership. The protocol
neither closes them after reading nor closes them on refusal. The registration owner stays their
only closer, including connection close during encoding. The lease does not transfer ownership
of a `ForwardedStreamingBytecodeResponse` carrier, whose existing completion contract still applies.

The lease's completion callback runs once, outside transport locks, after frame preparation or
its failure and before `onResponseEncodingFailure`. Null means preparation succeeded; it does not
promise delivery. The owner may close a source there when it needs it only for encoding, or retain
its registration until the consumer releases it. Connection close can race reading and can make
encoding fail; it never gives the protocol a second close claim. Completion can re-enter the host.
A lease is single-use and cannot survive a restart. An ordinary completion failure is attached to
an existing refusal; if preparation succeeded, it propagates as a preparation failure. The failure
callback then gets its normal cleanup opportunity. Existing failure-only handlers, constructors,
and `RpcResponse.toJson` keep their signatures and ownership behavior.

If `responseEncodingLease` throws, the response was not given a lease, so the host snapshots its
result and error data and closes each reachable unborrowed resource once before calling
`onResponseEncodingFailure`. Ordinary snapshot and close failures are attached to the lease
exception; the request receives an internal error whose default text names that exception. For
libp2p, a forwarded response is tracked before the suspendable lease callback, so connection close
can still release it while that callback is running.

Custom NetworkProvider and local or forwarded relay dispatchers encode responses themselves.
Use `encodeRpcResponse` rather than splitting `toJson` and byte writing across independent resource
owners. It returns checked JSON bytes and separate binary attachments, and completes the lease
before returning or throwing. Optimized bytecode carriers keep their dedicated writers, as with
`RpcResponse.toJson`. On refusal, invoke the result owner's failure callback before sending
the per-request error. Forwarding already encoded bytes does not acquire another lease. For example,
a source used only while preparing a response can have this complete standalone lifetime:

```kotlin
import foundation.url.protocol.Libp2pRpcProtocol
import foundation.url.protocol.RpcResponseEncodingLease
import foundation.url.protocol.encodeRpcResponse
import java.io.ByteArrayInputStream

val source = ByteArrayInputStream("hello".toByteArray())
val response = Libp2pRpcProtocol.RpcResponse.success("request-1", mapOf("text" to source))
val lease = RpcResponseEncodingLease(listOf(source)) { _ -> source.close() }
val encoded = encodeRpcResponse(response, binaryAttachments = true, lease = lease)
// The dispatcher writes encoded.jsonBytes followed by encoded.attachments using its framing API.
```

`TcpServer` retains its `Handler error: <reason>` diagnostic when no callback supplies a message.
A nonfatal failure while preparing a response fails only that request, and the connection keeps
serving later calls. TCP responses still use their existing receiver-limit policy; the libp2p
final-byte checks described above are unchanged.

### Remote application failures on the wire

When a service's own request handler throws (the exception reaches `ServiceRoutingHandler`, or a
raw `RpcRequestHandler` served by `Libp2pHostFactory.createServerHost` or `TcpServer` throws), the error response
keeps its existing `code` and `message` text and additionally carries the exception's fully
qualified class name and message in the optional `RpcError.data` field. Older clients ignore the
field; responses from older servers omit it.

```kotlin
import foundation.url.protocol.RemoteApplicationFailure
import foundation.url.protocol.remoteApplicationFailure

val response = stream.sendRequest(request)
val failure: RemoteApplicationFailure? = response.error?.remoteApplicationFailure
// e.g. RemoteApplicationFailure(className = "java.lang.IllegalStateException", message = "wrong state")
```

`remoteApplicationFailure` is null for transport, timeout, closed-stream, routing, and legacy
errors, and for cancellations and interruptions. An exception implementing `ServiceInfrastructureFailure` (a failure of
the service-hosting machinery, such as an expired handler session or a malformed resume) keeps its
error code and text but is never reported as an application failure; use
`ServiceInfrastructureException` (an `IllegalStateException`) or
`ServiceInfrastructureArgumentException` (an `IllegalArgumentException`, which keeps
`INVALID_PARAMS`). Servers build these errors with `applicationFailureRpcError`;
other error formats that embed the same shape (for example relay responses) use
`applicationFailureData` and `remoteApplicationFailureFromData`. The structured message copy is
capped at `MAX_REMOTE_FAILURE_MESSAGE_CHARS` characters; the full text stays in the error message.
`ServicePushException` (a server push the client connection could not take) is a
`ServiceInfrastructureFailure` too.

#### Proxy handlers

A handler that forwards each call to another service (a proxy, gateway, or facade) must report
details only for the leaf service's own exception, so it has two obligations:

1. **Forward the backend's failure unchanged.** Throw an exception implementing
   `ReportsRemoteApplicationFailure` whose `remoteApplicationFailure` is the backend error's
   `remoteApplicationFailure` (null when the backend reported none), for example the ready-made
   `ForwardedApplicationFailureException`. Its own class and message then only shape the error text;
   the reported details are the leaf's.
2. **Mark the proxy's own failures as hosting failures.** Failures of the proxy itself (a backend it
   cannot reach, a route or bridge mismatch, a closed backend connection) must implement
   `ServiceInfrastructureFailure`, or the proxy's exception class would be reported as the
   application's.

```kotlin
import foundation.url.protocol.ForwardedApplicationFailureException
import foundation.url.protocol.remoteApplicationFailure

// Inside the proxy's ServiceHandler.handleRequest:

val response = backend.sendRequest(request)
val backendError = response.error ?: return response.result
throw ForwardedApplicationFailureException(
    "Backend call failed: ${backendError.message}",
    backendError.remoteApplicationFailure
)
```

Throw the forwarding exception directly from the handler: only the exception that leaves the handler
is inspected, so a wrapper around it (for example an `ExecutionException` from `Future.get()`) is
reported as the proxy's own failure. Proxies can be chained; each hop forwards the leaf's details
unchanged.

## Network Health

Monitor the health of the URL protocol network at: https://url-network-health.wasmserver.com/


### Connection opening and caller departure

Connection opening uses the injected `Clock` for its shared deadline and preserves each address's
exact failure when it is published before that deadline. An address still dialing at the deadline
is cancelled and reported as `address '<a>': dial did not complete within the <N> ms connection budget`,
without waiting for the provider's cancellation or timeout message.

If the caller leaves before a dial completes, the protocol closes the returned stream. The caller's
interrupt flag remains set when interruption ends the wait.
If deadline cancellation or stream preparation fails before delivery, the remaining cleanup still
runs and closes the undelivered result. The first failure reaches the caller, with later cleanup
failures attached as suppressed exceptions.
Every exit attempts orderly dial-executor shutdown, with an immediate shutdown fallback if it fails.
Dial workers also expire after one idle second, so their lifetime does not depend on either shutdown
call succeeding. In-flight dials and queued stream closes retain their cleanup ownership until they finish.

Cleanup of a result the caller did not consume runs independently of that caller. If closing the
result fails, a `NotificationEffect` reports the peer, method path and original close failure to
the effect scope used by the opening call; the effects runtime prints an unhandled warning when
no handler is installed. A successful dial returned after the connection deadline is closed and
receives the same address-specific budget-exceeded cause.


## Peer freshness

Peer catalogue and presence records can carry an optional `freshness` JSON object:

```json
{"observerPeerId":"observing-peer","observationId":"observation-identifier","remainingLifetimeMillis":30000}
```

The field is supported by `PeerInfo`, `ServiceInfo`, `GossipProtocol.PeerAnnouncement`,
`GossipProtocol.ServiceAnnouncement`, and `RelayProtocol.RelayAnnouncement`. Exchanges
that contain these records use the same representation. The observer identifies the
node that directly observed the subject; the observation identifier distinguishes that
observation from earlier observations by the same node. Forwarding a record preserves
both identifiers. Receiving a catalogue record is not a new source observation.

`remainingLifetimeMillis` is a nonnegative duration, not a wall-clock timestamp. Nodes
therefore do not need aligned wall clocks. An observer chooses a finite lifetime under
its presence policy, and a reader caps imported duration at its own finite horizon. A forwarding node consumes the time the record spent there; it
never restarts that lifetime. For example:

```kotlin
import foundation.url.protocol.PeerFreshness

val observed = PeerFreshness("observing-peer", "observation-identifier", 30_000L)
val forwarded = observed.agedBy(2_000L) // 28,000 milliseconds remain.
```

`PeerFreshness.agedBy` reaches zero when elapsed time meets or exceeds the remaining
lifetime. Negative elapsed time is rejected. Carrier `withFreshness` and
`withFreshnessAgedBy` methods return updated values. Copying a record for the same
subject preserves freshness; changing its peer or provider identity clears it.
Peer announcement/catalogue conversions preserve the evidence for the same subject.
The elapsed-time forwarding overloads consume the duration supplied by the caller.
The existing no-argument forwarding methods preserve metadata only as a hint; they
cannot establish a renewed lease without elapsed-time accounting.

A reader using this metadata for registry pruning must account for transport time.
For a response to a request, the responder samples or recomputes the remaining lifetime
after the request begins, and the reader conservatively consumes the whole locally
measured request duration. Unsolicited forwarded announcements remain hints until
confirmed through such an exchange. Repeated receipt of the same observation must
never move its already-established expiry later. Clock rates must be comparable;
known rate uncertainty requires conservative duration conversion. This protocol module
does not implement the resolver's observation, scheduling, or removal policy.

Persist the source identifiers and remaining duration with enough clock context to
account for elapsed time across a restart. When that continuity cannot be established,
restore the record as unverified with no usable remaining lifetime. A saved wall-clock
timestamp alone does not establish continuity when that clock can move.

The extension keeps the existing protocol identifiers and JSON message versions.
Older readers ignore the unknown member; new readers accept records without it.
Missing, malformed, or unrecognized freshness metadata means imported, unverified
presence and does not make the enclosing peer record unusable. Each source identifier is limited
to 1,024 characters when reading JSON; longer identifiers are treated as absent evidence because
they exceed the retained metadata allowance. An older forwarding
node may omit the metadata, in which case a new reader again treats it as unverified.
A mixed-version exchange therefore preserves routes without promising a source lease.

PeerRegistry also records imported observation identifiers so a repeated identifier cannot
renew its own history. For current-format peer records, finite history intervals begin again
when the registry restores them, keeping each identifier known for its full interval after a
restart. A prior-format peer record without a history list keeps its current identifier
permanently as one bounded compatibility entry, using the `Long.MAX_VALUE` retention interval.
Repeated restores keep that entry in place without adding copies; newer identifiers continue
to use their own finite intervals. Together, the prior-format records contribute a one-time
identity set bounded by their saved provenance entries. New finite identities do not enlarge
this permanent set.
Live relay-only retention requires timely usable evidence renewal; optional metadata
alone cannot guarantee it when no renewed observation reaches the reader.

### Registry source freshness

Peer catalogue and exchange records may include an optional `freshness` object
with `observerPeerId`, `observationId`, and `remainingLifetimeMillis`. Older JSON
readers ignore this object, and current readers accept records without it. The
existing protocol identifiers and negotiation rules remain unchanged. An absent
or invalid object is an imported, unverified hint; receiving a descriptor does
not establish direct contact.

A successful local observation creates a new observation identity. Exporters
consume time since that observation, and forwarding readers consume both record
residence time and the full locally measured request-to-response interval. They
never restart a source duration from receipt time. A response must be sampled
for the current request before its freshness can be accepted. Unsolicited records
and responses without that timing guarantee remain hints.

`UrlProtocol.onPeerExchangeResponse(peers, exchangeElapsedMillis)` accepts such a
correlated response. The transport caller measures the complete exchange with
an elapsed clock. Existing `onPeersReceived` overloads keep accepting legacy and
unsolicited records as hints. `PeerRegistry.addImportedPeer` provides the same
admission contract to integrations; explicit `addPeer` remains a caller action.
`PeerObservationLog.getSourceFreshness` and
`PeerRegistry.getImportedPeerFreshness` return the duration remaining at export.
Registry admission also consumes time spent validating or waiting to accept the descriptor.
The default elapsed clock uses the JVM duration clock. A custom `Clock` must
represent elapsed time; its visible backward movement expires positive evidence.

Each observed backward step starts a new Clock timeline. Overlapping reads keep their
own source values, but reads started on an earlier timeline cannot age retained records
or supply usable imported duration on the current timeline. A removal recorded from an
earlier timeline starts its full retention interval at the next current sample.

Bootstrap responses may forward still-positive imported evidence after a later local
negative check. That check describes this node's route attempt; it does not cancel
another observer's bounded presence statement, which may use a different route.
Forwarding preserves its identifiers and consumes its existing duration. Once that
original imported expiry is reached, a peer without qualifying local success is
omitted. The local negative check never renews imported evidence.

Repeated observation identities cannot extend their first accepted expiry.
New source evidence can extend a peer's evidence and invalidates a captured
removal candidate. A legacy receipt never renews an existing lifetime. After
removal, it cannot create a new lifetime for the same identity for 300 seconds
within the current process. At that boundary it may again be admitted unverified.
A genuinely newer, unexpired source observation can be accepted sooner.

`PeerFreshnessWindow` retains each observation identity for the longer of its reader
horizon (300 seconds by default) and the source remainder declared at first acceptance.
This history does not grant additional usable duration: the first accepted expiry stays
fixed even when the source policy is longer than the reader cap. A source must bound
its declared remainder by its finite source policy. Repeated receipts never extend
history. Removal markers retain the reader horizon. Registry history has no fixed
entry-count admission limit: its size follows distinct observations received during
their applicable retention intervals.
This preserves newer source evidence even when observations arrive frequently.
An explicit `maxEntries` limit is available to helper callers; when full it declines
additional source histories and conservatively delays legacy admission when a
removal marker cannot fit. A caller choosing this limit must account for declined
evidence before deciding that a peer has no usable source evidence.

Persisted records retain every observation identity still needed by the history window and restore it
with zero usable duration, re-anchoring each original retention interval so repeated
receipt cannot renew a restored identity. A new observation identity or local success
can establish fresh evidence; older files without history still keep their saved
provenance identity at zero.

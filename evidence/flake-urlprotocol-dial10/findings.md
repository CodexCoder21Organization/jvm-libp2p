# flake-urlprotocol-dial10 findings
Launch: 2026-10-19 02:24:26 UTC. Hard deadline: 03:54:26 UTC (90 minutes).

## Plan
- DONE: Read continuation, dial1/3/8/9 findings, campaign design decisions, fresh clone README/FORK; inherit phase measurements and five assertion-red attempts, do not repeat discovery.
- IN PROGRESS: Finish N3 identity preparation, own-proof green and product-only reversal. Investigate inherited own-proof fixture failure before including it.
- PENDING: Content-selected neighbours, authorized fork suite exactly once, separate test and code reading passes; draft PR against develop.
- PENDING: Measure local-snapshot four-test batch if harness permits; list first-dial-only classes as next separate concern, no implementation.
- PENDING: Push evidence and source with exact leases; audit head CI once without watching, clean processes, final required line.

OBSERVED: Fresh fork cloned into this lane/fork at develop ecbc4a35; no root AGENTS. Source resumed at 81cf6254 plus the second red-only source milestone a619115d (cherry-picked as 53d527ba). Remote new work branch wip/flake-urlprotocol-dial10 started absent, pushed red-only 53d527ba using exact absent-head lease. Source unchanged from the inherited baseline. Prior native red logs copied as evidence, not re-executed. Five attempts are assertion-red, zero retries. No existing main test edits. Read github-repos, fix-flakey-test, status-report skills; lane restrictions override delegation, challenge logging, handoff writes, publication, CI watching and full consumer suite. rg is unavailable, so searches use grep/find.

## Inherited measurement and owner table (verify-don't-redo)
## 2026-10-19 02:14 UTC — measured phases before change
Measurement units: wall ms unless identified as compiler-thread/event time. Each column is the mean across four concurrent fresh-JVM probes; second dial uses a separate client and connection in that same JVM. No connection is reused. JFR-only values are primary; the minimal agent supplies nested math intervals. The broader agent adds pipeline/static-initializer intervals and is retained separately because its transformations add work. Phase durations overlap on two endpoints and with compiler threads; they cannot honestly be summed as a causal allocation of one wall deadline.

| Measured item | Cold first dial | Second dial in same JVM |
|---|---:|---:|
| Provider to connection-ready (JFR only) | 512.99 | 76.32 |
| Class loads (JFR only, count) | 386.75 | 1.50 |
| Union of class-load intervals (JFR only) | 202.03 | 0.65 |
| Compiler-thread total overlapping phase (JFR only) | 1056.49 | 114.71 |
| Compiler activity wall union (JFR only; overlaps every other row) | 442.97 | 75.57 |
| Math interval wall union (minimal observer) | 149.13 | 33.38 |
| Class-load union (minimal observer) | 263.43 | 0.00 |
| Remaining wall after class-load/math precedence (minimal observer; includes pipeline/waits/other) | 209.14 | 58.82 |
| Curve25519 evaluations (minimal observer, exact count) | 10.00 | 10.00 |
| Identity signatures (minimal observer, exact count) | 2.00 | 2.00 |
| initializeHandshake wall union (minimal observer; includes math) | 65.23 | 12.15 |
| sendNoiseStaticKeyAsPayload wall union (minimal observer; includes sign/protobuf/write) | 74.24 | 15.12 |

OBSERVED frame families (JFR-only, four first dials): active samples include Curve25519.eval/evalCurve/mul under initializeHandshake and HandshakeState.readMessage/writeMessage; classloader samples include URLClassPath/JAR inflate/defineClass; pipeline/mux samples include NettyInit, Negotiator and Yamux setup. Cold loads include HandshakeState, NoiseIoHandshake, UShortLengthCodec, NoiseXXCodec, Spipe.NoiseHandshakePayload, Netty buffer-event classes, Yamux classes, and consumer close-record classes. Crypto-provider key generation has already run during host creation; no entropy-source stall appears. Compiler work includes Curve25519.mul/square/reduce and URLClassPath.getResource. This is first-use work plus ongoing compilation, not one unexplained 900ms park.
OBSERVED waits: callers park on the public dial/controller future while actual work runs on the two Netty I/O workers. Native EPoll samples include idle boss accept loops, not handshake-worker starvation. Minimal-agent runs have no shared-crypto-worker queue in this one-connection-per-JVM shape. The detailed agent produced 2 first-dial failures /4, including one observed connection-ready at 942ms; it is evidence of frame ownership, not an unaltered rate measurement. Negative stream/handler values in a failed diagnostic mean unset timestamps. Full failure stacks retained in work-2.log/work-3.log; no unchanged test rerun past a failure.
INFER: The dominant directly timed first-use family is class loading (170–285ms JFR-only unions; 197–305ms with the minimal observer). Cold math is also material (~110–118ms excluding overlapping class loads in minimal runs). N2 shared-worker sizing is refuted for this local one-connection shape, not for every concurrent workload. N3 can remove a concrete, reusable part of that first-use path: Noise currently derives the same static public key and signs/serializes its identity payload at each connection, after start has completed. The remaining TCP/mux and consumer classes are separate readiness work; no claim that N3 alone meets the original bar before qualification.

The mechanism I am about to test is that a host's Noise binding defers static identity preparation until each connection: initializeHandshake calls localNoiseState.setPrivateKey on every channel, and sendNoiseStaticKeyAsPayload signs and serializes the unchanged host identity again. The evidence that demonstrates it is 10 Curve25519 evaluations and 2 identity signatures on each two-ended connection, including the second connection; initializeHandshake and payload creation are timed above. The owner is NoiseXXSecureChannel, constructed once for a host before start. One self-contained N3 concern will prepare and privately retain that identity there, while per-channel ephemeral keys and mutable handshake state remain per channel. This is useful preparation reused by connections, not a test warm-up dial.

### N3 ownership and participant table (derives only the supplied host-static / channel-ephemeral row)
| Resource/participant | Creates/owns/closes | Observable / hostile proof |
|---|---|---|
| Static Noise key and signed payload | Noise binding captures process key at construction; binding privately owns immutable preparation for its lifetime; no socket, executor or close token | Supplied PrivKey counts exact signatures: preparation before host start returns; reconnects do not repeat it. |
| Each channel's local DH copy and fresh ephemeral key | Handshake owns independent mutable state and destroys/clears its temporary ephemeral bytes as before | Existing fresh-ephemeral/isolated handshake proofs; real parallel connections on one host verify no shared state corruption. |
| Supplied identity publicKey/sign callbacks | No new owner lock held; exceptions propagate out of preparation before transport is constructed | Public construction failure preserves the supplied signing exception; returned signature bytes are copied by protobuf. |
| Process static-key property | Existing public property retained; a binding captures one private copy when constructed | Public child-JVM mutation proof: later array mutation/replacement cannot change already-prepared host identity or trigger another sign. |
| Standalone NoiseIoHandshake constructors | Existing public signatures retained; direct callers get their own private preparation | Existing embedded-channel and constructor compatibility checks. |
No journal, persistence, lock-order edge, condition wait, worker admission, shared-subscriber lifetime, or loop scheduling rule is changed. Traversal sites remain enqueueCrypto for every frame and processHandshakeFrame/read/write/split; only their static inputs change. Preparation callbacks run before any binding is published to a host, so they cannot re-enter a partly published host. A cached state object is never returned or mutated after publication. New gaps outside this N3 row follow scope freeze; none is silently added to this concern.
Existing-test edit ledger remains EMPTY. Plan: assertion-red public counting proof, checkpoint; implement owner preparation; green and product-only reversal red; content-selected neighbours and fork suite once; local-snapshot CI-shaped batch if feasible; draft fork PR develop. No consumer pin/publication.


## 2026-10-19 02:34 UTC — continuation audit
OBSERVED: dial9's later local evidence contains an unreported first-attempt own-proof failure after its N3 change: 4 PASS / 1 FAILED. The child mutation/reconnect proof reaches PingProtocol.PingInitiator.ping on connection two after connection-one closure terminated the binding's shared timeoutScheduler. RejectedExecutionException names a Terminated ScheduledThreadPoolExecutor at Ping.kt:102, child line 124. Complete inherited Gradle stack retained. This is not a signing/state failure and must not be rerun unchanged to claim green. Source directly shows PingInitiator.onClosed shuts the outer PingProtocol.timeoutScheduler and reconnect uses that same protocol object. It is a separate existing Ping ownership concern; any fixture resolution must keep assertions/bounds byte-identical or the red proof goes to evidence per the explicit scope rule.
INFER: Four other inherited green attempts are useful observations, not this lane's verification. N3 will be verified from the fresh clone and its own head. No class-eager-loading change will be added to N3.

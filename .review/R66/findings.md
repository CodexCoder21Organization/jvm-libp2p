Lane R66 review

Pinned head: e7153395f4a1a05860906366cc46e48ce8fc6422
PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/66

Plan
- In progress: read design, author evidence, repository guidance, and changed paths.
- Pending: review compatibility and delivery; run focused probes once with PARANOID.
- Pending: compare the twenty reported red cases on merge base and head once each using XML.
- Pending: verify remote head, write verdict and final file, stop all review tasks.

Scope: read-only review. Probe branch wip/laneR66-review only; no PR-branch changes, merge, publication, or deployment.

OBSERVED 2026-10-22T22:33:34.878037+00:00: remote head matches pin; merge base with develop is e15e0f1947842be7ef6836c116c14ba47cc8eea1. Read design, author findings/final, README, FORK and required testing sections. No repository AGENTS.md. This is Gradle/JUnit, with no kompile test command.
OBSERVED: existing Yamux/Mplex binding source and Stream source are unchanged. Capacity recognition requires the new handler, DATA, RST-only flags, one body byte, and value 1. Reset-body release is in a finally block. Existing tests cover partial/coalesced/reset ordering, completed/cancelled results, and two mixed Yamux directions.
INFER: a setup callback can finish the handler result before the mux close observer finishes the public controller. A focused public-API probe will check whether the recorded capacity cause remains authoritative. No implementation edit is planned.
OBSERVED: author notes report twenty red cases but omit exact identities. Requested the identity list while continuing independent review.

OBSERVED 2026-10-22T22:35:55.260894+00:00: head capacity controls complete: XML 24 cases, 24 passed, 0 red, 0 skipped, each once. Gradle has no test retry plugin/configuration. PARANOID set through the review init script. XML retained in .git/r66/capacity-results.
Current best mechanism hypothesis: a marked reset records the setup cause, then completes the protocol future; its synchronous callback can complete the pending handler result, which forwards into the public controller before close runs. Evidence for: AbstractMuxHandler.kt:430-436 and MuxHandler.kt:66-71. Evidence against: existing close-callback control passes; it does not complete the handler result inside that callback. Three focused review cases are now running once.

OBSERVED 2026-10-22T22:36:49.492775+00:00: focused review XML: 3 cases, 1 passed, 2 red, 0 skipped; all executed once. Both callback cases are red; mixed Mplex directions pass in one case.
P1: a pending public controller can report success or another error after a marked reset. The mechanism is that AbstractMuxHandler.kt:433 completes the protocol future before MuxHandler.kt:66-71 settles the public controller; a synchronous protocol callback completes the handler result, and forward(controller) accepts it despite the recorded setup cause. The evidence that demonstrates it is testProtocolCallbackKeepsCapacityControllerResult (controller succeeds) and testProtocolCallbackKeepsCapacityControllerError (controller reports IllegalStateException while protocol reports StreamCapacityException), each red on attempt 1. Both start with a pending public controller and use the real mux public createStream(StreamHandler) path.
Requested correction: make the recorded setup cause authoritative for every pending setup result before callbacks can publish a different result, while retaining existing completed/cancelled outcomes. No correction applied in this read-only review. XML and probe source are checkpointed on the review branch.

OBSERVED 2026-10-22T22:38:11.631567+00:00: selected network/native head controls produced XML 90 cases: 74 passed, 14 red, 2 skipped. This was one execution per case, no repeated attempt. Five classes contain the fourteen red cases. Their merge-base comparison is now running once. This selection is additional review evidence; it cannot identify the author's remaining six red cases without the omitted list.
OBSERVED: review branch checkpoints https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/laneR66-review contain the callback probes and their XML. Remote PR head remains pinned. No production source was edited.

Review table
| Item | Observation | State |
| --- | --- | --- |
| Binding negotiation | Existing Yamux mixed directions pass in author controls; both Mplex mixed directions pass in review probe. Selected ID checked. | Covered, binary comparison pending |
| Marker scope | Only capacity handler enables branch; DATA/RST plus exact one-byte value 1 required. Existing codec rejects unsupported flag combinations. | Source checked |
| Exact child | streamMap lookup; only local initiator receives typed setup cause. Sibling and parent controls pass. | Covered |
| Input ownership | Capacity branch releases body in finally; unknown/duplicate/late/partial/coalesced cases pass. PARANOID enabled. | Covered for executed paths |
| Callback ordering | Protocol future callback can publish success or another error into pending controller. | P1 demonstrated, 2/2 red first attempt |
| Completed results | Existing completed/cancelled result cases pass. | Covered |
| Callback locks | Capacity notification path holds no explicit lock around completion or pipeline calls. | Source checked |
| Twenty existing red cases | Fourteen red cases found in five selected native/network classes; merge-base run in progress. Author identities still absent. | In progress |

OBSERVED 2026-10-22T22:41:32.132748+00:00: baseline native/network comparison completed: 23 XML cases, 14 red, 8 passed, 1 skipped. All 23 have identical head outcomes. Public Stream and both existing binding classes have identical SHA-256 bytes and javap public JVM descriptors at base and head.
OBSERVED: 49 lifecycle controls and 5 additional Java host/API controls passed at head, each once. The identity gap remains: author evidence branch also omits the twenty-case list. Further focused mux and negotiation controls are running once; no complete suite has been started.

OBSERVED 2026-10-22T22:42:23.148543+00:00: located the author's archived XML by read-only file access. No source checkout was reused, edited or built, and no git command ran in another lane. Saved twenty exact identities in this review checkout; author full and baseline reports each contain the same twenty. Remaining six are in SubscriptionsLimitTest (3), GossipBackwardCompatibilityTest (2), GossipTwoHostTest (1). These are now selected for one independent execution per revision.

OBSERVED 2026-10-22T22:43:04.749937+00:00: existing mux and negotiation controls completed once: {'passed': 242}. No LEAK notices in review command logs. PARANOID is configured for each review test task. The twenty-case comparison is waiting only on the six exact pubsub cases at each revision.
P1 source references: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/e7153395f4a1a05860906366cc46e48ce8fc6422/libp2p/src/main/kotlin/io/libp2p/etc/util/netty/mux/AbstractMuxHandler.kt#L433 and https://github.com/CodexCoder21Organization/jvm-libp2p/blob/e7153395f4a1a05860906366cc46e48ce8fc6422/libp2p/src/main/kotlin/io/libp2p/mux/MuxHandler.kt#L71 .

OBSERVED 2026-10-22T22:44:59.863348+00:00: all twenty author-reported red identities were executed once at the merge base and once at the pinned head. Fourteen are red at both; six pass at both. There are zero missing cases, zero outcome differences and zero outer error-type differences. The remaining six all passed on attempt 1 at both revisions. Thus the author's twenty red outcomes are not all reproduced in this review, but none of these twenty cases shows a head-specific regression.

Twenty-case results (each entry: one attempt per revision)
| Class | Case | Base | Head |
| --- | --- | --- | --- |
| io.libp2p.core.HostTestJava | addPingAfterHostStart() | red | red |
| io.libp2p.core.HostTestJava | largeBlob() | red | red |
| io.libp2p.core.HostTestJava | largePing() | red | red |
| io.libp2p.core.HostTestJava | ping() | red | red |
| io.libp2p.core.RpcHandlerTest | test1() | red | red |
| io.libp2p.pubsub.gossip.GossipBackwardCompatibilityTest | testConnect_1_1_to_1_2() | passed | passed |
| io.libp2p.pubsub.gossip.GossipBackwardCompatibilityTest | testConnect_1_2_to_1_1() | passed | passed |
| io.libp2p.pubsub.gossip.GossipTwoHostTest | test message larger than mplex frame() | passed | passed |
| io.libp2p.pubsub.gossip.SubscriptionsLimitTest | existing peer subscribed to many topics() | passed | passed |
| io.libp2p.pubsub.gossip.SubscriptionsLimitTest | new peer subscribed to few topics() | passed | passed |
| io.libp2p.pubsub.gossip.SubscriptionsLimitTest | new peer subscribed to many topics() | passed | passed |
| io.libp2p.security.tls.TlsSecureChannelTest | [1] 0 | red | red |
| io.libp2p.security.tls.TlsSecureChannelTest | [2] 16 | red | red |
| io.libp2p.security.tls.TlsSecureChannelTest | [3] 65519 | red | red |
| io.libp2p.security.tls.TlsSecureChannelTest | [4] 65520 | red | red |
| io.libp2p.security.tls.TlsSecureChannelTest | [5] 65535 | red | red |
| io.libp2p.security.tls.TlsSecureChannelTest | [6] 65536 | red | red |
| io.libp2p.security.tls.TlsSecureChannelTest | incorrect initiator remote PeerId should throw() | red | red |
| io.libp2p.transport.quic.QuicKuboTestJava | pingKubo() | red | red |
| io.libp2p.transport.quic.QuicServerTestJava | tlsAndQuicInSameHostPing() | red | red |

Final verdict: CHANGES REQUESTED. One P1, no additional P2/P3 findings.
P1: pending controller completion can lose the recorded capacity cause when a protocol-result callback completes the handler result. A controller that was pending when the marked reset arrived reports success in testProtocolCallbackKeepsCapacityControllerResult, or IllegalStateException in testProtocolCallbackKeepsCapacityControllerError; the protocol future reports StreamCapacityException in both. The two deterministic public-API probes are red on their first and only attempt. The source completes the protocol future at AbstractMuxHandler.kt:433 while the handler result still forwards without checking the recorded cause at MuxHandler.kt:71. Make the recorded cause authoritative for every pending setup result before callbacks can publish a different result. Existing completed/cancelled results must keep their outcome. No implementation change was made.
Evidence branch: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/laneR66-review . Probe source: libp2p/src/test/kotlin/io/libp2p/mux/yamux/YamuxCapacityReviewTest.kt. Red XML: .review/R66/probe-results. Exact base/head comparison: .review/R66/twenty-case-comparison.json.

Bar audit
- DONE: compatibility and negotiation source review; existing Stream/Yamux/Mplex binding compiled bytes and public descriptors match exactly. Both mixed Yamux directions passed in author controls, and both mixed Mplex directions passed in one additional review case.
- DONE: delivery/frame/buffer/callback review. All 24 author-added cases pass first attempt; 242 existing mux/negotiation and 49 lifecycle cases pass first attempt. Two additional delivery probes expose P1; one mixed Mplex case passes. PARANOID set for all review tasks; no LEAK notices observed in logs. No explicit lock spans the new capacity callbacks.
- DONE: exact twenty-case base/head comparison, first attempt only. Fourteen red and six green on both; no differing case. Author claim that all twenty are currently red is not reproduced for the six pubsub cases listed above.
- DONE: verdict recorded. No PR-branch push, source correction, merge, queue entry, publication, deployment, production action, CI watch, or complete suite. Review commits and probes only on wip/laneR66-review.

Reading: approved rFURR2-design.md; rFURR3-findings.md and rFURR3-final.md; repository README.md and FORK.md; Testing Architecture sections Test-Driven Development and Never Calibrate a Reproducer to a Machine; relevant production and test sources; author archived XML (read-only) for exact identities. No existing test was edited and no timeout or iteration count changed.
Plan complete: read/context DONE; compatibility/delivery DONE with P1; twenty-case comparison DONE; verdict/final/checkpoint DONE after final head/process checks.

# Fast message ID attribution — lane 238

The failure is a pre-existing test completion bug. It does not require https://github.com/CodexCoder21Organization/jvm-libp2p/pull/48 . The original test naturally fails at the same line on unchanged develop. A filtered duplicate never reaches the delivery queue, so an empty timed poll is not acknowledgement that its queued inbound task ran.

| Native scenario | Exact PR 7a27e489 | Develop 3e88aeef | Corrected 82db409e |
| --- | --- | --- | --- |
| Original test, 20 fresh forks | 20 pass / 0 fail | 16 pass / 4 fail | — |
| Held event thread, original completion assumption | 10 expected assertion failures / 10 | 10 expected assertion failures / 10 | — |
| Same held ordering, release and await actual inbound completion | 10 pass / 10 | 10 pass / 10 | — |
| Corrected test, fresh forks | — | — | 30 pass / 30 |
| Restored-source neighbors | — | — | 5 pass / 5; Java formatting check passes |

Two temporary product mutations independently fail the corrected test: bypassing fast-ID lookup triggers the duplicate-delivery assertion, and computing the canonical ID before retaining the normal lookup triggers the canonical-ID-null assertion. Both product changes were restored; neither is in the source PR. The diagnostic suite intentionally contains negative controls and therefore exits 1 with ten expected failures on each source state; the completion controls pass. Do not interpret that expected diagnostic exit as a product regression or a green full-module gate.

- [Findings and independent reviews](findings.md)
- [Original develop failures and complete command output](develop-original.log)
- [Original PR comparison](pr-original.log)
- [Public-router ordering probe source](GossipFastMessageIdOrderingProbe.java)
- [Develop ordering log](develop-ordering.log) and [PR ordering log](pr-ordering.log)
- [Thirty corrected trials](fixed-thirty.log)
- [Bypassed lookup mutation](mutation-canonical.patch) and [failure log](mutation-canonical.log)
- [Eager canonical calculation mutation](mutation-eager-canonical.patch) and [failure log](mutation-eager-canonical.log)
- [Final five cases and formatting](final-neighbors.log)
- [Source object manifest](source-manifest.json)

Each stage has native XML in its same-named directory and a `*-summary.json` selecting only that stage's task names. Complete logs include command arguments, exits and process-group ownership. `GossipApiTest.original.java` preserves the original source. Prior lane229 failure XML is also retained. The original test, pubsub source tree, event service, peer stub and dependency-version objects are identical on the two compared heads. The test creates no mux/transport.

Fix PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/49 (base develop). Source branch: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/lane238-fast-message-id . Only the existing test is changed; all six assertions and both queued inbound calls remain. The full completion chain uses the existing one-second bound, and the real test executor is closed afterward. No timeout was raised and no count, assertion or payload was reduced. Verification is scoped local verification, as the brief requires; no full-module or green-CI claim is made.

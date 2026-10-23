Lane R69 review
Started 2026-10-23T01:42:48.613378+00:00

Plan
- In progress: verify fresh checkout, exact head, scope, and required reading.
- Pending: review assertions, fixture behavior, timer ordering, and resource cleanup.
- Pending: run at least 30 independent class runs on base and head, then reversal.
- Pending: compare at least five named other-suite cases on base.
- Pending: checkpoint evidence and deliver verdict.

Limits: review only; no PR branch updates, merge, publication, or deployment. Evidence will use a separate wip/R69-review-evidence branch.

OBSERVED 2026-10-23T01:45:24.489601+00:00: fresh clone head is the requested 812a22c71175817223c80fe69f8c8ba446bbae44; recorded base is e15e0f1947842be7ef6836c116c14ba47cc8eea1 on develop. Only libp2p/src/test/kotlin/io/libp2p/security/noise/NoiseHandshakeOffEventLoopTest.kt changed: 98 additions and one removal. The only removed statement reports the omitted write successful. All nine original test methods, their assertions, bounds, and counts remain byte-for-byte in order. Two new methods each repeat ten times.
Reading: repository README (275 lines); complete TESTING.md (1052 lines), including required TDD and machine-independent sections; PHILOSOPHY.md (93 lines); prior FNOISE findings and final, treated as claims to verify. No repository AGENTS.md. Repository-native Gradle applies; generic kompile commands do not. No origin/main exists; pinned review revisions are retained.
The mechanism is successful completion of an omitted response write starting a responder read timer that can close the connection before the initiator read timer; the evidence that demonstrates it is the production write-completion path plus the ordered-clock regression to be independently checked on both fixture versions. This is the current hypothesis, not yet a reproduced result.

Resource invariants under review
| Resource | Owner and terminal behavior | Public test coverage |
| --- | --- | --- |
| Omitted buffer | Adapter releases once; never forwarded | Reference count reaches zero |
| Omitted write promise | Adapter settles at channel close; completion once | Pending before close, closed-channel cause, callback re-entry and second close |
| Embedded pair | Test owns both and finishes/releases in finally | Real negotiation, responder-only clock advance, initiator expiry closes both |
| TCP hosts | Existing tests own all hosts, stop in finally | Original end-to-end silent-peer tests unchanged |

OBSERVED 2026-10-23T01:46:52.572795+00:00: scope evidence pushed to https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/R69-review-evidence . Gradle head and base preparation are tracked separately. Production NoiseXXSecureChannel.kt:371-375 arms the peer read timer only after a successful write; the new fixture leaves that completion pending. Independent runs are now being prepared.

OBSERVED 2026-10-23T01:48:07.178715+00:00: dedicated test-completeness pass: timer test checks real negotiation reached the omitted frame (line 320), both setup futures remain pending (321-322), responder-only virtual time cannot finish either (325-330), then initiator virtual time yields the expected read-time result and closes both channels (332-341). Thus an implementation with no active initiator timer cannot satisfy the test. Both embedded channels finish/release in finally (343-344). Write test checks released buffer (356), pending promise (362), closed-channel result (365), one callback (366), repeated close (367-368), and close re-entry in the callback (360). No TestChannel link/executor is used by the manual-transfer test, so its lazy executor is never created. TCP host cleanup remains unchanged.

OBSERVED 2026-10-23T01:49:45.006282+00:00: first independent full-class head run completed 29/29 first attempts, zero skipped or retry entries, command exit 0. Subsequent runs have distinct Gradle Test tasks and separate XML, fresh test JVM per run, no retry extension. Both revisions use the same two Netty event loops and 512 MiB test heap; original limits and iteration counts are untouched. The child-JVM harness settings remain unchanged.

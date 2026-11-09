The BuildTest coordinator has repeatedly failed droplet allocation while cached direct child-stream setup fails and later relay calls close. The requester is finishing https://github.com/CodexCoder21Organization/UrlResolver/pull/1694, which depends on this owned-hold and queued-child cancellation change. Its native GitHub build then failed at the later child's responder count, so this update reproduces that failure and corrects its ordering assumption.

This PR adds owned provisional parent holds and makes controller cancellation end queued child creation before its protocol is offered. Its original snapshot 48 production source is unchanged by this update. The update changes three scenarios to await the public responder-start callback before reading the positive exact count or closing the later child. The accepted-sibling scenario also explicitly checks zero cancelled responders before opening the later child.

The failed check is https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42553933355/job/128955362815 (one expected responder, zero observed). Classification **(b): test ordering**. [Negotiator.kt:153](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/a926ff238e86669a8e2eae6ecf5aa52d14fa31a8/libp2p/src/main/kotlin/io/libp2p/multistream/Negotiator.kt#L153) sends acceptance before [ProtocolSelect.kt:84](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/a926ff238e86669a8e2eae6ecf5aa52d14fa31a8/libp2p/src/main/kotlin/io/libp2p/multistream/ProtocolSelect.kt#L84) initializes the peer binding. The initiator controller can complete in that interval. The former scenario closed the later child and read the peer counter without waiting for its callback. No production cancellation change is needed.

First-attempt native evidence, with no test retries:
- Unchanged isolated scenario: FIRST 1/1.
- Concurrent independent parent pairs, 20 repetitions per muxer, four callers, 256 MiB and SerialGC: FIRST 40/40. Natural timing did not reproduce the failure.
- Deterministic held-parent binding order: FIRST 0/20, ten failures per muxer, all at the exact one-versus-zero assertion. Accepted-sibling forced order: FIRST 0/2, the same assertion under both muxers.
- Corrected callback-wait register: FIRST 66/66.
- Causal-wait reversal: FIRST 0/22, all at the original positive responder-count assertion again.
- Corrected scenarios against the prior production cancellation implementation: FIRST 0/4, so their original cancellation coverage remains. Production implementation restored before the final register.
- Full `:libp2p:test` module register, using an owned Kubo node: FIRST 1538/1540; 0 failures and 2 existing skips. Every executed case is first-attempt; no rerun plugin.
- `spotlessCheck` and `detekt` passed in that same native command.

Existing-test edits in this update:
- `NetworkProvisionalCancellationBeforePublicationTest.kt`: add a per-scenario responder-start future, complete it after incrementing the counter, and await it before closing/counting the later child.
- `NetworkProvisionalMplexCancellationBeforePublicationTest.kt`: the identical causal wait for Mplex.
- `NetworkProvisionalCancellationSiblingTest.kt`: the same callback wait, plus an exact zero-start assertion after both existing Echo replies and before opening the later child.
All earlier assertions, exact expected counts, muxer parameters, iteration counts and wait bounds remain unchanged. No payload marker or traffic allowance change remains. The three-file correction is committed as `Proposed test change: wait for later responder callbacks`. Two independent review passes read the complete scenario files, Testing Architecture, Code Review Red Flags and Engineering Philosophy.

Preparation results are retained separately: one evidence compilation attempt ran zero tests because of a Kotlin variance mismatch; its stale XML summary was withdrawn. An initial sibling payload marker candidate was FIRST 62/64 because both sibling parameters exceeded their unchanged fixture allowance; that candidate was replaced with the callback wait before the final register.

Current source head: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/8a47f8ecf30278cca52a4447c456ab8e4cc04e4f

Current findings and native XML: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/fix-libp2p-cancel-flake-evidence/lane-evidence/fix-libp2p-cancel-flake

Prior owned-hold/cancellation evidence, including its FIRST 98/98 targeted register: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/15c7dd61d3a0af3781ac0439932e611b27cd467d/lane-evidence/fix-libp2p-holds-c

Snapshot 48 was published from the earlier production head https://github.com/CodexCoder21Organization/jvm-libp2p/commit/a926ff238e86669a8e2eae6ecf5aa52d14fa31a8 as `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-48`, jar SHA-256 `fc8ec4faaf8176b734c98055247421800b4ddf545dea6b216ad66c1d53f343d5`. This update changes tests only. No new artifact was published by this lane.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

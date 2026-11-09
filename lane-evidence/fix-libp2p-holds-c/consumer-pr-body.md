The BuildTest coordinator has repeatedly failed droplet allocation while cached direct child-stream setup fails and later relay calls close. The owner asked to finish the cached-parent reservation and provisional-child migration in this PR as the candidate connection-management correction for that incident.

Cached bytecode calls reserve the exact eligible parent before opening a child. Outbound ping arrivals use independent provisional holds, check pending-dial ownership before admission, and promote the selected parent. Discarded provisional controllers are cancelled before their holds are released, preventing a queued duplicate probe from starting after selection.

This uses `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-48`, published from https://github.com/CodexCoder21Organization/jvm-libp2p/pull/97. Its POM is HTTP 200 and its served jar SHA-256 is `fc8ec4faaf8176b734c98055247421800b4ddf545dea6b216ad66c1d53f343d5`.

First-attempt evidence:
- Upstream restored native register: FIRST 98/98 (required network 71/71, new queued cancellation 4/4, capacity controls 23/23); spotlessCheck and detekt pass.
- Published-artifact baseline: FIRST 1/2. The public cancellation control passes; the unchanged consumer exact-count case fails with expected 3 and actual 5 probes.
- Corrected local required register: FIRST 9/9; extra public artifact control FIRST 1/1. The exact probe count stays 3.
- Complete source migration reversal with snapshot 48 still selected: required FIRST 8/9 and artifact control FIRST 1/1; the unchanged held-duplicate case fails waiting for physical parent close at test line 276.
- Restored candidate: required local FIRST 9/9 and public artifact control FIRST 1/1.
- Remote qualification FIRST 9/9 from native XML; official result PASSED 9/9, exit 0: https://buildtest.kotlin.build/run?id=b5ff526b. The 3,988 filtered-out XML rows are excluded from this count.

Existing-test edits relative to main are dependency pins only. These three files change `@file:WithArtifact("community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-32")` to the identical declaration ending in `snapshot-48`:
- `tests/testFreshPingDoesNotSelectParentOwnedByUnsettledAdmission.kts`
- `tests/testPingDefersParentOwnedByIndexedAdmission.kts`
- `tests/testPingRetainedParentBecomesReadyWhileDuplicatePublicationIsHeld.kts`

Five bytecode/reservation scenarios and two direct ownership/cancellation controls are new files in this PR lineage. All ten selected test pins moved from snapshot 47 on the intermediate candidate to snapshot 48; their bodies are unchanged. No existing assertion, expected count, wait bound or iteration was changed. The full before/after pin inventory is in `test-edits.json` in the evidence.

Evidence and full pin inventory: https://github.com/CodexCoder21Organization/UrlResolver/tree/8edafe1d000712d21cc23c2016158c626f45a154/lane-evidence/fix-libp2p-holds-c

Upstream native evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/60b0fb15a4dcf4468a87e6598e5ff05caa2554f9/lane-evidence/fix-libp2p-holds-c

🤖 Generated with [Claude Code](https://claude.com/claude-code)

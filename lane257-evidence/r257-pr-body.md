The owner asked for the connection-setup bug found during verification of https://github.com/CodexCoder21Organization/jvm-libp2p/pull/48 to be fixed at its source. Lane246 traced a failure on unchanged develop to the client's selection headers being sent twice when the server's plaintext selection reply and Exchange record arrive together; either muxer can then fail connection setup.

Keep ProtocolSelect's activation guard until Negotiator's pending activation event has been consumed. This preserves decoder removal before protocol callbacks while preventing a second activation after buffered bytes complete protocol setup. Forward unregister events to the selected protocol and release buffered input when activation closes the channel.

Tests cover real TCP hosts with both muxers and exact header-write counts, coalesced and bytewise input, synchronous callback reentry, close before activation, close from activation or payload, repeated close, buffer release and pending-controller closure. No existing assertion, iteration count or timeout is weakened.

Validation (native Gradle, warnings-as-errors; all bounded by `timeout 900`, one worker):
- Original unchanged-develop evidence: 4/4 cases failed, with duplicate TCP selection headers for both muxers and `[active, payload, active]` for both generic roles.
- Close tests on the inherited activation fix: 4/4 original callback cases failed. Expanded final tests with the close fix removed: 6/8 failed (two before-activation and four callback-close cases); two pending-controller controls already passed. Exact callback assertions are unchanged, with an added buffer-release assertion.
- Fixed focused suite: **160/160 passed across ten fresh 128 MiB JVM tasks**, zero failures/errors/skips. Each task includes both TCP muxers, coalesced/segmented and callback-reentry cases, and all eight close cases.
- Original lane246 coalesced-record read-pause fixture, unchanged: **2/2 passed**, both muxers.
- Neighbor set: **199/199 passed** (the prior 191 plus eight close cases), covering mux, multistream, plaintext, secio negotiation and publication lifecycle.
- Transport package: **72 passed, 0 failed, 1 inherited disabled case** (`QuicServerTestJava.checkConnectionIsNotClosedByTimeout`). `pingKubo` passed against an isolated Kubo 0.34.1 fixture with the workflow's verified archive checksum.
- Scoped Spotless check passed. Final rebase on `origin/develop` was a no-op at the exact tested head. Dedicated test and code review passes completed; no further issues found.

[Full logs, native XML, counts, fixture and findings](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane257-negotiation-20261015/lane257-evidence). CI monitoring belongs to the supervisor under this lane's brief. No merge or publication performed.

Prior attribution: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane246-yamux-readpause-20261015/lane246-evidence . The decoder-order candidate at https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane252-decoder-order-candidate was rejected because the callback-reentry tests showed it consumed four bytes of selected-protocol payload.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

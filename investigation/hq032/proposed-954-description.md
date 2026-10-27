This continues the downstream rollout requested in url://handoff/handoffs/hf-2026-10-26-roll-the-merged-jvm-libp2p-duplicate-settlement-fix-downstream-publish-fork-pin-urlprotocol-publish-protocol-pin-urlresolver-flip-the-consumer-reproducer-and-finish-the-remote-runner-failure-reproducers. The merged fork fixes must reach consumers so duplicate-dial settlement keeps an already selected RPC parent usable and a completed physical close remains observable when the event-loop queue is full.

The original work began after `testRejectedDrainLoggingDoesNotHoldOutboxMonitor` failed its first required check while holding https://github.com/CodexCoder21Organization/UrlProtocol/pull/635; the failure is https://github.com/CodexCoder21Organization/UrlProtocol/actions/runs/40085745929/job/120848195951.

The isolated original-test cleanup proposal originating at https://github.com/CodexCoder21Organization/UrlProtocol/commit/dd22e57d69bd26d39453d143b5f558fa52180ff4 remains for the supervisor's final assessment. Every original assertion, time limit and stress iteration is preserved.

The branch pins published `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-41` and prepares protocol `0.0.1442`. The fork's selected-parent and completed-future corrections are merged at https://github.com/CodexCoder21Organization/jvm-libp2p/pull/73 and https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71. The published jar SHA-256 is `2482ff751bd14b929b3e6b9ad6161ce7a645eaff1c1bad7d43423a731edbfe3d`. Version1442 was checked against published metadata and open pre-pins, then reserved in https://github.com/CodexCoder21Organization/UrlProtocol/pull/954#issuecomment-6432972708.

The deterministic host-stop guard fills a real live event-loop queue after physical close succeeds. The 128-cycle/eight-worker amplifier now separates all request setup, all queue faults and all ordinary host cleanup, because transports share their NIO worker group. Every cycle must deliver both requests before any held-loop callback, drain every injected queue before any ordinary cleanup, and finish every cleanup before the next round. A failed phase terminates all participants with bounded waits. The public close callers still run while their diagnostics are held.

A permanent source-build flush guard also holds an accepted real transport write, fails it between write and flush, and requires the original failure to reach the framed writer. This protects the existing `awaitRawStreamWrites` behavior that the downstream upgrade needs.

Validation recorded so far:

- Original rejected-drain, deterministic host-stop and initial phased stress gate: 3/3 first-attempt passes.
- Complete final phase ordering: 10/10 independent direct component runs on one shared NIO worker, 128 cycles/eight workers each, original 128 MiB heap and 240-second bound. Earlier phase controls failed 3/3 at real setup or cleanup overlap. Four normal-runner forward checks passed first attempt; a later command stopped the campaign because its first child failed in `NativeSha256Agent.premain` before test readiness and a built-in retry passed. That startup failure remains recorded separately.
- Resolver selected-parent consumer regression: unchanged main 3/3 red, production snapshot-41 pin 3/3 first-attempt green while retaining the test's snapshot-32 declaration.
- Flush guard: exact published1434 3/3 red, source-build first-attempt green.
- CPU1 FreshJoin: exact protocol1434 plus libp2p41 fails 3/3, each at 8/48 missing-withdrawal trials; current protocol-source comparison is being completed.
- Exact-head full-suite and required `bld-build`/`bld-all-tests` results: pending; this description must be refreshed with the final gate counts before marking ready.

Exact source checkpoints, XML, first-attempt traces, direct component scripts, and Git bundles are retained at https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/hq032-rollout-evidence/investigation/hq032 . Publication follows the supervisor's reviewed merge; this lane has published and deployed nothing.

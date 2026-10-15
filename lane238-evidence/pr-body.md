Lane 229 observed `GossipApiTest.testFastMessageId` failing in a full module run and one of five targeted trials, holding https://github.com/CodexCoder21Organization/jvm-libp2p/pull/48 for attribution. This follow-up was requested to determine whether the mux activation change caused that failure and fix the demonstrated cause without weakening the fast-message-ID checks.

The original test also fails on unchanged `develop`: four of twenty trials report the same `expected: 1, but was: 0` assertion at line 75. This lane's twenty original PR-head trials pass. A controlled router-event ordering produces the original assertion failure 10/10 on **both** heads while the duplicate task is still pending; releasing it and awaiting its actual completion gives the correct created-message count and uncalculated canonical ID in every attempt. The completion-based controls pass 10/10 on each head. This is a pre-existing test completion bug; the exercised router/cache/peer-stub path is unchanged by the mux PR and creates no mux or transport.

The test previously treated an empty 100 ms delivery poll as acknowledgement that duplicate processing had finished, although a filtered duplicate is intentionally never delivered. It now chains two `submitOnEventThread` operations, checks the same six assertions after the corresponding `onInbound` call returns on that thread, and awaits the whole chain within the existing one-second bound. The real executor and both queued inbound calls remain, and the executor is shut down in `finally`. No product code, dependency, timeout value, payload, assertion or iteration count is relaxed.

Validation:

- Original PR head `7a27e4890885592f60ad402af6af30b5578da364`: 20 passed / 0 failed.
- Original `develop` `3e88aeef2e016a46565be871b367646c10ef6f65`: 16 passed / 4 failed, with matching native stacks.
- Deterministic original completion assumption: 10/10 failures on each head; actual-completion controls: 10/10 passes on each head.
- Corrected test: 30/30 passed, zero skips.
- Two deliberate product mutations are rejected by independent assertions: bypassed fast-ID lookup fails duplicate delivery at line 85; eager canonical-ID calculation with lookup retained fails the canonical-ID assertion at line 88. Both are native assertion failures, not timeouts or compiler failures; product source is restored.
- Final restored-source gate: 5/5 passed (all GossipApiTest and FastIdSeenCacheTest cases), zero skips; spotlessJavaCheck passed. Working tree is clean; only GossipApiTest.java differs from develop.

[Evidence, complete logs, native XML, the public-router ordering probe, and review findings](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane238-fast-message-id-20261015/lane238-evidence). The diagnostic probe and deliberate product mutations remain in that evidence branch only; this PR changes just the existing test. Per the assigned scope, verification is local and targeted; no full-module or green-CI claim is made. GitHub Actions cannot run on this fork under its current configuration, and the supervisor owns any subsequent CI wait.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

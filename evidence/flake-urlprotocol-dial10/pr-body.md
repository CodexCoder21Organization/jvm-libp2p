The BuildTest restart-cost and droplet-utilization campaign is investigating UrlProtocol's unchanged 1,000 ms connection-budget test, which still fails first attempt in the four-test CI shape with N1 at snapshot-33. The owner asked to find the first dial's cost and remove it at its owner; this PR covers only N3, the repeated host identity preparation, before the separate, larger class-loading concern. Related evidence: [UrlProtocol change](https://github.com/CodexCoder21Organization/UrlProtocol/pull/725), [merged N1](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/53), and [campaign handoff](https://www.handoff.wasmserver.com/handoffs/hf-2026-08-24-fix-startup-compaction-delta-heap-exhaustion-then-revalidate-the-extreme-suite).

`NoiseIoHandshake` previously derived the same static public key and signed/serialized the same identity payload for every connection, after host startup. `NoiseXXSecureChannel` now prepares those inputs once at binding construction. Each connection copies the privately held static state, uses an immutable prepared payload, and keeps its own fresh ephemeral key and mutable handshake state. Construction preserves an original signing failure. Existing binding and standalone handshake constructors remain available; `javap` reports zero removed public members against snapshot-33. No scheduling, timeout, iteration, version, or consumer pin changes.

### First-attempt evidence

- Inherited baseline: five new proofs assertion-red, zero retries. Fresh product green: four retained N3 proofs pass once each. Product-only reversal: all five inherited proofs assertion-red again, zero retries; normal compilation succeeds in each control.
- Final head full fork suite, exactly once: **1,103 PASS / 3 FAILED / 2 SKIPPED, 1,108 attempts**. All four owned N3 identities occur exactly once and PASS. No retry configuration or native attempt-history entries. All child-JVM callers from the source search are included.
- Local built-snapshot four-JVM batch: **4/4 first-attempt PASS**, including the original function body, byte-identical to consumer main. This is a local result, not remote CI qualification or proof of a remote rate change from the inherited 0/4. No publication, pin change, extra warm-up dial, or retry.
- Same minimal measurement agent: every two-ended connection now performs **8 curve evaluations / 0 identity signatures inside the dial**, versus **10 / 2** before, including second dials. Cold math after class-load precedence is 115.36→66.98 ms. Class-load time remains substantial: the instrumented cold cohort's class-load union is 326.87 ms and total provider-to-ready 706.52 ms, versus 263.43 / 587.93 ms before. That instrumented cohort is 2/4 first-dial passes; both failures and their full stacks are retained. No net latency improvement is claimed from separate instrumented cohorts.
- Changed product formatting reports `IS CLEAN` through Spotless's IDE hook; its wrapper check task is skipped by that hook. Module Detekt still reports the three unchanged baseline diagnostics in AbstractChildChannel, MuxSessionActivationTest and MuxCloseDuringPublicationTest. No full-module formatting/static-analysis success is claimed.

The full suite is **red**, not qualified green. Its failed neighbours are:

| Test | First-attempt result |
|---|---|
| `QuicKuboTestJava.pingKubo` | Known localhost:5001 connection refusal; test requires an external Kubo daemon. |
| `GossipTwoHostTest.test message larger than mplex frame` | Known condition timeout at TwoGossipHostTestBase:123 / GossipTwoHostTest:36. |
| `ParentCloseListenerReviewTest.mplexVisitorClosedChildrenReleaseParentCloseListeners` | Newly observed `TimeoutException` in `client.stop()` at test line 98. Origin remains **unclassified**; unchanged source is not proof it is pre-existing. No blind rerun or shutdown change is included. |

[Native suite audit](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/flake-urlprotocol-dial10-evidence/evidence/flake-urlprotocol-dial10/n3-full-suite-audit.json), [complete failure stacks](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/flake-urlprotocol-dial10-evidence/evidence/flake-urlprotocol-dial10/n3-full-suite-full-failure-stacks.txt), [phase measurement notes](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/flake-urlprotocol-dial10-evidence/evidence/flake-urlprotocol-dial10/measurement-notes.md), [full findings and owner table](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/flake-urlprotocol-dial10-evidence/evidence/flake-urlprotocol-dial10/findings.md).

### Test-edit ledger

Existing main tests: **none changed**. The new host proof lost one unused import. The new same-binding ephemeral proof retains its assertions and bounds. The inherited fifth, mutation/reconnect proof and its child support were moved unchanged to [evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/flake-urlprotocol-dial10-evidence/evidence/flake-urlprotocol-dial10/inherited-NoisePreparedIdentityIsolationTest.kt) under the explicit scope rule for red new proofs: after N3 it reaches a separate Ping scheduler failure on its second connection (`RejectedExecutionException`, Ping.kt:102). Changing that fixture exceeds the pre-authorized readiness/ordering correction; no ruling has arrived. **This PR carries four green proofs, not five.** Every retained bound/iteration is unchanged. Eleven unrelated formatter-only edits were restored before the final suite.

Dedicated test and code reading passes found no P1 in the N3 delta. They do not substitute for independent final review or CI qualification.

### Follow-ups (separate PRs)

1. Prepare the remaining first-use connection components before host start returns, without opening a preparation connection. The measured remaining families include Netty socket/buffer-event/codec classes, Noise handshake/cipher classes, protocol selection, Yamux/mux handlers, and consumer close diagnostics. [Every measured class-load event and family](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/flake-urlprotocol-dial10-evidence/evidence/flake-urlprotocol-dial10/class-load-inventory.json) is retained; family times overlap and are not additive.
2. Resolve the Ping scheduler's lifetime across reconnects, or obtain the ruling for an honest per-stream fixture, then adopt the preserved fifth proof with assertions/bounds unchanged. Its existing failure is retained in [the inherited full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/flake-urlprotocol-dial10-evidence/evidence/flake-urlprotocol-dial10/inherited-n3-green-with-ping-failure.log).
3. Investigate the newly observed parent-listener cleanup timeout. The current evidence does not establish its cause.
4. Artifact publication and remote consumer pin/qualification require a later authorized step; neither occurred here. This draft must not be presented as resolving every cold one-second failure.

Final-head CI audit: both workflows ended in `startup_failure`, with **zero jobs, zero artifacts and zero test attempts**: [run 39192003081](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39192003081), [run 39191985638](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39191985638). No native CI XML exists to qualify this head; CI is blocked, not green. This was one terminal-state read, with no watcher or rerun.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

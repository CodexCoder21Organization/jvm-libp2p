# R93A3 progress

2026-11-14 09:02 UTC — OBSERVED: Review launched; PR is OPEN at b3fa50420fe578cfa17abef8342f75f6592ce12d against develop. OBSERVED: Fresh clones started and required documents located. INFER: Review current head unchanged from latest brief. Next checkpoint: prior lane records, full-document reading, current-base overlap.

Plan:
- [in progress] Read required documents, prior lane records and repository instructions; verify exact head and base overlap.
- [pending] Read every touched file whole and trace lifetime/error/completion paths; decide every prior finding and expectation edit.
- [pending] Run targeted public checks if slots permit; inspect Actions XML first attempts and retries.
- [pending] Complete findings, description inventory and publish verdict; push evidence and verify remote SHA; clean processes.

OBSERVED: Brief prohibits product edits, rebasing its branch, delegation, handoff CLI, merges and publication. The general rebase instruction is applied to the evidence branch only; the review target remains the exact PR tree. OBSERVED: No Addendum headings at launch.

2026-11-14 09:10 UTC — OBSERVED: Fresh exact-head touched-class Gradle gate passed 64/64, attempts 64, retries/skips 0, build 1m48s. OBSERVED: Public held-close ordering probe fails 0/1 at R93A3HeldCloseOrderingTest.kt:51: close operation precedes the earlier physical listener. INFER: Immediate promise notification may change prior callback ordering. Prior-promise control is compiling; no product edit or confirmed regression verdict yet. Next checkpoint: prior-promise comparison and explicit reentrant-contract probe.

OBSERVED: All 45 touched files read whole (39 tests, 4 production, README/FORK), with hashes/line counts in whole-file-inventory.json; neighboring ConnectionOverNetty, P2PChannelOverNetty, NettyExt and existing notification tests read. Base versions of both edited tests read through their complete current files and all base diffs. Root AGENTS absent.
OBSERVED: 2026-11-14 09:14 UTC — New ordering regression confirmed: held-close test head0/1, prior-promise1/1; explicit reentrant test head0/1, prior-promise1/1. Each single attempt, no skips/retries; source controls differ only in requestPhysicalClose promise delivery. Public reentrant test fails at evidence/R93A3/R93A3ReentrantHeldCloseOrderingTest.kt:55. INFER: One P2 merge-blocking product finding at NettyChannelClose.kt:87; current head must not be published.
OBSERVED: F93m intended regressions independently reproduced against old helper: published-error test0/1 and destroyed-loop test0/1 on intended assertions; current64-case register includes both green1/1. G3/G6/history remain explicit API limitations, not new product defects. Next checkpoint: full findings/disposition tables and final evidence receipt.

2026-11-14 09:15 UTC — OBSERVED: Checkpoint790403cf1b997f960afca503e1f6688417e198c2 verified local=remote on wip/R93A3-evidence-20261114 at origin https://github.com/CodexCoder21Organization/jvm-libp2p.git. OBSERVED: New public reentrant-contract regression head0/1, prior-promise1/1, no retries/skips; earlier generic ordering probe also head0/1, prior-promise1/1. INFER: Finding R1 P2 blocks merge/publication, not a repeat of round-two coverage findings. Next checkpoint: findings/report, final push and cleanup.

# FIX67 findings

Request: finish https://github.com/CodexCoder21Organization/jvm-libp2p/pull/67 by porting REV67 peer-close/pending-retry test unchanged, positive/negative controls, targeted and full suite, rebase/push/ready/body update and watch-only CI. No merge, enqueue, publish, deploy, restart or buildtest mutation.

## Plan
- [in progress] Verify live PR, read instructions, required guidance and review evidence; inspect pending-retry invariant and test.
- [pending] Port reviewer test unchanged; run 3 first-attempt positive controls and 3 shortcut mutation negative controls; checkpoint.
- [pending] Run 23 existing targeted/neighbour cases plus added case 3 times; full suite once under machine-wide lock; compare exact 20 baseline failures.
- [pending] Independent test and implementation review; rebase on current develop, push PR head, mark ready, update body and read back.
- [pending] Watch informational remote check with build-watchman without mutations, record required Actions state, push final evidence and audit cleanup.

## Constraints and record
User scope requires local Gradle commands (repository has no kompile scripts), first attempts only, no timeout/iteration/assertion weakening, no JVM tuning, suite lock /tmp/claude-501/complete-suite.lock. Commits carry requested co-author; evidence only on wip/FIX67-*.

2026-10-23T00:58Z OBSERVED: fresh clone FIX67, PR OPEN/draft at 13bea7dd18571049b0f6f05702b5071f4477b064, base develop. Read local REV67 findings. Reviewer supplies real TCP/Noise/Yamux pending-close scenario, passing current implementation and failing closed-channel shortcut. rg unavailable; use git ls-files and Python text search. Required documentation retrieved via GitHub contents API. Initial verification is underway.

2026-10-23T01:02Z OBSERVED: read README, CLAUDE.md, FORK.md, REV67 findings and supplied scenario, complete required Testing/Philosophy guidance. No AGENTS.md is tracked. Base remains e15e0f19; fetch/rebase on develop is clean. Documentation says remote must be required; lane explicitly calls it informational and forbids remote commands, so lane direction controls. No cron tool is available here; status reminders are maintained during tracked-task checks.

Mechanism under test: physical channel inactivity does not settle a privately owned retry close promise. Reproducer: hold retry attempt 2 using a public outbound Netty handler, physically close the remote peer, re-enter both APIs from channelInactive, then fail held attempt 2. The original failure must remain ordinary-close result, all three retry observers must receive failure 2 once, and later retry on closed socket must succeed without request 3.

| Resource | Owner / terminal state | Re-entry/concurrent behavior | Public API proof |
|---|---|---|---|
| First close | channel attribute, permanent settled result | ordinary callers retain failure 1 | existing ownership cases plus added scenario |
| Latest retry | atomic reference, success terminal; failure replaceable | pending retry shared even after peer inactivity | added held-attempt/peer-close scenario |
| Caller future | caller owns derived view | caller cancellation/completion cannot affect owner | existing 8192-observation stress test |
| Physical socket | Netty peer/channel | independent close does not replace pending result; after failure closed socket needs no new request | added exact request-count assertions |
| Host/socket/held promise | scenario owns all | finally releases held attempt, removes handlers, closes channel, stops hosts | scenario cleanup inspected; no persistence/restart state |

Plan: first reading done; positive/negative controls in progress. Reviewer scenario port is byte-for-byte unchanged.

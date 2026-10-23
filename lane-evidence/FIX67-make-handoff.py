from pathlib import Path
import datetime,json
out=Path(__file__).parent
heads={line.split()[1].removeprefix('refs/heads/'):line.split()[0] for line in (out/'FIX67-remote-heads-final.txt').read_text().splitlines()}
repo='https://github.com/CodexCoder21Organization/jvm-libp2p'
pr=repo+'/pull/67'
now=datetime.datetime.now(datetime.timezone.utc).isoformat()
rows=[]
for branch,contents in [
('fix/JL61-explicit-close-retry','Ready/open/unmerged PR; requested retry implementation plus12 real-connection scenarios; targeted24x3 pass; full21 failures.'),
('wip/FIX67-test-20261023','Same final reviewer-test candidate as PR head; all assertions preserved with whitespace formatting only.'),
('wip/FIX67-shortcut-mutation-20261023','Deliberately incorrect closed-channel shortcut; reviewer scenario fails3/3. Do not land.'),
('wip/FIX67-evidence-20261023','lane-evidence/: findings, every positive/negative/neighbour/full XML and log, full stacks, memory, base comparisons, CI and handoff records; branch advances with evidence.'),
('wip/JL61-evidence-20261022','Original fail-first implementation evidence, original full suite/base controls, review, and workflow-policy findings.')]:
 sha=heads[branch]; rows.append(f'| [{branch}]({repo}/tree/{branch}) | [{sha[:8]}]({repo}/commit/{sha}) | {pr if branch=="fix/JL61-explicit-close-retry" else "no PR"} | {contents} |')
body=f'''# Handoff: Restore CI dispatch and resolve jvm-libp2p verification gaps

Updated {now}.

**RE-VERIFY: this is a write-time snapshot. Read {pr}, its head check suites/check runs and Actions runs/jobs, Actions permissions, and git ls-remote for the branches below before acting. Every status below can change.**

## Design decisions in force

Owner direction, 2026-10-21:

> "Then, do another round, Your goal is to add end-to-end integration tests which identify bugs (the tests should initially fail), and then fix the bug in the implementation."

Original close-retry direction, 2026-10-22:

> "Implement a documented, explicit retry operation; ordinary close() keeps its single shared result. Think about concurrent retry vs ordinary close, retry after success (no-op), and repeated failures."

FIX67 direction, 2026-10-23: add the reviewer's pending-retry/peer-close scenario while keeping its assertions; verify positive and mutation controls; run neighbours three times and one full suite; rebase/push/ready the existing PR. The lane restrictions remain:

> "You may not merge, enqueue, publish artifacts, deploy, restart services, or delete/rerequest buildtest runs."

> "Absolute rules: never increase a timeout, never reduce an iteration count, never disable/skip/delete a test, never weaken an assertion, no mocks (no mockito), no reflection, never make a private member public for a test."

Use local Gradle, because this repository has no kompile runner scripts. Never --remote in these lanes. Full suites must hold /tmp/claude-501/complete-suite.lock on the shared10GiB machine; record memory.current periodically and do not add JVM tuning flags. Only first attempts count; this Gradle build has no retry plugin. No policy broadening or CI rerequest is authorized by this record.

## Mission and current state

Restore actual CI execution and determine the additional full-suite Noise failure. The close-retry implementation and the reviewer's missing regression test are ready for review in {pr}. The PR is OPEN, ready (not draft), and unmerged at [43eab3dc]({repo}/commit/43eab3dc7f2a03e8144b9fa99cf3811cb0fbda4d). No merge, enqueue, artifact release or deployment was performed.

The expected exact20 full-suite failure result was refuted: the one final-head full suite produced1226 tests,1203 passed,21 failed,2 existing disabled. All known20 develop failures are present, plus one additional saturated Noise test failure. The12 retry scenarios all passed first attempt. Do not describe this as a green full suite or say the21st failure has been proven pre-existing.

## What was found and done

1. During the [UrlProtocol snapshot-38 upgrade](https://github.com/CodexCoder21Organization/UrlProtocol/pull/846), five downstream cases could not retry a physical connection close after failure. The upstream issue is {repo}/issues/61. Original fail-first proof has nine recovery failures against the old helper and two expected boundary/default passes.
2. The implementation separates original close ownership from latest explicit attempt. Ordinary Netty close always observes the original private result. Retry reuses pending/successful work and CAS-replaces only a failed attempt; ownership is published before handlers can re-enter. Callers receive derived futures. Relay inherits a JVM default that preserves its stream-close behavior. REV67 confirmed existing compiled relay compatibility; no new abstract method is required.
3. [REV67 evidence]({repo}/tree/wip/REV67-review-20261023/lane-evidence) supplied the missing real TCP/Noise/Yamux scenario: hold retry attempt2, close the peer, re-enter ordinary/retry APIs from channelInactive, then fail attempt2. All three retry observers must receive failure2 once, ordinary close must retain failure1, and a later retry on the closed socket must succeed without physical request3. FIX67 ported this test; final changes versus supplied source are whitespace only. No production source changed in FIX67.
4. Reviewer scenario passed3/3 positive controls. The exact reviewer's immediate-success closed-channel shortcut failed3/3 at the expected pending-result assertion. The original23 neighbours plus the added case passed24/24 in each of3 final-head runs; assemble, spotlessCheck and detekt passed each run. Gradle durations15/13/13s. Dedicated test and implementation review passes found no remaining issue in this retry addition. Existing8192-observation and1000-failure stress counts are unchanged.
5. One full root Gradle suite ran under the shared lock after roughly31min waiting; command runtime3m25s, exit1, not137. Result1226=1203pass+21fail+2existing disabled. Saved every XML, full stack, raw log, exact failed identity comparison and memory sample. Memory before command8,836,386,816bytes; sampled peak10,736,967,680bytes; final sample9,750,020,096bytes; oom_kill stayed1. No full-suite retry was performed.
6. The20 known failures match unchanged develop [e15e0f19]({repo}/commit/e15e0f1947842be7ef6836c116c14ba47cc8eea1): native TLS/QUIC SSL_CTX initialization; fixed-port40002 bind failures following a host setup failure; and Kubo expected on localhost5001 without an owned instance. The original source supports a failed HostTestJava setup leaving its listener open before later tests bind that port; exact native-provider cause remains unestablished. REV67 unchanged-base XML and original author controls are preserved, not waived.
7. Extra failure: `NoiseHandshakeOffEventLoopTest.remote read timeout fails saturated handshakes while responder crypto is queued()`. Child harness reports120 queued responder message-1 tasks but118 failed dials at NoiseHandshakeOffEventLoopTest.kt:565. The cause is unconfirmed. Noise implementation, read-timeout handler, JUnit test and saturated harness bytes are identical between base and PR. Fresh base has32 passing controls (2 Gradle,10 fresh child runs,20 child runs at concurrency2); PR has20 passing child controls at concurrency2. These later passes do not erase the failed full-suite result and do not prove a fix. No source, assertion, timeout, count or private visibility was changed. A scope question was raised before guessing a Noise correction; no answer arrived during this lane.
8. Adjacent [Noise fixture correction]({repo}/pull/69) targets a different silent-responder timer-order assertion. Its evidence describes ReadTimeoutException versus ConnectionClosedException; do not assume it fixes this queued-count mismatch. Its source/evidence was read; no change from it was applied.
9. After fetch/rebase, develop remained e15e0f19 and exact tested head43eab3dc was unchanged. PR was verified OPEN before pushing. Marked ready with gh pr ready. Body opens with the owner's why, links issue/reviews, explicitly records21 full failures and current CI gaps, and ends with requested footer. Both REST PATCH updates were read back exactly.
10. Current-head Actions runs [latest](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39886648554), [push](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39886644206), and [earlier WIP](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39881858384) all ended startup_failure. Latest jobs API total_count=0. Develop likewise has startup_failure/zero jobs. Actions permissions freshly remain enabled=true, allowed_actions=local_only, while build.yml uses external checkout/setup-java/Gradle/Android Actions. This mismatch is sufficient to block startup; additional causes are not excluded. Actual expected Gradle matrix jobs have not passed.
11. Informational Kotlin [suite108095815110](https://api.github.com/repos/CodexCoder21Organization/jvm-libp2p/check-suites/108095815110) is queued with zero dispatched runs and no check runs. build-watchman0.0.21 reported the same condition on two4-minute polls; suite age exceeded56min, while sampled develop suite had dispatched. This is a dispatch observation, not a code verdict. Exact routing/filter/delivery/receiver cause is unknown. The same head was first pushed to WIP, later to the existing PR branch; no causal attribution to that order is established. No remote build log exists to read. The watcher recommends rerequest, prohibited here, so none was done. The90min hard cap was armed; watch was stopped at this confirmed blocker, RESULT INTERRUPTED exit130, notGREEN. Watcher processes ended.

## Relevant branches and unmerged work

All rows are CodexCoder21Organization/jvm-libp2p. Heads below were checked with git ls-remote at this write; evidence branches can advance.

| Branch | Remote commit | PR | Contents/state |
|---|---|---|---|
'''+'\n'.join(rows)+f'''

All three local checkouts (FIX67, FIX67-develop, FIX67-evidence) have clean tracked/untracked status, no stashes, and no local-only commits. No extra worktrees. The base clone is unchanged develop. The PR addition from FIX67 is exactly one JUnit source file; no evidence, build outputs or caches entered the PR. Runtime artifacts were built only for local validation; command/diff audit has no publication, deployment or restart operation. No production code was changed by a deployment.

## Reproduction and operational notes

Read [TESTING.md](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md) and [PHILOSOPHY.md](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md), repository README/FORK/CLAUDE, and evidence before new edits. Actual default branch is develop, not main. Fetch/rebase before build. For Gradle, --tests belongs immediately after the test task, before unrelated assemble/format tasks.

Single added scenario: `./gradlew --no-daemon :libp2p:cleanTest :libp2p:test --tests io.libp2p.transport.RetryCloseInactiveReviewTest`.

All24 neighbours and3-repeat driver: lane-evidence/FIX67-targeted.py. Each iteration cleanTest prevents up-to-date test reuse. Mutation branch is only a negative control, never a candidate for landing. Original full command: `flock -w 5400 /tmp/claude-501/complete-suite.lock timeout 45m ./gradlew --no-daemon cleanTest test --continue`.

Additional Noise control uses exactly the existing `NoiseHandshakePathHarness saturated` in a fresh child JVM; no JVM flags were added. lane-evidence/FIX67-capture-classpath.gradle captures the public Gradle test task classpath without editing repository sources; FIX67-noise-repeat.py and FIX67-noise-concurrent.py retain all outcomes. Inspect FIX67-noise-failure-stack.txt and FIX67-full-stacks.txt, not only count summaries.

The report-challenge skill's CLI auto-enqueues/merges, prohibited by the lane. Plain Actions and CI dispatch challenge records are pushed under lane-evidence instead. Current handoff skill uses this service, not the old guide's handoff-PR merge exception.

## Next steps

1. Re-verify current PR head/readiness/unmerged status, actual check-suite/check-run state, Actions permissions/jobs and remote branch heads. Do not merge or publish under this authorization.
2. Diagnose CI dispatch at its owning receiver/bookkeeping path for the exact SHA/suite. No current run exists, so there is no code failure to fix from this CI observation. Do not rerequest, delete, warm/restart or generate an empty commit to retrigger CI under this lane. Owner must explicitly expand authority before any forbidden action.
3. Resolve intended Actions policy/workflow alignment in its owning workstream. Do not silently broaden allowed_actions. Actual jobs must execute; informational status is not an Actions pass.
4. Diagnose the additional Noise queued-count failure with a reliable public-path reproducer before any fix. Preserve all current assertions and bounds. Record the exact ordering or dependency condition; do not blame shared load or calibrate a trigger to a host. Compare base and PR honestly; successful later controls do not prove absence of a regression. Keep the unrelated silent-peer correction separate unless evidence joins the mechanisms.
5. Known20 native/TLS/Kubo/cleanup defects remain in this same verification workstream, with original full stacks and base controls available. Fix causes at their owning layer, without skipped tests or larger bounds.
6. Once real blockers change, run appropriate local checks under memory/lock rules and watch actual new CI with a bounded watch-only monitor. Update this handoff and PR with exact outcomes. No unchanged CI retry, merge, enqueue, publication, deploy or restart is authorized.
'''
(out/'FIX67-handoff-final.md').write_text(body)
print('Handoff body bytes',len(body.encode()),'snapshot',now)

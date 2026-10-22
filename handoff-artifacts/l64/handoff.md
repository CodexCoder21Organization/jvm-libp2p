# Handoff: Finish required CI for the paused Yamux queue fix

Written 2026-10-22T03:00:28.253642+00:00.

**RE-VERIFY: This is a write-time snapshot. Run `gh pr view 64 --repo CodexCoder21Organization/jvm-libp2p --json state,mergeStateStatus,statusCheckRollup,mergedAt,headRefOid,baseRefName` and read check suites/check runs for the exact head before acting. An empty status rollup is not a green required check.**

## Design decisions in force

User requirements dated 2026-10-22:

- "You are not allowed to merge, enqueue, mark ready, close PRs, publish artifacts, deploy, restart services, rerun CI, or delete/rerequest buildtest runs."
- "Never increase a timeout, never reduce an iteration count, never disable/skip/delete a test, never weaken an assertion, never use mocks (no mockito) or reflection, never make a private member public for a test."
- "Develop's newer storage changes (preparePendingChildRead, exact-sized queued copies, and their tests) must be kept, not reverted."
- "Reproduce first with the reviewer's real-TCP probes turned into committed failing tests (push the failing tests alone first)."
- "5/5 first-attempt passes of the new tests; full gradle test suite once ... push; required checks green. Do not merge."
- "Stop conditions in the brief are binding both ways: do not stop before the bar is met, and do not add verification rounds beyond the bar."

Those prohibitions remain in force. The owner was asked whether to authorize one CI rerequest for the exact verified head or leave the PR blocked; no answer has been received. Do not treat elapsed time or the first suggested option as permission.

## Mission summary

Bring https://github.com/CodexCoder21Organization/jvm-libp2p/pull/64 up to develop and resolve review findings 0–3. The owner asked: "Then, do another round, Your goal is to add end-to-end integration tests which identify bugs (the tests should initially fail), and then fix the bug in the implementation." A UrlResolver test found connection-wide stalls when one Yamux stream paused. https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63 merged the per-stream pause change into develop. This follow-up bounds retained frames while preserving control traffic.

All implementation/review/local-verification work is complete. Only the required CI-green condition remains blocked. No merge is requested or authorized.

## What was found and done

1. Rebased only the two unmerged commits onto develop b01e08e5e40daecd68a529bc4c07a03999397657 and retargeted the PR to develop. Kept preparePendingChildRead, exact-sized copies, and all existing storage tests. Initial rebased mux baseline: 189 tests, one failure in the 100-copy storage test because the old frame threshold stopped parent reads.
2. Committed and pushed the review's real-TCP workloads before implementation. Both failed first attempt: a 4 MiB reply stopped at 262,091 bytes with parentAutoRead=false; a remote reset left the child open. The mechanism is verified in source: the parent pause also prevents WINDOW_UPDATE/RST from reaching YamuxHandler.
3. Enforce 64 retained payloads plus receive-window bytes before copying the next input. Frame65 releases the arriving data and all held copies, reports the exact frames/bytes/limits locally and resets only that stream with Yamux RST. Parent reads stay enabled for control traffic, siblings and negotiation. No silently truncated live stream is returned.
4. Release held input before error/write callbacks, which can resume reading during reset. Preserve the detailed child terminal error using the existing WRITE_FAILURE attribute, because Netty can fail the child's outbound buffer before the mux promise's error propagates to StreamOverNetty. Public write-failure and callback re-entry tests caught and verify that ordering.
5. Added exact 63/64/65 copied-buffer count/capacity tests, 256 coalesced frames followed by sibling data, 16 streams filled to both the 64-frame and 300-byte fixture window bounds, local close/remote reset/parent close with two saturated children, FIFO resume, callback re-pause with FIN after64 held frames, and already-paused-parent preservation. The TCP close scenario waits for64 admitted frames. TCP review cases run at64 (complete reply required) and1000 (completed explicit reset permitted).
6. Kept the original 100-copy storage regression and65-frame window-accounting regression's iteration counts and every assertion. Their test-only handler allowance is explicitly128 frames to isolate those original contracts; the new bounds fixture uses the production default64. No production visibility was widened. No mocks/reflection/timeouts/iterations were relaxed. Separate test-comprehensiveness and code/lifecycle review passes are recorded in findings.
7. Final immutable source head: 1d31d81c0c19c0e378ceae4d0136c3a9708c658f. Five entire mux-family runs each passed204/204 first attempt with128MiB heap and zero failures/errors/skips. Kotlin formatting passed. Do not add another repeat round; the requested five-round bar is already met.
8. Ran the requested full Gradle command once under the machine-wide lock:1176 tests,1154 passed,20 failed, two existing skips. All new Yamux tests passed. Gradle stopped at failed :libp2p:test, so later subproject tests are not claimed. The failures are unchanged native TLS-context creation, fixed40002 port conflicts after a TLS test exits before cleanup, and the local Kubo HTTP endpoint refusing its connection.
9. A targeted pure-develop control replaced all branch-changed files with develop and removed branch-only files temporarily, then ran HostTestJava.addPingAfterHostStart, RpcHandlerTest.test1 and QuicKuboTestJava.pingKubo. All three failed first attempt in the same three failure classes. Files were restored byte-for-byte afterward. This refutes attribution of these failures to the paused queue change; the full suite is not green. No second full suite was run.
10. Pushed the verified head to the PR branch, updated title/body with motivation first and all validation limits, and read the body back exactly. PR is OPEN, base develop, head 1d31d81c0c19c0e378ceae4d0136c3a9708c658f, mergeStateStatus CLEAN, mergedAt null. No deployment/publication/restart/merge/queue action occurred.

## Handoff storage fallback

The central Handoff service returned health OK, but create failed before the API invocation while fetching its client bytecode: `Stream closed while reading message data (read 2097110 of 2813637 bytes)`. No handoff id or successful creation response was returned. Restarts/deploys are prohibited, and the report-challenge CLI would automatically enqueue/merge a separate PR, also prohibited. The handoff is therefore durably stored on the evidence branch without a merge. The complete failure text is preserved beside this handoff. A future authorized actor can import it into the central service after that client fetch path works.

## Current CI blocker

The required kotlin.build (remote) check has no dispatched run. GitHub's statusCheckRollup is empty. The kotlin-build-ci-test suite 107625820125 was created 2026-10-22T02:39:29Z for head_branch wip/l64-control-traffic-fix, still queued with latest_check_runs_count=0 and no update. The later feature-branch GitHub Actions suites107627843094 and107627853209 have startup_failure with zero runs. This is not a failing code/test check.

Read-only build-watchman0.0.21 reported the same no-dispatch state over multiple assessments, including age838s. Its suggested warm/rerequest action was not performed because of the explicit lane rule. Inference only: WIP suite association/deduplication or a missed event may prevent dispatch; the service-side cause has not been proven. The complete CI observations are preserved in the evidence branch.

The next actor needs approval for a dispatch/rerequest or a CI owner to recover dispatch under their own authority, then must watch the exact head through required-green. Do not make an empty commit or metadata-only source commit just to cause another CI event; that would hide the same restriction. The owner approval question remains pending.

## Relevant PRs / refs

| Repo | Branch | Remote head SHA | PR | Contents | State |
| --- | --- | --- | --- | --- | --- |
| CodexCoder21Organization/jvm-libp2p | [fix/r1-yamux-paused-stream-frame-bound](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/r1-yamux-paused-stream-frame-bound) | 1d31d81c0c19c0e378ceae4d0136c3a9708c658f | https://github.com/CodexCoder21Organization/jvm-libp2p/pull/64 | Final fix and tests | Local five-round gate clean, full-suite failure classes reproduced on develop, CI dispatch blocked; OPEN |
| Same | [wip/l64-control-traffic-fix](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64-control-traffic-fix) | 1d31d81c0c19c0e378ceae4d0136c3a9708c658f | no PR | Same final source | Durable checkpoint |
| Same | [wip/l64-control-traffic-red](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64-control-traffic-red) | de4282e68b9199b6126298bf406367194275bff3 | no PR | Failing TCP tests before implementation | Both fail first attempt as described |
| Same | [wip/l64-develop-baseline](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64-develop-baseline) | dcb03df3b54f5c2b515f3448f13613d2be3c9577 | no PR | Rebased old PR before review fixes | One known storage baseline failure |
| Same | [wip/l64-handoff-evidence-20261022](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/l64-handoff-evidence-20261022) | 26ab23db63192e0bedca5ef74394e2c426d933c1 | no PR | Findings, summaries, full extracted failure stacks, CI observations, pre-existing R1 stash export | Evidence snapshot at this SHA; this handoff document advances the same branch, so re-check its current head. Source parent is the verified PR head |

All heads were verified with git ls-remote. The primary working tree is clean. One inherited R1 stash predates this lane; it was not applied, changed or deleted, and is preserved remotely as a patch so the local checkout is not its only copy.

Evidence: [findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/l64-handoff-evidence-20261022/handoff-artifacts/l64/findings.md), [verification summary](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/l64-handoff-evidence-20261022/handoff-artifacts/l64/verification.md), [full extracted failure stacks](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/l64-handoff-evidence-20261022/handoff-artifacts/l64/failure-stacks.txt), [CI observations](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/l64-handoff-evidence-20261022/handoff-artifacts/l64/ci-observations.md), [inherited stash patch](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/l64-handoff-evidence-20261022/handoff-artifacts/l64/pre-existing-r1-stash.patch).

No production artifact was published, no service was deployed or restarted, and no code was merged. This is verified from the session's actions/delegate scope, PR mergedAt=null, and develop still at b01e08e5. Build outputs, jars, caches and XML were intentionally not committed; required source and forensic text are on the linked branches.

## Next steps

1. Re-verify PR and exact-head check state using the commands below. If required CI is already green, the original lane request is complete; leave the PR open and do not merge.
2. If still undispatched, obtain explicit approval for any rerequest/CI-run mutation or have the authorized CI owner recover dispatch. The owner has not approved it yet. Re-check whether the suite's WIP association changes the correct dispatch path; do not blindly repeat the same action.
3. After authorized dispatch, use build-watchman to observe required-green on exactly 1d31d81c0c19c0e378ceae4d0136c3a9708c658f. No CI rerun as debugging, no bypass, no merge/queue/deploy/publication. No extra local repeat round or second full-suite run is needed because those bars are already met and the source is unchanged.
4. Update findings/PR if CI reveals a genuine new defect. Only then perform a new failing-first local reproduction and fix; do not retry CI unchanged.
5. Complete/archive this handoff after exact-head required CI is green and update the owner with that result.

## Reusable / operational knowledge

- Existing authorized checkout was /tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/workspace/r1-R1-libp2p. Clone fresh if resuming elsewhere. Read README/FORK.md and repository instructions first.
- Required reading: https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md and https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md.
- Targeted command: ./gradlew :libp2p:test --tests 'io.libp2p.mux.*' --no-daemon --max-workers=2. The five-run gate used an external Gradle init script setting Test.maxHeapSize='128m', and cleanTest before each run. It did not change repository build policy.
- Full command already run once: flock /tmp/claude-501/complete-suite.lock ./gradlew test --no-daemon --max-workers=2. No remote builds/tests.
- CI read-only: coursier launch buildwatchman:build-watchman:0.0.21 -r https://kotlin.directory -- --repo CodexCoder21Organization/jvm-libp2p --pr 64 --until-merged --check 'kotlin.build (remote)'. --until-merged is read-only; stop it when the requested gate is resolved, as merge is prohibited. Track long commands with hard deadlines and stop owned watchers before handing back.
- Check suites: gh api repos/CodexCoder21Organization/jvm-libp2p/commits/1d31d81c0c19c0e378ceae4d0136c3a9708c658f/check-suites. Keep PR/check polling at least60 seconds apart.
- The inherited remote fetch refspec covers only develop. A default force-with-lease had no tracking ref for the feature branch and rejected stale info; explicit lease against the freshly verified old feature SHA was used. Do not use unbounded force.
- gh pr edit is unreliable here; use gh api PATCH with a JSON body file and read it back. PR body ends with the requested Claude Code footer; commits use the requested co-author trailer.
- The canonical handoff README retains an old merge-based section. Current create-handoff skill and explicit lane rules supersede it: use the central service, no handoff PR/merge.

Build outputs were last used for the pure-develop diagnostic control and are not release artifacts. Source was restored and is clean at the verified PR head; any later artifact build must rebase/build from source. No artifact publication is authorized.

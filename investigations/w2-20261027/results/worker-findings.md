# W2 handoff findings

Agent: fable-W2-1027
Handoff: hf-2026-10-22-restore-required-verification-for-jvm-libp2p-explicit-close-retry

## Plan
- IN PROGRESS: Claim and read the whole handoff and linked guidance.
- TODO: Re-verify PRs, default branch, artifacts, verification evidence, and CI setup.
- TODO: Resolve remaining work or document why it is already done or superseded.
- TODO: Record branch/PR state, update or complete handoff, and write result JSON.

## Initial observations
OBSERVED: No active claims appeared; claimed successfully as fable-W2-1027.
OBSERVED: The worker is authorized to prepare PRs but cannot merge, enqueue, approve, or deploy.
INFER: The handoff title suggests verification work; the actual scope is not yet established.

## Live triage checkpoint
OBSERVED: Default branch is develop, at 25efb1996bd79e59e6eef28d2d44d9a0405fdd78. It is a native Gradle fork with no kompile runner.
OBSERVED: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/67 merged 2026-10-23T02:31:44Z. PR 69 is also merged.
OBSERVED: Existing PRs https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75, https://github.com/CodexCoder21Organization/jvm-libp2p/pull/76 and https://github.com/CodexCoder21Organization/jvm-libp2p/pull/77 already address this handoff. Their branches and prior investigation evidence remain remote.
OBSERVED: Effective develop rules API returns [], legacy branch protection returns 404, Actions now permits selected actions. Native CI PR Linux gate passed; Windows has eight baseline failures. Kubo PR Linux passed but a separate Windows cleanup proof failed. Noise PR tests passed but Dokka ended with Metaspace.
OBSERVED: Prior supervisor directions in the remote evidence explicitly authorize native Gradle CI and Linux bld-build as the gate, track Windows separately, and prohibit larger resource limits. Current worker task likewise prohibits merges and rule changes.
INFER: Do not add a second build system or duplicate these three PRs. Existing CI can be reviewed immediately while remaining fixture problems are investigated.

## Updated plan
- DONE: Claim, whole handoff, handoff triage guide, racing guide, fresh repo location/clone.
- IN PROGRESS: Verify candidate tests and independently review native CI/test corrections.
- TODO: Diagnose Kubo immediate cleanup and Noise documentation failure from first-attempt evidence.
- TODO: Salvage existing PRs, record exact continuation if the time box expires, and write result.

OBSERVED: Added an evidence-only branch https://github.com/CodexCoder21Organization/jvm-libp2p/tree/verify/w2-close-and-dokka-20261027 at 23af060a. It preserves current implementation and original resource limits. The Windows probe invokes Java ProcessBuilder plus an independent native WaitForSingleObject(handle,0) observation before immediate log deletion; 128 independent first attempts on four real callers. The Dokka probe runs all actual dokkaJar tasks and records JMX/VM.classloader_stats without changing limits. No speculative fix is present.
INFER: A native process signal comparison can distinguish premature Java exit observation from another file holder. A classloader trace can identify whether Dokka retains duplicate compiler loaders. Neither mechanism has been proven yet.

## Windows process mechanism established
OBSERVED: First valid process probe https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40577357438 fails 128/128. Each attempt: Java waitFor(5,SECONDS) true; exitValue 1; native WaitForSingleObject handle returns WAIT_TIMEOUT (258) before AND after failed Files.delete. Full stacks downloaded. Initial probe setup run 40577066631 omitted repository version metadata and is explicitly not bug evidence.
OBSERVED: OpenJDK upstream commit https://github.com/openjdk/jdk/commit/b77bd5fd6a6f7ddbed90300fba790da4fb683275 already changes the exact early-exit fast path to use native signal state. Java17/21 update sources still check exit status instead. JDK24 source includes native signal check.
INFER: Adopt the existing upstream correction via a fixed test JDK if the same reproducer turns green. Do not add sleeps, retries or alternate cleanup waits to the Kubo consumer test.
OBSERVED: Independent native CI review confirms all three earlier test corrections preserve assertions and wait bounds; identified dropped JDK11 floor coverage. A mechanical Java11 runtime setup after Java17 Android SDK preparation is delegated for the existing CI PR.

OBSERVED: CI review correction is pushed to existing OPEN https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75 at a4b0b707772b349aaeec12f18b9a46cbac1a6f03. Actionlint1.7.12 and diff --check passed; no implementation/test changes in this correction.
OBSERVED: JDK24 first process probe stopped at Kubo readiness, so it is not a green close control and no candidate toolchain pin has changed. A same-runner17/24/17 experiment retains128 first attempts per variant and prints every failure. Evidence checkpoint aa18dfad is remote.
OBSERVED: Original-limit Dokka-only profile succeeds, near cap at525173912 used bytes. This does not erase original full-build OOM. Need compiled-build context plus retained-root evidence before a Dokka implementation fix.

## Consumer fail-first proof
OBSERVED: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40578818177 tested QuicKuboTestJava with immediate log rename after the unchanged timed process wait. 2 tests, 1 failure, 0 errors/skips; all1000 pings completed. Failure is Files.move(...daemon.log,...stopped-daemon.log): file in use, at QuicKuboTestJava.java:115. This is the consumer boundary guard; source is checkpointed on verify/w2-owned-kubo-20261027 at45ef9df7. Java24 control is now dispatched against identical source.
OBSERVED: Same-runner17/24/17 native process experiment40578626631 reached close in101/99/103 attempts respectively. Java17 close failures101/101; Java24 close failures0/99 with native signal0; Java17 reversal103/103 failures with signal258. Separate readiness deadlines occurred27/29/25times. These are not called complete green runs; the close contradiction is fully discriminated while startup is a separate probe/fixture problem.
INFER: The event loop in the owned Kubo fixture and copied probe calls WatchKey.reset without pollEvents. WatchKey retains its event list and requeues immediately, creating a busy loop. This is a specific API-use defect to correct, not a claim that the host is overloaded. Need honest first-attempt evidence after consuming the actual events; preserve five-second bound and128 attempts.

## Current candidate proof checkpoint
OBSERVED: Exact CI head a4b0b707 full Linux/JDK11 run40577902249 produced1445 total,1443 passed,0 failures/errors,2 existing skips. All12 retry scenarios and both retirement cases pass. Native CI PR body updated via REST after gh pr edit hit its known classic-projects GraphQL error.
OBSERVED: Consumer Kubo proof40579528959 on the same strengthened test source as red40578818177 passed2/2 onJDK24,0 failures/errors/skips, plus Detekt/Spotless. A17 reversal is running. Final source-only candidate872086e0 adds event consumption, the immediate file-release guard, CI11/24 pins and FORK explanation on the evidence branch. Independent lifecycle/test review is active; actual PR76 has not been changed yet.
OBSERVED: The drained-watch native experiment40580238188 still had readiness failures73/69/78 of128 by17/24/17. It nevertheless discriminates every completed close:17 fails55/55,24passes59/59,17 reversalfails50/50. Therefore missing pollEvents is an API-use correction but is NOT proven to cause the copied-template probe's separate readiness failures. Do not claim startup was fixed. The real owned-init consumer proof has no readiness failure.
OBSERVED: Native build/static/documentation probe40579988965 is terminal and captured a heap artifact180067833compressedbytes. A bounded hosted MAT analysis is being dispatched; no local JVM/heap parser is run on the shared machine.

## Terminal checkpoint 2026-10-27 21:10 UTC
OBSERVED: Both final PRs are OPEN/non-draft and all final source refs are remote. The worker reached the first-attempt targeted-proof bar. CI-only duplicate is CLOSED with comment https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75#issuecomment-6453453385.
OBSERVED: Complete scope ledger, branch table, tests, exact pending settings/landing steps and prior diagnostic limitations are in W2-201254-own-handoff.md. The result JSON is valid ASCII and records PRS_READY/open-claimed.
OBSERVED: Root/CI11/Noise11 checkouts are clean, no stashes/local-only commits/extra worktrees. All meaningful source and evidence is pushed.
INFER: Nothing remains for this worker before the orchestrator's merge/CI/rules work; no publish or deployment is required.

## Plan completion
- DONE: Claim/read/triage/reverify.
- DONE: Resolve Windows cleanup by adopting an existing upstream JDK fix, with actual red/green/reversal and final2/2 static proof.
- DONE: Preserve native CI and shared tests, reduce Noise to one file, preserve42/42 fresh and earlier red/green/reversal controls.
- DONE: Close superseded CI with a comment and preserve every useful change.
- DONE: Independent review, remote checkpoint and clean-checkout sweep.
- DONE: Worker result/handoff prepared; orchestrator owns landing, current native CI and the required-check setting.

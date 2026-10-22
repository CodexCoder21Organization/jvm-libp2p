Lane F66 started 2026-10-22 UTC.

Plan:
- IN PROGRESS: read guidance, review evidence, and clone current PR head.
- TODO: promote two probes; record first-attempt red results and checkpoint.
- TODO: correct setup result ordering; verify pending and completed outcomes; reversal control.
- TODO: run 24 new, 242 mux/negotiation, 49 lifecycle cases and one complete suite through shared slot.
- TODO: review, push PR branch, update description, write final file.

Constraints: no publication, merge, queue, deploy, assertion changes, or timeout increases.

OBSERVED: PR remains OPEN at e7153395f4a1a05860906366cc46e48ce8fc6422, branch fix/laneFURR3-yamux-capacity; base is develop, not main. Fresh clone workspace/laneF66/jvm-libp2p. Root README read; no root AGENTS.md. Guidance downloaded from DocumentationRepository via gh api. No origin/main exists; use origin/develop for the required current-base rebase.

OBSERVED: both promoted callback probes red on first attempt (2/2), no extra attempts or skipped cases; XML saved in .evidence/F66/red. Tests copied from review branch with fixture names updated and its unrelated third test omitted. Existing tests unchanged. Gradle-native Kotlin tests are used because this repository is a Gradle project.
The mechanism is protocol-future settlement running synchronous callbacks before the pending handler result and public controller are settled; the evidence that demonstrates it is both deterministic callback probes reporting a different controller result while the protocol reports StreamCapacityException.
Plan: read guidance DONE; red reproducer DONE; setup ordering fix IN PROGRESS.

Invariant table before implementation:
| Resource | Creator/owner | Terminal states | Caller behavior and re-entry | Test coverage |
| Protocol future | StreamOverNetty / stream | completed, cancelled | completion after recorded cause uses that cause; prior outcomes remain | callback probes plus protocol cross-completion case |
| Handler result | caller handler / caller | completed, cancelled | capacity setup settles pending result before protocol callbacks; source returned after cause is settled immediately | callback probes plus handler result assertion |
| Public controller | MuxHandler / caller | completed, cancelled | pending completion during callback uses recorded cause; prior outcomes remain | callback probes plus controller cross-completion case |
| Child channel | mux / mux | closed | record cause first; no callback runs under a lock; sibling stays usable | existing capacity and lifecycle tests |
No persisted state is involved.
Implementation plan: retain the returned handler result on the child, settle that result before protocol notification, and make owned protocol/controller futures consult the recorded cause at completion. A handler returned after the cause is recorded is settled before forwarding. No synchronization around callbacks.

OBSERVED: original two probes passed after setup-result change. Strengthened both probes to check handler-result callbacks attempting protocol/controller completion with a value or another exception, and same Throwable identity across protocol, handler result, public controller. First green attempt preceded this strengthening; fresh strengthened run now active. Red checkpoint: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/449df13a389710a6aab990eb120102aa90b72f78 ; evidence branch https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/laneF66-evidence .

OBSERVED: capacity group 26/26 passes first attempt (24 prior + 2 promoted), no skipped or extra attempts; strengthened probes included. Reversal with production change removed: both strengthened probes red on attempt 1 (2/2), no skipped or extra attempts. Production change restored. Evidence XML in .evidence/F66/capacity and reversal.

OBSERVED: passing-fix checkpoint https://github.com/CodexCoder21Organization/jvm-libp2p/commit/2c9857aca73ee336326c902d29fa6d7409000e53 pushed to evidence branch. Baseline comparison JSON from review branch contains 20 entries: 14 red and 6 pass, one attempt on base and head, all matching.
INFER: current capacity total is 26 (24 prior plus the two requested probes); the brief says re-run 24, so both the original 24 and promoted two are included.
Broader controls active for mux.*, multistream.*, core review tests, stream visitor, mux activation/publication, and stale connection setup. Exact prior 242/49 selectors are not recorded in the supplied findings; current groups will be counted from XML and class selections documented. No source/test artifacts will be added to PR branch; XML and result summaries remain on evidence branch only.

OBSERVED: broader controls pass first attempt: 268 mux/negotiation cases = 242 prior controls + 26 capacity cases; 56 lifecycle cases (a broader selection than prior 49). No skipped or extra attempts. XML plus summary archived in .evidence/F66/controls.
Independent test/code review: source fixture changes do not change interfaces or add interface calls. Reviewer notes a possible overlapping-completion ordering between reading cause and superclass completion. Existing deterministic probes cover callback re-entry after cause recording, not that overlapping caller operation. Scope question sent to owner; no decision recorded yet. No callback is run under a lock.

2026-10-22 ~23:00 UTC: complete suite started through shared slot using timeout cap 10800 seconds and requested Gradle invocation. One complete run only. While optional scope answer is pending, assumption is normal first-completion behavior for overlapping caller operations, with cause authority for callback-initiated completions after recording. Reviewer is checking this narrow point against the public API.
Plan: deterministic red/green/reversal DONE; 242 prior controls +26 capacity +56 lifecycle first-attempt checks DONE; complete suite IN PROGRESS; final review/PR update TODO.

OBSERVED: the shared-slot wrapper still had an empty log and no Gradle test task started; stopped it before any complete suite began. Single complete run remains unused. A further public completion path is being checked: completeAsync with a direct executor can run inside a handler-result callback; inherited CompletableFuture implementation may bypass completion overrides. Both probes now exercise direct and direct-executor paths in separate real channel pairs. Targeted run active.

The additional mechanism is inherited completeAsync publishing a value/exception without the setup-cause completion override; the evidence that demonstrates it is both direct-executor callback probes red on attempt 1 against the first fix, no extra attempts. This is a public callback path after cause recording, independent of overlapping caller operations. XML .evidence/F66/async-red.

OBSERVED: both probes pass on attempt 1 after adding completeAsync overrides (two cases, each checks ordinary and direct-executor completion; no skipped or extra attempts). Before the async change, the same tests were red on attempt 1; that is the async reversal control. Independent review reclassified the earlier read-then-complete note as normal overlapping first-completion behavior, not proof of a callback-after-recording defect. The deterministic async gap was fixed. Existing cancellation/completion controls remain selected in the broader run.

OBSERVED: clean PR work branch fix/laneF66-results contains 5 changed source/test files and zero evidence files. Evidence is separate on wip/laneF66-evidence. Final complete checks will run on this clean code branch. Rebase commands now run in a fail-fast shell after committing source changes; rebase on origin/develop completed before this control build.

2026-10-22 23:04 UTC: final controls pass on attempt 1 with async fix: 242 existing mux/negotiation +26 capacity +56 lifecycle, zero red/skipped/extra attempts. XML archive .git/f66-final-controls will be moved onto evidence branch. Only a trailing-space cleanup in the new test followed this check; git diff --check now passes. Single complete suite queued through shared slot from clean code branch after successful fetch/rebase.

Independent review complete: initial implementation and added async overrides reviewed in separate passes; no remaining correctness issue identified for ordinary post-recording completion paths. No interface signature or added interface call, so no implementer updates needed. Async probes explicitly exercise the Supplier+Executor overload with a direct executor; the default overload delegates to it and was inspected. Prior completed/cancelled behavior is covered by existing controls. Intentional obtrude replacement remains normal caller behavior. The earlier overlapping-call observation was refuted as a separate defect by public completion semantics; the post-recording async path was demonstrated and fixed.

OBSERVED: removed the now-unused ConnectionClosedException import from the promoted test fixture while the suite is queued. No existing test was edited; all assertions and time budgets retained. The only edits to review probes are neutral fixture naming, omitting their unrelated third case, adding result-callback/direct-executor checks, and exact cause identity assertions.

OBSERVED: README already documents the negotiated capacity binding and typed capacity result; this change restores that existing behavior across pending setup results, so no README change is needed. The complete suite remains queued with a live shared-slot holder. Targeted verification and review are complete; remaining work is complete-suite XML comparison, PR update, final evidence push.

The cancellation mechanism is the new wrapper returning completeExceptionally(cause) from cancel, even though capacity settlement is not cancellation; for a previously cancelled future the same call returns false instead of the usual true. Evidence experiment: new public-API scenario testCapacityKeepsControllerCancellation checks return value and isCancelled for pending and prior-cancelled controllers, with real channel pairs. No existing assertions changed. Queued complete-suite wrapper stopped again before any Gradle output; complete run remains unused.

OBSERVED: cancellation scenario red on attempt 1 (1 case, no extra attempts). The pending controller receives the capacity exception but cancel returns true; isCancelled remains false. The return should be false. The same code must preserve true for an already-cancelled result. XML saved in .evidence/F66/cancel-red on evidence branch.

2026-10-22 23:16 UTC OBSERVED: final groups pass first attempt after cancellation correction: 242 existing mux/negotiation controls, 27 capacity (24 original +2 promoted +1 cancellation scenario), 56 lifecycle controls, zero red/skipped/extra attempts. The cancellation scenario covers both protocol and public-controller pending/prior-cancelled states. Independent narrow code/test review found no remaining issue; no interface additions. Single complete suite now queued again after successful rebase; earlier queued wrappers never started Gradle.

2026-10-22T23:21:07.910286+00:00 OBSERVED: complete suite queued for shared slot; log bytes 0, task entries 0. No source changes.

2026-10-22T23:26:23.913046+00:00 OBSERVED: complete suite Gradle active; log bytes 61679, task entries 32. No source changes.

2026-10-22 23:27 UTC OBSERVED: single complete Gradle suite exited 1, 1214 cases: 1191 pass, 21 red, 2 skipped, no retry XML entries. All 20 red cases from original full suite are present; six had passed when previously selected individually. Additional red: io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest / initiator times out silent responder while waiting for message 2(). Expected ReadTimeoutException, observed ConnectionClosedException. Checking unchanged code with targeted runs; no second complete suite. Full XML .git/f66-full-evidence. Duplicate class/name identities (15) occur in parameterized control classes, not retry entries; preserve raw XML rather than treating name collisions as retries.

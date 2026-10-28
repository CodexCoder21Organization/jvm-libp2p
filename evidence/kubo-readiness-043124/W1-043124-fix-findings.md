# Review repair progress
Started 2026-10-28 04:31 UTC. Deadline 06:01 UTC.
PR https://github.com/CodexCoder21Organization/jvm-libp2p/pull/80 initially OPEN at 9aa6a6ba06711504f2bc58fc09cc59406021782b; default/base develop.

Plan:
- [in progress] Read handoff, review, repository guidance, and reproduce both real-Kubo collision cases.
- [pending] Push failing maintained tests, fix process/identity ownership and allocated endpoints, prove green then reversal red.
- [pending] Search adjacent gaps, update FORK.md, separate test/code review.
- [pending] Rebase, verify final native bld-build first attempt, push with explicit lease, deliver JSON. No merge/enqueue/deploy.

OBSERVED: Fresh clone /code/ws/W1/workspace/jvm-libp2p-fix-043124, README and FORK read; no AGENTS.md. Repository is native Gradle, no Kompile wrappers. Handoff and complete review read. Required guidance read: DocumentationRepository architecture/TESTING.md and PHILOSOPHY.md.
OBSERVED: workflow accepts any HTTP200 from fixed5001 before checking owned PID; QUIC test dials fixed4001. Existing assertions include1000 pings, all retained.
Mechanism: unrelated Kubo's API satisfies readiness even after intended daemon fails to bind. Reproducer: start two real Kubo nodes with distinct identities, use first endpoint for second readiness; separately verify dead owned PID cannot be accepted.

Invariants before implementation:
| Resource | Creation/owner/close | Terminal/re-entry/observable behavior | Public-path proof |
|---|---|---|---|
| Kubo repo | wrapper creates fresh temp repo; wrapper removes after owned process reaped | no persisted or ambient repo reused | healthy wrapper run and failure cleanup |
| Kubo PID | wrapper starts and owns Popen; wrapper terminates/waits on every exit | exit before/during build fails; never close another process | real bind failure, death during command |
| API endpoint | Kubo binds loopback port0 and writes owned repo/api | ready only while owned PID lives AND response ID equals repo identity | distinct live node response rejected |
| QUIC endpoint | owned daemon binds UDP0; actual address delivered via environment | test only dials the owned peer/address in native job | unchanged1000 ping integration in bld-build |

OBSERVED milestone: test-first remote checkpoint https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/kubo-readiness-red-043124 at b77f6047. Old readiness: 3 cases, 2 assertion failures (dead PID accepted; wrong live identity accepted), 1 healthy pass. Full traces /tmp/W1-043124-red.log. Fixed readiness first execution:3/3pass. Reversing ONLY readiness implementation with current CLI/tests: same2 assertion failures; /tmp/W1-043124-reversal.log. Restored fixed implementation after reversal.

OBSERVED adjacent gap: ordinary SIGTERM bypassed Python finally, leaving real Kubo running. Added public CLI regression; assertion `Kubo outlived its wrapper` fails. Installed SIGTERM-to-SystemExit143 handler; regression passes. Removed only registration and observed same assertion fail, then restored. Logs /tmp/W1-043124-signal-{red,green,reversal}.log. Broader gap search now retains6real-Kubo cases: two collision cases, healthy readiness, matching allocated endpoints/status23/cleanup, owned-node exit during command, SIGTERM cleanup. Full candidate6/6pass first attempt after each distinct change; /tmp/W1-043124-all-green.log.
OBSERVED documentation sweep: only projects/NetLab.md in DocumentationRepository, no HostedServiceDocumentation reference; PlanRepository references are historical challenges/handoffs and unrelated workstreams, not this fork's build instructions. No central doc needs a claim changed for this scoped CI readiness repair. FORK.md primary note updated with actual full workload and existing green native run; releases remain manual. README links FORK and needs no duplicate note.

OBSERVED independent review found direct-child-only cleanup could leave a real descendant alive. Added descendant to cancellation test; old stop fails `Descendant outlived its wrapper` after existing10second cleanup bound. New isolated process group closes/reaps the actual descendant, command and Kubo; first attempt passes. Logs /tmp/W1-043124-descendant-{red,green,reversal}.log.
OBSERVED review also caught new environment-based test configuration conflicting with Testing documentation. Replaced with explicit binary CLI argument and exclusively owned per-run properties fixture passed to wrapper. Kubo itself receives explicit --repo-dir. Java ping loads explicit build/kubo-node.properties; missing fixture raises useful IOException; no new getenv/system-property configuration remains. File is removed on success/error/SIGTERM, concurrent wrapper fails rather than overwrites. Kept all1000pings and original assertions/bounds.
OBSERVED exact Kubo archive SHA25642045802fe60c64fb01350bc071190c534d600fe269759c06e27e22b2012fd3e and binary version0.34.1 verified locally.

OBSERVED review resolution: residual descendants ignoring TERM after leader exit now have real TCP-listener coverage. Baseline endpoint remained live; final group SIGKILL closes it; exact stop reversal reopens same assertion. Both zero and negative PID values were accepted by signal0's process-group semantics; added two real-endpoint subcase assertions, rejected nonpositive PID values; first green and exact reversal recorded. Current local final9/9pass in8.381seconds (/tmp/W1-043124-final9.log). No test count/timeout/iteration/assertion reduction. Ownership collision guard red/open-w vs green/open-x preserves existing fixture bytes.
OBSERVED documentation now shows wrapper-aware full builds in both README and FORK, plus targetedKubo invocation and prior green bld evidence. The original Windows early return is unchanged from9aa6a6ba; it is not an introduced regression or a promise of WindowsKubo verification. This scoped bld-build is explicitlyLinux. Independent reviewer rechecking final resolutions.
OBSERVED remote source checkpoints: b77f6047 red readiness; f2a1984b first green wrapper; afb4be37 final source plus disposable proof workflow at https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/kubo-readiness-green-043124 . Earlier targeted runner40633654586 passed first attempt but predates fixture/group refinements; do not substitute it for finalsource verification. Obsolete own legacy build runs40633591038/40633654504 cancelled rather than waited on. Current proof runner validates changed source; PR80 remains unmodified pending final proof/review.

OBSERVED final review: /tmp/W1-043124-independent-review.md now verifies every introduced code/test/doc finding resolved, final9/9pass log reviewed; inherited Windows earlyreturn unchanged and explicitly outside Linux verification claim. Product branch committed7663ef0c, fetched/rebased latestdevelop (up to date), checkedPR stillOPEN at9aa immediately before push, used explicit --force-with-lease. All6changed productfileblob hashes match proofafb4be37 exactly; no disposable proofworkflow included in productdiff.
OBSERVED handoff service claim failed because another session has completed/archived the broader handoff meanwhile: `Handoff ... is already completed and archived; reopen it before attempting to complete it again.` This scoped author repair continues independently; no handoff re-open/complete or merge/settings action taken on that basis.

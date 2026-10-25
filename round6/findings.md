# F71g round 6 findings

PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71
Started: 2026-10-25 00:23 UTC. Hard wall limit: 90 minutes.

Plan:
- [in progress] Item 1: read production/test fixtures, write invariant table; add public callback regression; record red; fix; green; reversal; restore; four shutdown classes first attempt; push.
- [pending] Item 2: narrow README guarantee and mention old unflushed-only limitation; push.
- [pending] Item 3: inspect two original failures and child harness; record mechanism or unknown before edits; three solo runs and one overlapping harness-class pair; classify honestly; push.
- [pending] Review code and coverage independently; publish round6 evidence on evidence/pr71-20261024; REST description update; stop owned JVMs; final audit.

OBSERVED: Initial gh pr view matched required head 3e1751baddc1e37fc40c51ff1e28b9135c6acc23, OPEN, base develop. Fresh clone under workspace. No subagents will run. Base develop overrides generic main instructions. Evidence remains outside PR tree and will be appended under round6 on evidence branch.
OBSERVED: Read F71f-final.md and R71adv5-findings.md. Prior failures were empty-output whole-process deadline assertions (10s inline; 15s responsiveness), with no child stack. Historical wait mechanism is not established by those logs.

Item 1 invariant table (before implementation):
| Resource | Creator / owner | Terminal release | Public test |
| Child outbound payload | caller transfers to outbound buffer; callback owns retained copy | synchronous close releases buffer; mux releases copy | three real LocalChannel writes close inside callback; all refCnt zero |
| Pending parent promise | custom mux creates; remains pending | closed child must register no completion listener | instrument public addListener, assert zero registrations after close |
| Completion token and hook | child loop may install only while child still owns outbound buffer | close must leave no registered hook | track public add/remove hook API, close child/parent/server and barrier live loop; zero hooks |
| Loop / channels | test creates real DefaultEventLoop and LocalChannels | finally close all channels and terminate owned loop | bounded public futures; no private access or reflection |
INFER: Mechanism to fix: onChildWrite closes the child synchronously before returning its pending promise, so close cleanup runs before the completion token exists and doWrite then installs an unreleasable hook. Reproducer: ReentrantChildWriteCloseTest with three children, live-loop barrier, exact zero-hook assertion and zero parent listener registrations.

OBSERVED: Required reading: repository README.md, CLAUDE.md (AGENTS.md absent), FORK.md, DocumentationRepository architecture/TESTING.md and PHILOSOPHY.md; CODE_REVIEW.md fetched for review pass. The brief's exact base develop, bounded per-class register and no-subagent restrictions take precedence over generic main/full-suite/delegation instructions.
OBSERVED: The two original emitted failures were recovered from R71adv5.log because the R71adv5 workspace no longer exists. Exact emitted stacks are preserved under round6/reviewer-failure-transcript.txt; both assertions contain empty child output. Original XML and child stacks are unavailable in this checkout.
INFER: Item 3 mechanism before any related edit: unknown; the parent was waiting for child process exit, and the available historical output does not identify the child's active phase or establish whether a changed PR path was reached. The inline child constructs two hosts, starts them, connects, checks execution, then stops; the responsiveness child constructs 162 hosts, starts them, connects the monitor, runs the 160-peer storm and ping checks, then stops. Output only occurs near body completion, so empty output cannot isolate startup, preparation, connection or cleanup.
OBSERVED: Exact harness reference search finds no other test class using NoiseHandshakePathHarness or NoiseHandshakeStarvationHarness. Optional clarification sent for the intended companion class; NoiseAlgorithmReadinessTest is the proposed adjacent child-JVM class. Continue Item 1 while waiting. External observer prepared to record owned child PID/CPU/state and request one ordinary JVM thread dump before an unchanged deadline, with no test source or deadline edit.

OBSERVED 00:29 UTC: Item 1 test committed locally as https://github.com/CodexCoder21Organization/jvm-libp2p/commit/fc3587a1825adfebe341266ff77922d5a6e8bb1f while the unchanged-production first run compiles. Private home contains only copied dependency/wrapper caches, no compiled project output. Test uses real local transport, public mux extension, public event-loop hook methods and public promise listener registration. No forced GC or heap timing is used; hook and callback ownership are counted directly.
OBSERVED: Planned first-attempt shutdown register: OffLoopParentWriteNotificationTest (6), ReviewTerminalWriteConversionTest (4), ReviewCompletedShutdownTest (6), ReviewChildLoopAffinityTest (3). These are the four retained-delivery / settle-before-termination classes specified by rounds 4 and 5.
INFER: Inline child has no application child stream, so the new Mux completion-hook path does not run during that connect; parent transport connect/listen/close can exercise the PR converters and close ownership. The responsiveness child uses Ping child writes as well. Therefore a source-only claim that all PR paths are absent would be incorrect; capturing the actual child phase is necessary before attribution.

OBSERVED 00:32 UTC: Evidence checkpoint pushed: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/695e8c85 (round6 findings and exact emitted original deadline stacks). The current first test command is actively compiling main Kotlin; owned compiler PID 3408068. No runtime failure or pass has yet occurred. Maximum owned Gradle test commands remains 1.

OBSERVED 00:36 UTC: Initial command remains in main Kotlin compilation; compiler thread dump confirms active expression/type checking, not an unexplained parked wait. No test body/record exists yet. No production edit has been made. Owned Gradle daemon 3405084, compiler 3408068; at most one owned test command. Evidence thread records stay only under round6.

OBSERVED 2026-10-25 00:42 UTC — 20-minute status: RUNNING.
Request: complete the three round-six items on https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71, preserve first attempts and evidence, do not merge.
PR: OPEN; remote head remains 3e1751baddc1e37fc40c51ff1e28b9135c6acc23 (fresh status read recorded separately); local test commit fc3587a1825adfebe341266ff77922d5a6e8bb1f. Evidence checkpoint is https://github.com/CodexCoder21Organization/jvm-libp2p/commit/695e8c85bee21910883b77c8c695d34758aa47a8.
Progress: main Kotlin compilation completed and Java compilation started in the first red command. No test body has run and no runtime result is claimed. Production remains unchanged. The regression drives three real children through synchronous callback close, then checks zero loop hooks and zero pending-parent callback registrations.
How the planned fix works: the callback may close its child before returning its parent promise. The post-callback guard will check child openness and outbound-buffer ownership before installing a token. Closed children therefore acquire neither a new hook nor a parent completion listener.
Delegated work: none; prohibited by brief. Maximum owned Gradle test commands observed: 1.
Blockers: none. Companion-class clarification is optional; no other class references either exact handshake harness, and the adjacent NoiseAlgorithmReadinessTest is the stated default.
Plan: continue the first assertion-red, fix/green/reversal/restore, four shutdown classes, README, then the solo and paired deadline investigation. Active compilation is evidenced by generated classes and task progress.
No unnecessary holds: items follow the user's explicit order. No idle landing gates: merge/approval/closure are prohibited. Owner direction captured: none new.
Next steps: finish the regression controls and shutdown register, narrow the README, execute the deadline reproduction plan, push evidence and description, and stop owned JVMs.
Lesson: a callback can invalidate ownership before returning; validate ownership again before registering a resource that outlives the callback. Lesson: an empty-output process deadline identifies the parent's wait, not the child's phase; capture the child before assigning a cause.

OBSERVED 00:46 UTC: Main and fixtures compiled; initial command is in test Kotlin compilation with the same unmodified production source. Its source manifest ties the new test bytes to fc3587a1825adfebe341266ff77922d5a6e8bb1f and verifies initial-head production equality. No test body or runtime result yet. Prepared external diagnostics poll owned children once a second and request a single ordinary JVM thread dump only near an existing deadline; no observer has been started yet.

OBSERVED 00:50 UTC: Item 1 FIRST runtime attempt red, 1 check / 1 assertion failure / 0 errors / 0 skipped, XML 3733ms. Exact assertion: "Synchronous child close must leave zero completion shutdown hooks ==> expected: <0> but was: <3>". All three child writes settled and payload refCnt assertions passed first. Full emitted stack is round6/item1-red/full-failure.txt; XML and complete Gradle log retained. Command wall 1476559ms includes the fresh compilation. Test commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/fc3587a1825adfebe341266ff77922d5a6e8bb1f checkpointed to the still-open PR branch; no production source changed for this red.

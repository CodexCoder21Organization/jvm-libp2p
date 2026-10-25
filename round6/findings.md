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

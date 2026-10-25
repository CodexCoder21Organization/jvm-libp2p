# F71h round 7
OBSERVED: Started 2026-10-25; initial PR head exactly 85b4297856d09df89816bc571137bd392dc498ff, OPEN, base develop. No subagents permitted or used.
Plan:
- IN_PROGRESS: Read required guidance and earlier probe; fresh clone, private Gradle cache.
- TODO: Item 1 deterministic public regression red, fix green, reversal red, restore, five shutdown classes; push code and separate evidence.
- TODO: Item 2 name stream-open wait path before changes; three solo attempts plus fixture pair; preserve every first attempt.
- TODO: Item 3 README contract; push.
- TODO: Independent test/code review passes, update REST description, stop owned JVMs, final report.
OBSERVED: Fresh clone checked out the exact requested head and rebased on origin/develop without movement. No repository AGENTS.md exists. Read README, FORK, prior F71g final, R71tc5 findings and existing shutdown/reentry probes. The reviewer's original local directory is absent; its full summarized trace and paths remain in R71tc5-findings.md. Private Gradle cache copied from round 6; all commands use --offline --max-workers=2. Initial red class command running.
INFER: The mechanism I am about to fix is that termination ownership is attached only to a pending parent operation and removed when its delivery finishes; an accepted unflushed child write after that delivery has no owner to settle it when closure is refused. The reproducer is UnflushedWriteTerminationTest: real local Netty parent/child, first pending parent completed and owner barrier, accepted unflushed write, refused child close, parent close, loop termination barrier. A second parameter covers no earlier parent operation.
Invariant table (before production edits):
| Resource | Creator/owner/closer | Terminal/callback/reentry contract | Public test |
| Accepted payload + promise | caller creates; child outbound buffer owns; completion/close/owner shutdown releases | Every accepted entry settles and releases once even if no parent operation exists; callback runs on owner | new UnflushedWriteTerminationTest, earlier-delivery and no-delivery cases |
| Parent outcome | mux creates; child retains pending result until delivery | Completed result applied before termination failure; close callback cannot reinstall ownership | ReviewCompletedShutdownTest; ReviewTerminalWriteConversionTest; ReentrantChildWriteCloseTest |
| Shutdown owner | child installs on autonomous loop; write settlement or close removes | Recheck open/buffer ownership after callback; no hook retained for closed child | ReentrantChildWriteCloseTest |
| Buffer mutation | owner loop only | Foreign parent completion dispatches before buffer mutation; shutdown settles on owner | ReviewChildLoopAffinityTest; OffLoopParentWriteNotificationTest |
| Persistence/restart | none | no persisted state introduced | not applicable |
OBSERVED: Required testing guidance and engineering philosophy read; applicability is public API testing, real local channels, deterministic ordering, exact failure messages, no weakened bounds, no callback ownership after close. No persistence or interface change is planned. The original reviewer checkout /hq/ws/R71tc5 is absent, including its full failed-stream command/XML and probe source; R71tc5-findings.md preserves the exact setup stack and outcome. This limits retrospective diagnosis; no missing record is being replaced with a guessed trace.

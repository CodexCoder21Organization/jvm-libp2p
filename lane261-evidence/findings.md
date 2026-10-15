# Lane 261 review findings

2026-10-15 16:50 UTC — RUNNING. Objective: independently review tests and code for https://github.com/CodexCoder21Organization/jvm-libp2p/pull/50 at f74ff5a0dccefbdfe035ee6a67395acddcd45d2c; fix blocking findings, no merge or publish.

Plan:
- [in progress] Read linked context/docs and repository instructions; clone exact head and confirm develop rebase is a no-op.
- [pending] Map invariants to public tests and verify fail-first behavior by reverting production changes alone.
- [pending] Review callback ordering, buffer ownership, re-entry, and QUIC; add/fix missing coverage or blocking defects.
- [pending] Run new tests with paranoid leak detection and unchanged neighbouring suites; update PR review paragraph; push with lease if changed.
- [pending] Record verdict, invariant table, final head; stop owned processes and remove checkout/cache.

Constraints: no nested agents, no remote builds, Gradle timeout 900 / JAVA_OPTS=-Xmx2g / one build JVM at a time. Stop new work by 17:49 UTC, hard end by 18:04 UTC. Supervisor owns CI and handoff service. Version snapshot-31 is intentionally unchanged in this lane.

## 16:52 UTC — review map / negative run in progress
OBSERVED: exact head checked out; origin/develop rebase is a no-op (base 5c08e2e2). README and FORK read; no AGENTS.md found. PR description already starts with the originating connection-setup failure. Build version remains snapshot-31 as requested. Read common.md, handoff snapshot, r257 findings, Testing's TDD/public API/resource isolation/bug-fix/deterministic reproduction sections, Engineering Philosophy's reproduce/upstream/refutation sections, Code Review red flags. Full documentation copies in /tmp/r261-{TESTING,PHILOSOPHY,CODE_REVIEW}.md.
OBSERVED: all three new test classes reviewed. CoalescedProtocolSelectionTest provides exact active/payload order for both roles and coalesced/bytewise inputs plus reentrant incoming data. CoalescedNegotiationTcpTest covers real TCP and both muxers, exact announcement counts and a usable application stream. CloseNotification covers both roles before activation, close from activation/payload, buffer release, repeated close and pending controller. New tests compiling with ONLY ProtocolSelect restored from origin/develop; paranoid leak detection enabled through external Gradle init script.
INFER: a direct outgoing write from activation callback is not yet asserted; add a small public-API scenario so decoder-removal ordering covers outgoing as well as reentrant incoming data. No production defect established at this point.

Invariant table before edits:
| Resource / invariant | Creator / owner / close | Existing public proof | Review status |
|---|---|---|---|
| Selected stage once-only activation | Binding / event-loop pipeline / channel | CoalescedProtocolSelectionTest both roles/groupings; TCP both muxers | Negative run pending |
| Leftover input exact order and ownership | decoder / handler until forwarded / consumer or release-on-close | exact payload events; refCnt assertions | Extend activation-write scenario with multiple later buffers |
| Close notification exactly once | channel / installed protocol adapter / transport or caller | three CloseNotification scenarios, both roles | Negative run pending |
| No payload after activation-close | same input owner / ProtocolSelect | callback close exact events plus input refCnt | Negative run pending |
| Guard removal / callback reentry | Negotiator + ProtocolSelect / event loop / future settlement | incoming reentry test; close cases | Add outgoing write proof; inspect QUIC shared path |

## 16:56 UTC — fail-first proof checkpoint
OBSERVED: first bounded Gradle exits 1, 18 cases, 10 failures / 8 passing controls, 0 errors/skips, after restoring ONLY ProtocolSelect from origin/develop. Generic coalesced activation fails 2/2 ([active,payload,active]); new outgoing callback test fails 2/2 with the exact selected-protocol reply written twice; real TCP fails 2/2 with repeated selection headers (Mplex count 2, Yamux count 4); close-before-activation fails 2/2 ([] instead of [closed]); activation-close fails 2/2 ([active,payload,closed] instead of [active,closed]). Bytewise and reentrant-read controls pass. Full stack traces are preserved in r261-baseline.log and baseline native XML, copied to evidence/lane261-review-20261015/lane261-evidence/.
OBSERVED: original full-revert baseline lets payload-close cases pass because its earlier guard removal happens before payload; therefore run a second negative experiment with only the close fix removed (ProtocolSelect at 6f79afa8, activation fix retained) to isolate lost unregister forwarding. This is required to attribute each failure to the correct hunk, not a claim of six close failures on develop.
OBSERVED: added outgoing callback scenario to CoalescedProtocolSelectionTest without changing existing scenarios. Expanded pending-controller close test from two to four cases: retain both original pending cases and add both roles with controller completed at activation; renamed to selectedProtocolReceivesCloseAfterActivationExactlyOnce. Existing false/done and true/exceptional checks are now exact parameter-derived equalities, identical for the original two rows; new rows require completed/successful controller to remain successful after close. Added repeated explicit close; all exact callback assertions remain unchanged. These new rows are passing controls for the already-correct guard-removal path, not claimed new bug reproducers.

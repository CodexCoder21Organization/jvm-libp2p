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

# Lane 269 findings
Started 2026-10-15T17:37:55.041885+00:00

Plan:
1. IN PROGRESS: Read instructions, reference documentation, test and evidence; record mechanism before editing.
2. TODO: Run unchanged off-loop scenario 20 times; force its exact read-boundary condition and show old assertion failing.
3. TODO: Correct ordered-record assertion, retain forced-condition coverage and every paused assertion; push checkpoints.
4. TODO: Run fixed off-loop 20 times, class 5 times, mux package once; independent test and code review passes.
5. TODO: Rebase develop, open PR, record SHA and counts, stop own processes and remove checkout/cache.

OBSERVED: Read the complete brief and common.md, plus the shared handoff snapshot. Specific lane rules override global main-branch, full-suite, CI-watching and handoff-writing defaults. Base is develop; no nested agents; no handoff-cli; no publishing or merging; no CI watching.

## 17:44Z evidence reading milestone
OBSERVED: Fresh develop at current fork snapshot; README.md and FORK.md read. No repository AGENTS.md found. Read evidence branches lane246 (findings, fixture, task harness and XML) and lane237 (logging patch). The coalesced-record fixture is a DIFFERENT initialization failure: security negotiation activates twice when plaintext handshake records coalesce. It does not reproduce the payload-stage assumption. The brief conflates those two cases; I will force separate payload transport reads instead.
OBSERVED: Original off-loop test waits for all payload frames and FIN BEFORE resuming the child; first callback blocks its I/O loop, then child pause stops subsequent transport reads. Thus all frames cannot reach the muxer unless they arrived in that first read. Existing final assertion already compares the complete ordered payload list and FIN.
INFER (before editing): The mechanism I am about to fix is a test ordering error: after the first in-flight payload is paused, it waits for frames that may still need a parent TCP read, but parent reads are intentionally disabled until child resume. Evidence is lane246 raw trace (first payload only; later payloads written but unread) and this test's await-before-resume ordering. No evidence of dropped/reordered payloads.
Plan item 1 completed; item 2 in progress. Baseline batch uses 20 separate native Gradle Test tasks, both muxers each, unchanged source. Gradle one worker, in-process Kotlin compilation, JVM cap 2 GiB.

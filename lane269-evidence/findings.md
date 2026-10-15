# Lane 269 findings
Started 2026-10-15T17:37:55.041885+00:00

Plan:
1. IN PROGRESS: Read instructions, reference documentation, test and evidence; record mechanism before editing.
2. TODO: Run unchanged off-loop scenario 20 times; force its exact read-boundary condition and show old assertion failing.
3. TODO: Correct ordered-record assertion, retain forced-condition coverage and every paused assertion; push checkpoints.
4. TODO: Run fixed off-loop 20 times, class 5 times, mux package once; independent test and code review passes.
5. TODO: Rebase develop, open PR, record SHA and counts, stop own processes and remove checkout/cache.

OBSERVED: Read the complete brief and common.md, plus the shared handoff snapshot. Specific lane rules override global main-branch, full-suite, CI-watching and handoff-writing defaults. Base is develop; no nested agents; no handoff-cli; no publishing or merging; no CI watching.

## 17:39Z evidence reading milestone
OBSERVED: Fresh develop at current fork snapshot; README.md and FORK.md read. No repository AGENTS.md found. Read evidence branches lane246 (findings, fixture, task harness and XML) and lane237 (logging patch). The coalesced-record fixture is a DIFFERENT initialization failure: security negotiation activates twice when plaintext handshake records coalesce. It does not reproduce the payload-stage assumption. The brief conflates those two cases; I will force separate payload transport reads instead.
OBSERVED: Original off-loop test waits for all payload frames and FIN BEFORE resuming the child; first callback blocks its I/O loop, then child pause stops subsequent transport reads. Thus all frames cannot reach the muxer unless they arrived in that first read. Existing final assertion already compares the complete ordered payload list and FIN.
INFER (before editing): The mechanism I am about to fix is a test ordering error: after the first in-flight payload is paused, it waits for frames that may still need a parent TCP read, but parent reads are intentionally disabled until child resume. Evidence is lane246 raw trace (first payload only; later payloads written but unread) and this test's await-before-resume ordering. No evidence of dropped/reordered payloads.
Plan item 1 completed; item 2 in progress. Baseline batch uses 20 separate native Gradle Test tasks, both muxers each, unchanged source. Gradle one worker, in-process Kotlin compilation, JVM cap 2 GiB.

## Test resource invariants (before changing concurrency-related fixture code)
| Resource | Creation/ownership/close | Caller-visible contract and interleavings | Existing/new test coverage |
| --- | --- | --- | --- |
| Client/server hosts and TCP parents | Test creates two isolated hosts on port 0; nested finally always stops both. Parent shutdown terminal. No persisted state or restart. | Parent reads stop once queued off-loop child pause runs, restore after child resume. Callback blocks only on a bounded latch, released in finally. | Existing real-NIO assertions and parent-read assertions retained; event-loop barrier proves pause applied before snapshot. |
| Child inbound payloads and remote-end event | Real mux creates child; test callback consumes buffers through SimpleChannelInboundHandler. Parent teardown owns child. | At most in-flight/already-dispatched payload before pause reconciles; no later payload or FIN until resume; exact ordered full list then FIN. | Existing before-resume prefix/end-marker assertions and final equality retained; forced one-byte transport read variant added. |
| Test outbound buffers | Test allocates, transfers to sender write path, keeps current cleanup unchanged. | Sender writes all three distinct messages and FIN; failed writes fail the test. | Existing write-future checks retained. No production ownership change. |
| Synchronization latches/queues | Per invocation, test owns; I/O callbacks publish counts and ordered records. | Test thread never waits for future socket reads while parent reads are intentionally paused. Barrier after releasing callback observes completed pause task. | Move frame-count waits after resume; retain all delivery latches and exact list checks. |

OBSERVED: Reference reading: README.md, FORK.md; common.md and shared handoff; lane246 findings + fixture + trace patch + example XML and lane237 logging patch; PHILOSOPHY.md named reproduction/upstream/refutation sections; TESTING.md TDD, hermetic/public-API/real-dependency rules and full flake-policy/timeout sections; CODE_REVIEW.md relevant test bounds, assertions, artificial load, and work-in-progress file rules. Durable initial analysis: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane269-readpause-20261015 .

## 2026-10-15T17:42:58.543114+00:00 baseline progress
OBSERVED: Initial Gradle invocation remains active and has compiled production Kotlin/Java and test fixtures; unchanged ChildReadPauseTcpTest has not yet run. Source tree remains unchanged at ec74179a589bfb7137122f93906e03a24ca19714. Initial analysis checkpoint pushed as a22bdd6b to the evidence branch.
INFER: Exact forcing mechanism will be FixedRecvByteBufAllocator(1).maxMessagesPerRead(1), installed on the existing TCP parent after handshake completion. A single-byte NIO read that completes the first mux payload cannot contain any byte of a later frame, and the queued pause task runs before the next read. This uses real sockets/decoders with no sleep, load, mock or private API. The permanent parameter matrix will include native and fragmented transport reads for both muxers.

## 2026-10-15T17:47:36.460356+00:00 unchanged baseline milestone
OBSERVED: Unchanged develop ec74179a589bfb7137122f93906e03a24ca19714: 20/20 passed for mplex and 20/20 passed for yamux; zero failures/skips, Gradle exit 0, 8m08s including clean compilation. This does not negate prior lane246's recorded payload-stage failures: that trace forced no read boundary. XML and JSON archived under out/r269-xml/baseline and out/r269-baseline-result.json.
Next: add only the forced transport-read parameters and allocator; leave the old await-before-resume assertion intact, and run 10 repetitions before applying the prepared ordering correction.

# flake-urlprotocol-dial7 findings
Launch: 2026-10-19 01:28 UTC. Wall deadline: 03:08 UTC (100 minutes).

## Plan
- DONE: Resume dial6; fresh fork clone; classify Gossip with 10 fresh-JVM one-attempt runs before and after N1, retaining XML/stacks.
- IN PROGRESS: Gate permits; publish exactly one next snapshot; exact Coursier resolve and SHA-256; draft version-record PR to develop.
- PENDING: Fresh UrlProtocol clone; pin; run exact four-selector batch once; diagnostic sixteen-client workload ten copies; content-targeted dialer sweep (at most three concurrent batches).
- PENDING: If qualification bar passes, draft pin PR to main; audit native CI attempts within 1800 seconds, checkpoint evidence and clean processes.

OBSERVED: Read dial6 findings; PR https://github.com/CodexCoder21Organization/jvm-libp2p/pull/53 is MERGED at ecbc4a354c323f366150165842d6a039dd105128. Prior documentation readings inherited as instructed, no repeat. Kubo red before and after is pre-existing and not a gate under the continuation. Gossip's one pass before and after does not classify its earlier failure.

Decision rule BEFORE runs: 10 fresh JVM runs at pre-merge 031e565a912acd94b5ff5c5a9840131f025654e3 and 10 at merged ecbc4a354c323f366150165842d6a039dd105128, one attempt per run, no retry/no sleeps. Similar counts (including 0–1/10 on both) classify as pre-existing under the owner's ruling; pre-merge 0/10 and merged >=3/10 requires STOP. Other materially ambiguous counts require reporting without guessing. Keep every stack. No assertions, iterations or bounds changed.

The mechanism is process-wide inline Noise admission keeping competing ready work on an event loop; the evidence that demonstrates it is dial3's red/green/reversal and merged N1. This lane only qualifies a publication and pin; no product or existing-test behavior edit is planned. The Gossip rate control is the explicitly authorized experiment, not retrying a failed gate to hide it.

Lane rules take precedence over skill defaults: no handoff writes, challenge CLI, delegation, merges, enqueueing, deploys or other publication. Status progress lives here and evidence branches. One snapshot publication is explicitly authorized if the control permits it.

OBSERVED 2026-10-19T01:33:24.200273+00:00: Gossip before run 1/10: attempt 1 PASSED, 4.272s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-01.

OBSERVED 2026-10-19T01:33:53.731095+00:00: Gossip before run 2/10: attempt 1 PASSED, 3.667s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-02.

OBSERVED 2026-10-19T01:34:19.782578+00:00: Gossip before run 3/10: attempt 1 PASSED, 3.952s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-03.

OBSERVED 2026-10-19T01:34:44.863772+00:00: Gossip before run 4/10: attempt 1 PASSED, 3.682s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-04.

OBSERVED 2026-10-19T01:35:11.195098+00:00: Gossip before run 5/10: attempt 1 PASSED, 3.588s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-05.

OBSERVED 2026-10-19T01:35:39.612275+00:00: Gossip before run 6/10: attempt 1 PASSED, 4.133s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-06.

## 2026-10-19 01:35 UTC — control progress and prepared qualification
OBSERVED: Pre-merge Gossip 5/5 first-attempt PASS so far; each iteration has exactly one native XML testcase, separate Gradle test executor JVM, no retry. The first compile took 3m22s; subsequent Gradle runs take about 25–30s. Lock WAIT/ACQUIRED stamps retained per iteration. Fork source and fixture SHA-256 values are identical between comparison heads. First native attempt and complete preparation evidence checkpoint pushed to the evidence branch.
OBSERVED: Fresh consumer main remains c7d9a191. Inherited exact four-test selector list is confirmed by baseline filter text. Thirteen diagnostic sources saved byte-for-byte; a union of inherited caller inventory and fresh content search identifies 1366 existing neighbour files, excluding the owned original and the thirteen diagnostic copies. It includes ProcessBuilder child-JVM callers; no full-suite command is planned. Snapshot-33 POM returns 404; it is not reserved. No source change or publication yet.
INFER: No rate classification is made from this partial comparison. The inherited fork suite has 1104 native attempts: 1100 PASS, 2 FAILED, 2 SKIPPED; the two failures and full stacks are retained, not erased by targeted passes.

OBSERVED 2026-10-19T01:36:11.041762+00:00: Gossip before run 7/10: attempt 1 PASSED, 5.399s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-07.

OBSERVED 2026-10-19T01:36:36.818176+00:00: Gossip before run 8/10: attempt 1 PASSED, 3.989s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-08.

OBSERVED 2026-10-19T01:37:02.229542+00:00: Gossip before run 9/10: attempt 1 PASSED, 3.702s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-09.

OBSERVED 2026-10-19T01:37:28.504666+00:00: Gossip before run 10/10: attempt 1 PASSED, 3.625s, Gradle exit 0. Native XML and full log retained in evidence/gossip-before-10.

## 2026-10-19 01:40 UTC — baseline complete
OBSERVED: Pre-merge control is 10/10 first-attempt PASS, zero skips/failures/retries. Evidence checkpoint 3ae51f88 is pushed with exact expected lease. Merged control compiling, no verdict yet. Both test files and base fixture remain unchanged; an external Gradle init script only disables the test task up-to-date shortcut, so each requested run executes once in a fresh worker JVM. No new test or product code is written.

OBSERVED 2026-10-19T01:40:48.403944+00:00: Gossip merged run 1/10: attempt 1 PASSED, 4.416s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-01.

OBSERVED 2026-10-19T01:41:14.560639+00:00: Gossip merged run 2/10: attempt 1 PASSED, 3.876s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-02.

OBSERVED 2026-10-19T01:41:40.295999+00:00: Gossip merged run 3/10: attempt 1 PASSED, 3.412s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-03.

OBSERVED 2026-10-19T01:42:05.811219+00:00: Gossip merged run 4/10: attempt 1 PASSED, 3.779s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-04.

OBSERVED 2026-10-19T01:42:33.198601+00:00: Gossip merged run 5/10: attempt 1 PASSED, 3.513s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-05.

OBSERVED 2026-10-19T01:42:58.112277+00:00: Gossip merged run 6/10: attempt 1 PASSED, 3.303s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-06.

OBSERVED 2026-10-19T01:43:23.710993+00:00: Gossip merged run 7/10: attempt 1 PASSED, 3.408s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-07.

OBSERVED 2026-10-19T01:43:48.781152+00:00: Gossip merged run 8/10: attempt 1 PASSED, 3.607s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-08.

OBSERVED 2026-10-19T01:44:13.612897+00:00: Gossip merged run 9/10: attempt 1 PASSED, 3.518s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-09.

OBSERVED 2026-10-19T01:44:40.207047+00:00: Gossip merged run 10/10: attempt 1 PASSED, 4.153s, Gradle exit 0. Native XML and full log retained in evidence/gossip-merged-10.

## 2026-10-19 01:45 UTC — rate control terminal, gate permitted
OBSERVED: All 20 requested native attempts completed: pre-merge 10/10 PASS, merged 10/10 PASS; 0/10 failures at each head, zero retries/skips, no test/source/assertion/bound change. Native XML and full logs retained per run. The external init script forced execution; logs show the test task ran on every iteration. Each Gradle invocation starts a fresh executor JVM. Lock wait/acquire stamps are retained; controls used pinned historical heads intentionally rather than rebasing the historical comparison away.
RULING APPLIED: 0 failures before and 0 after meet the owner's explicit similar-counts rule. Gossip is recorded as a pre-existing fork flake under that rule, not declared fixed. Kubo remains a pre-existing external-daemon requirement. Publish gate is the inherited merged suite (1100 PASS, 2 FAILED, 2 SKIPPED of 1104 native attempts), with both full stacks named, plus these authorized controls and the N1 lane's 92/92 first-attempt Noise-and-neighbour evidence. No fork failure is hidden. Proceeding with one snapshot only.

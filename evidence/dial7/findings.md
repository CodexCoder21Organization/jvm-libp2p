# flake-urlprotocol-dial7 findings
Launch: 2026-10-19 01:28 UTC. Wall deadline: 03:08 UTC (100 minutes).

## Plan
- IN PROGRESS: Resume dial6; fresh fork clone; classify Gossip with 10 fresh-JVM one-attempt runs before and after N1, retaining XML/stacks.
- PENDING: If gate permits, publish exactly one next snapshot; exact Coursier resolve and SHA-256; draft version-record PR to develop.
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

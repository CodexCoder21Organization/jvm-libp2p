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

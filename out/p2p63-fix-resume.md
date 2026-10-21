# Finish review-round verification and update the Yamux pause PR

Written 2026-10-21 22:50 UTC.

**RE-VERIFY:** This is a write-time snapshot. Re-read the open PR at https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63 and fetch origin/develop before continuing. Do not inspect CI as part of this lane; the brief ends immediately after the gated PR push.

## Design decisions in force

The user requires that "the bytes actually held for a paused Yamux child stay bounded by that child's receive window (plus a small constant), regardless of how sibling data was framed." The new test measures payload backing storage; metadata/queue object size is not estimated as payload bytes.

"Rules: no assertion weakened, no timeout raised, no iteration cut." Rebase on origin/develop; preserve the existing-test table and first-line approval notice. No publishing, merging, deployment, CI watching, extra clone or agents is authorized. Push checkpoints only to wip/usage-p2p63-fix-ckpt; push the existing PR branch fix/yamux-per-stream-pause only once, after all requested gates.

## Mission and current state

Complete runtime validation, the post-pass storage revert and resume-on-write controls, full :libp2p:test, spotlessCheck and detekt. Rebase again, rerun targeted tests, then push the existing PR branch once and append the completed Review round 1 evidence. Stop without watching CI or merging.

Candidate source is committed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d933ef4c2a4edc43e5d47b0345014c7dbba9994a. The live PR source remains 986632ab125fd1a2f430e61e7cfe639d78182174: no PR-branch push has occurred. Only its requested first-line approval notice has been posted so far. The checkpoint branch contains the candidate and may have an additional notes-only commit after this source commit.

## What was found and done

YamuxFrameCodec retains slices of a complete input buffer. A paused child's tiny queued payload therefore kept sibling bytes alive after their delivery, while only readable payload bytes counted toward its window. The first actual unchanged-production execution failed deterministically: 100 one-byte paused payloads + 100 delivered 256-byte sibling payloads, framed as 100 real 281-byte buffers, retained 28,100 input backing bytes against a 300-byte window. The strengthened same-stream TCP test passed in that same run (1 pass / 1 assertion failure / 0 skips/errors total).

AbstractMuxHandler now calls preparePendingChildRead only when queuing. Its default is identity. Yamux overrides it with an exact-sized Unpooled.copiedBuffer and releases the input in finally. Direct delivery and Mplex retain their existing path. The storage test also measures queued root capacities on ordered resume, expecting exactly 100 bytes, and verifies input release.

The TCP test pauses the reply reader after the first chunk, sends request byte 2, waits until it passes through the real muxer, and requires no application delivery and unchanged responder pause while the >window reply remains pending. After full reply/write verification it explicitly resumes and checks exact request delivery. Existing reply assertions and 5-second limits remain. README documents the public bound/pause behavior.

The execution ledger is removed from the candidate source commit; it remains available on the wip branch/history and will be restored only in the notes checkpoint, never in the PR candidate.

## Validation evidence and limits

- First build: zero tests, corrected Kotlin nullable-property smart-cast error in the TCP observer.
- Second build: zero tests, 900-second command deadline just after test classes compiled.
- Third baseline: first actual execution, TCP passed and storage failed on the exact 28,100-byte assertion. Full stack is in p2p63-fix-findings.md and baseline-third.log.
- First fixed mux command: main/fixture compilation and spotlessApply succeeded; 900-second deadline during test compilation; zero candidate tests verified.
- Scoped mux command: uses the external targeted-compile.gradle to request compilation of the 20 selected mux sources, without changing repository build files, tests or assertions. See final findings/log for its outcome. The all-source suite must use log.gradle instead.
- Separate source review of tests and code completed, recorded in findings. This is not a runtime pass.
- No full-suite result this round; do not reuse prior-round Kubo/Gossip counts as new evidence. The brief allows those two known failures to recur and be reported separately.

## Relevant refs

| Repo | Branch | Source SHA | PR | State |
| --- | --- | --- | --- | --- |
| CodexCoder21Organization/jvm-libp2p | https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/usage-p2p63-fix-ckpt | d933ef4c2a4edc43e5d47b0345014c7dbba9994a | No PR on wip branch | Candidate implementation, tests, README and cleanup; verification incomplete; notes may be an additional commit |
| CodexCoder21Organization/jvm-libp2p | https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/yamux-per-stream-pause | 986632ab125fd1a2f430e61e7cfe639d78182174 | https://github.com/CodexCoder21Organization/jvm-libp2p/pull/63 | Open, source not updated this round; approval notice applied |

No artifact was published, no deployment or service change was performed, no merge/queue command was issued. Session commands were limited to repository/build work and PR metadata.

## Exact next steps

1. Restore the candidate source commit d933ef4c (do not put notes-only out files in the PR). Read the latest findings/log outcome before repeating a completed check.
2. Through boxrun, run the mux selectors `:libp2p:test --tests 'io.libp2p.mux.*' --tests 'io.libp2p.etc.util.netty.mux.*'`. Use the saved scoped init only for targeted iteration; normal full suite uses the saved logging init. Expected count is the previous 183 plus the new storage scenario; count actual per-test lines/XML rather than assuming.
3. After a real green, temporarily replace Yamux.preparePendingChildRead with identity (`return data`, no release) and add `if (!child.config().isAutoRead) child.config().isAutoRead = true` at the start of Yamux.onChildWrite. Run the storage and large-reply classes together. Storage must fail on retained backing bytes; the TCP test must fail on early request delivery. Then restore YamuxHandler.kt exactly from the candidate commit. No temporary mutation was applied in this session unless the final findings explicitly say otherwise.
4. Run the full :libp2p:test and all-project spotlessCheck/detekt. Record first-attempt counts; report the known Kubo prerequisite and Gossip/PARANOID failures separately if seen. Do not loosen any assertion, timeout, count or default.
5. Fetch/rebase origin/develop, rerun the targeted gate, check the PR is still open, push HEAD to fix/yamux-per-stream-pause once. Keep all original existing-test rows and add the candidate TCP row from the saved body draft. Append actual Review round 1 results, keep first-line notice, end with the required Claude footer. No CI watching.

## Operational notes

All builds used the provided boxrun, --no-daemon, --max-workers=2 and in-process Kotlin compilation. Normal source compilation consumed much of this session. Every deadline was fixed at launch (no existing test limit changed). A single GitHub metadata TLS handshake timeout resolved on one bounded retry. Disk stayed above the 2.5 GiB stop threshold. Do not touch sibling lane checkouts or pre-existing Java processes.

Commit messages must end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. gh pr edit is unavailable here; use `gh api -X PATCH ... -F body=@file`.

## Final attempt and cleanup outcome

The scoped fixed mux command also reached its 600-second deadline in compileTestKotlin and executed no tests. There is no fixed runtime pass. No temporary copy revert or resume-on-write mutation was applied. All recorded build process PIDs were absent in the final `ps -eo pid,args -ww` check. The candidate tree is clean at d933ef4c before notes-only branch changes. The central Handoff service was not written because this brief restricts publishing to its explicit GitHub checkpoints/PR metadata; these notes are preserved only on that authorized branch.

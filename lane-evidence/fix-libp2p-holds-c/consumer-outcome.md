# DONE

OBSERVED: Every brief work item is complete. No merge, queue, approval, ready-state change, resolver publication, deployment, service change or handoff was performed.

| Brief item | State and proof |
| --- | --- |
| Upstream correction | DONE. Native queued-child baseline FIRST 0/4, scoped green FIRST 6/6, reversal FIRST 2/6 (four queued cases fail; two ordinary controls pass), restored FIRST 98/98. |
| Required upstream network register | DONE, FIRST 71/71; new cancellation cases FIRST 4/4; capacity controls FIRST 23/23. spotlessCheck, detekt, jar and POM generation pass. |
| Snapshot 48 | DONE. Published once using CLI 0.0.6 artifact-dir upload. POM HTTP 200 and served jar matches staging. |
| Consumer qualification | DONE. Artifact baseline FIRST 1/2 (public control 1/1, exact count 0/1 expected 3 actual 5); corrected required local FIRST 9/9 plus public control FIRST 1/1. |
| Consumer migration reversal | DONE. Reverse of supplied migration patch with snapshot 48 still pinned: required FIRST 8/9 plus public control FIRST 1/1. The physical duplicate close future stays pending at unchanged test line 276. Restored required FIRST 9/9 plus control FIRST 1/1. |
| Remote register | DONE, native XML FIRST 9/9; official PASSED 9/9, exit 0, one submission. Run https://buildtest.kotlin.build/run?id=b5ff526b . The 3,988 filtered-out XML rows are excluded. |
| PR updates | DONE. Both product branches and descriptions updated; each check set read once after its push. No CI wait. |
| Cleanup | DONE. Zero owned background workers on ps audit. Generated caches, launchers, helper bytecode, jar staging and native build outputs removed. Product diffs have zero build artifacts or evidence paths. |

OBSERVED: Upstream mechanism: cancelling the provisional controller cancelled source.controller but left the owned mux creation future pending, so its queued initializer still installed the protocol. MuxHandler now cancels that creation result, checks controller cancellation before protocol installation, and closes an assigned late child only while creation is pending. Ordinary already-published stream outcomes remain unchanged. Consumer discard paths now explicitly cancel their own provisional controllers before child cleanup and hold release; the selected probe transfers unchanged.

Code: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/a926ff238e86669a8e2eae6ecf5aa52d14fa31a8/libp2p/src/main/kotlin/io/libp2p/mux/MuxHandler.kt#L69 and https://github.com/CodexCoder21Organization/jvm-libp2p/blob/a926ff238e86669a8e2eae6ecf5aa52d14fa31a8/libp2p/src/main/kotlin/io/libp2p/mux/MuxHandler.kt#L83 .

Upstream PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/97
Upstream head: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/a926ff238e86669a8e2eae6ecf5aa52d14fa31a8
Consumer PR: https://github.com/CodexCoder21Organization/UrlResolver/pull/1694
Consumer head: https://github.com/CodexCoder21Organization/UrlResolver/commit/8a76875c6daa14fc56bddb2bc032b69df568a957

Artifact: `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-48`
Jar SHA-256: `fc8ec4faaf8176b734c98055247421800b4ddf545dea6b216ad66c1d53f343d5`
POM: https://kotlin.directory/community/kotlin/libp2p/jvm-libp2p/1.3.0-codexcoder21-snapshot-48/jvm-libp2p-1.3.0-codexcoder21-snapshot-48.pom

OBSERVED: Existing upstream test edit is spotless import/line layout only in the cherry-picked cancellation scenario; conditions, counts and bounds are identical. Consumer existing-main edits are only the snapshot-32 to snapshot-48 pin in the three named ping scenarios. All ten intermediate candidate test pins move 47 to 48; bodies stay unchanged. Complete before/after is test-edits.json. No existing assertion, count, iteration or bound changed.

OBSERVED: Independent review caught the initial broad callback closing ordinary published children. Both unmodified capacity controls were FIRST 0/2 before the narrower correction and FIRST 2/2 afterward. Code Review Red Flags and Engineering Philosophy were read in full; relevant Testing Architecture rules and all ten pin-edited consumer scenarios were read. Reviews and invariants are retained separately.

OBSERVED: Check reads: upstream five entries IN_PROGRESS at its single read; consumer OPEN/draft at the expected head with bld-build IN_PROGRESS at its single read. CI completion is not claimed.

Evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/fix-libp2p-holds-c-evidence/lane-evidence/fix-libp2p-holds-c and https://github.com/CodexCoder21Organization/UrlResolver/tree/wip/fix-libp2p-holds-c-evidence/lane-evidence/fix-libp2p-holds-c .

INFER: Next lane first action: review the two PRs and their required CI results. The implementation and qualification work in this brief are complete; merging and deployment need separate owner instructions.

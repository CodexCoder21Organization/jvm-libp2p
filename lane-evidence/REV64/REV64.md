# REV64 — fresh-context review of https://github.com/CodexCoder21Organization/jvm-libp2p/pull/64 at 00d446b7e5b6c2500147e154a7fea42ae6a8785d

BLOCKING: 0 / SHOULD-FIX: 2 / NIT: 3

Checkout: fresh clone, `pull/64/head` = 00d446b7, origin/develop is an ancestor. Paths below are relative to the repo root.

## Run evidence (OBSERVED)

- Mux family (`io.libp2p.mux.*` + `io.libp2p.etc.util.netty.mux.*`), three separate `./gradlew :libp2p:test --tests ... --rerun --no-daemon --max-workers=2` runs (Gradle has no retry layer; XML timestamps 08:57:33, 08:57:44, 08:57:59 UTC confirm each run re-executed): **211 tests, 0 failures, 0 errors, 0 skips on the first run, and the same in runs 2 and 3.**
- Mutation M1 (mine): comment out `sealPendingChildRead(msg)` in the drain loop (`AbstractMuxHandler.kt:302`), so a merged tail is delivered without being trimmed. Result: 211 tests, 1 failed, `YamuxPausedQueueBoundsTest.sixtyFifthPausedFrameMergesIntoTheNewestCopyAndKeepsTheStreamOpen` (line 111) `expected: 2 but was: 64`. Source restored, `git status` clean.
- Mutation M2 (mine): disable the drain-before-admit branch (`AbstractMuxHandler.kt:197`, `if (false && autoRead && queued != null && !queued.draining)`). Result: **211/211 pass** — see SHOULD-FIX 1. Source restored.
- Mutation controls from the author (always decline merge, trim no-op, unbounded growth) are in the PR body. I did not repeat them.
- `kotlin.build (remote)` on this head reports "no build.kts found", so CI runs none of this Gradle project's tests. The local runs above are the only test evidence.

## Focus 1 — test-expectation changes in commit 00d446b7

| Test | On develop? | Old assertion → new | At least as strict? | Justified? |
| --- | --- | --- | --- | --- |
| `YamuxPausedQueueBoundsTest.coalescedSmallFramesBeyondTheFrameBound...` (256 one-byte frames on the wire, then a sibling frame) | No (added earlier in this PR, `dcb03df3`/`d7475ede`) | `paused.isOpen == false` → `isOpen == true`, `exceptions` empty, held capacity ≤ window, then after resume all 256 bytes in order, stream still open, all copies released | Yes. The behaviour is inverted on purpose, and the new test asserts more (content, order, storage, release). | Yes. The peer stays inside the 300-byte window, and Yamux gives it no way to learn a frame limit. |
| `sixtyFifthPausedFrameResets...WhenMergeIsDeclined` and `overflowReleasesQueuedReads...` | No (added in this PR) | No assertion changed. Now run with a fixture that overrides the protected hook `coalescePendingChildRead` to return false. | Identical assertions. | Acceptable. The overflow path is now unreachable from a Yamux peer (see Focus 2), and the override is a protected open hook, not reflection. These tests now guard a defensive path only. |
| New: `sixtyFifthPausedFrameMergesIntoTheNewestCopy...`, `byteBeyondTheReceiveWindowIsStillRejectedAfterMerging` | No (new) | — | — | Both assert full message text, ordering, capacity and release. Good. |
| `YamuxPausedStreamKeepsConnectionReadingTest.pausedStreamReceivingManySmallFrames...` | No (added in this PR, `364f0e37`) | Old: stream closes within 5 s, writes may fail (`handle{}`), 0 bytes before and after resume. New: every write must succeed (`get(10 s)`, stricter than `handle{}`), 0 bytes before resume, stream open, sibling gets 1,000 bytes in order, then the paused stream gets all 1,000 bytes in order and stays open. | Yes for the new contract. No timeout was raised (10 s already present) and no iteration count was reduced (1,000). | Yes. |
| `PausedYamuxBackingStorageTest`, `YamuxHandlerTest.pausedChildAcceptsManySmallFramesWithinItsWindow` | **Yes, both are develop tests** | Commit 00d446b7 changes only comments. Earlier commits in this PR (`dcb03df3` onward) set a test-only frame limit of 128. | Assertions are identical. See SHOULD-FIX 2 for coverage. | See SHOULD-FIX 2. |

No assertion was weakened. The two develop tests are covered under SHOULD-FIX 2.

## Focus 2 — correctness of the merge path (analysis; INFER unless noted)

- **Ownership and reference counting.** `coalescePendingChildRead` (`YamuxHandler.kt:524-532`) copies `data` into the tail and releases `data` in `finally`, and it returns true only when it took ownership. When it declines, `childRead` releases `msg` (`AbstractMuxHandler.kt:217`). `preparePendingChildRead` releases its input in `finally`. A merged tail is a single ByteBuf with refCnt 1. It is released by the overflow path (`:226-227`), by child close (`:429`), by `channelUnregistered` (`:151-153`), or after delivery by the child. I found no double release and no leak path. The window-violation path releases `msg.data` before `childRead` (`YamuxHandler.kt:159-162`). Tests assert `refCnt()==0` for every copy and input on the merge, window-violation and coalesced paths.
- **Byte order across resume.** A merge appends only to `peekLast()`, the newest entry. When count < 64 a new entry is appended and the old tail is sealed first. Drain is FIFO from the head. Order is preserved, and `containsExactlyElementsOf(0 until N)` asserts it in three tests, one of them over TCP.
- **Window accounting.** `pending.bytes += size` once per payload, merged or not. Drain subtracts `readableBytes` of the merged buffer, which equals the sum of its parts. `onChildReadDelivered` returns credit equal to that sum, once, after delivery. Credit is never returned twice and never lost: a closed child returns no credit, which is pre-existing behaviour. Held bytes ≤ window because credit comes back only after delivery (`handleDataRead` rejects `newWindow < 0`).
- **Held storage ≤ window.** Every non-tail entry has its exact size (copies are created exact, and the old tail is sealed before a newer entry is added). The tail's `maxCapacity` is `window − retainedBytes` at creation. While the tail is the newest entry and count = 64, nothing ahead of it can be drained without the count dropping below 64, and then the next frame creates a new tail. So capacity sum ≤ window at rest. For the same reason a merge cannot be declined while `withinBytes` holds, so the overflow reset is unreachable for a Yamux peer, as the author claimed.
- **Resume from another thread mid-merge.** All queue mutation, merging, sealing and draining run on the parent event loop. An off-loop `setAutoRead(true)` only flips the flag and posts `childAutoReadChanged`. If the flag flips during a merge, the post-merge `if (child.config().isAutoRead) drainPendingInbound(child)` (`:237`) drains in order. The posted task later finds an empty queue. A re-entrant `childRead` during a drain sees `draining == true` and appends to the queue, which is drained by the same loop. I found no defect here.

## SHOULD-FIX

### 1. The drain-before-admit loop no longer has a discriminating test — `AbstractMuxHandler.kt:192-205`; tests `YamuxResumeAtFrameBound{,RePause,Close}TcpTest`

OBSERVED (M2): replacing the branch condition with `false` leaves all 211 mux-family tests green. The three TCP tests were written (commit `7f10658d`, red on `1d31d81c`) to catch exactly this regression: frame 65 reaching Yamux after an off-loop resume but before the scheduled drain. Under merging, frame 65 is merged into the tail and then drained by the post-admit `if (isAutoRead) drain`, so the bytes still arrive complete and in order and the tests pass either way.

The loop still matters wherever merging declines, which is **Mplex** in production (frame limit 64, reset). Concrete scenario: an Mplex child is paused holding 64 frames. The application resumes it from a non-event-loop thread. Frame 65 is already decoded in the pipeline and reaches `childRead` before the posted drain runs. Without the loop the child is reset and its 64 held payloads are discarded although it was readable. No test fails if the loop is removed, so it can regress silently. README ("Resuming from another thread drains held payloads in order before admitting the next frame") documents behaviour that no test pins.

Correct fix: add a fail-first test that drives this race where merging declines. For example, an Mplex TCP variant of `YamuxResumeAtFrameBoundTcpTest`, or the Yamux variant run with the `mergeAtFrameBound = false` fixture style. Confirm it fails under M2. The callback re-pause and callback close variants need the same treatment.

### 2. Two develop tests now run only at a non-default frame limit; the PR body says "No develop test changed" — `YamuxHandlerTest.kt:35,60-61,345`, `PausedYamuxBackingStorageTest.kt:16`

OBSERVED: on develop `YamuxHandler.maxPendingChildReadFrames = null` (no frame bound). `pausedChildAcceptsManySmallFramesWithinItsWindow` (65 frames, `hasSize(65)`) and `pausedPayloadDoesNotRetainDeliveredSiblingStorage` (100 paused bytes sharing inputs with sibling frames, `List(100){"2a"}`, delivered storage exactly 100) therefore pinned **production** per-frame delivery. This PR adds `pendingReadFrameLimit = 128` to both, so they no longer run the production configuration. Under the PR's production default they would see 64 reads instead.

The assertions are unchanged, so this is not an assertion weakening. The granularity change itself is deliberate and documented in README. The cost is coverage: the shared-input backing-storage scenario (paused one-byte payloads sliced out of inputs that also carry 256-byte sibling frames) is no longer exercised with merging active. In particular, nothing checks that a merged tail's delivered storage equals its readable bytes in that scenario. The new `coalescedSmallFrames...` test checks held capacity ≤ window but not delivered capacity.

The PR body's line "No develop test changed: every develop test holds fewer than 64 paused payloads, or uses the existing explicit fixture allowance" is inaccurate. The allowance is not existing; this PR added it to two develop tests. An earlier paragraph of the body does disclose it.

Fix: keep the 128-limit runs, and add production-default variants of both scenarios. Assert the same bytes in order, 64 reads, and (for the backing-storage scenario) total delivered storage == 100 and every input released. Also correct that PR-body sentence.

## NIT

3. `AbstractMuxHandler.kt:102` — the protected open hook `preparePendingChildRead(data)` changes its signature to `(data, retainedBytes)` on public open classes (`YamuxHandler`, `MuxHandler`). Any external subclass that overrides it stops compiling. FORK.md does not mention this. It is a fork-only hook, so the impact is likely nil; one line in FORK.md would cover it.
4. `YamuxHandler.kt:534-537` and Netty growth in `coalescePendingChildRead`: "held storage stays within the window" holds at rest. During `ensureWritable` growth and during the seal trim (`capacity(writerIndex)` allocates and copies), the old and new arrays coexist, so the transient peak per stream is up to about window + tail. This is bounded and not a defect. The README/PR wording could say "at rest", or leave it.
5. Only one assertion (`YamuxPausedQueueBoundsTest.kt:111`) catches a missing trim on the delivery path (M1). The TCP test `YamuxPausedManySmallFramesTcpTest` asserts read count but not delivered capacity. A capacity assertion there would make the exact-size contract hold under real TCP too.

## README / FORK.md accuracy

- README table and paragraph: accurate. They state at most 64 copies, appending at 64, fewer and larger reads with bytes and order unchanged, window violation closes the connection, and Mplex resets (`MplexHandler.onLocalClose` writes a RESET frame, so "reset" is correct). The "Resuming from another thread..." sentence is accurate, but no test covers it (SHOULD-FIX 1).
- FORK.md: accurate. It could mention the hook-signature change (NIT 3).

Evidence: /tmp/claude-501/-code/2abf611d-5e04-4919-84a8-d7b72869d40c/scratchpad/out/REV64-ev (run logs, xml1..3, mutation1 failing XML, mutation2-xml), mirrored to branch wip/REV64-evidence-20261022.

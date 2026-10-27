# Deterministic Yamux paused-read proof

## Plan

- [x] Confirm PR77 worktree is clean/pushed; create isolated proof worktree at current `origin/develop`.
- [ ] Inspect the three pause/settle call sites and implement the forced real-read ordering in the existing closing test only.
- [ ] Add a disposable branch-targeted Actions workflow and exclude that proof branch from full build workflow.
- [ ] Rebase proof branch on current `origin/develop`, push it, run only `closingAPausedStreamWithQueuedDataKeepsTheConnectionReading`, and verify expected assertion-level `0` versus `1` XML (not compile/setup failure).
- [ ] Save full log/JUnit XML, final diff and commit; report proof URL/result to parent. No production fix and no further test runs.

## Starting state

- Product PR77 was already pushed clean at `0e7c69f436bbecce1e4aa158c103f8410dae4f53`; it is in a separate worktree and will not be touched.
- Fresh worktree `../jvm-libp2p-yamux-proof` is branch `proof/w2-yamux-pause` from freshly fetched `origin/develop` `25efb1996bd79e59e6eef28d2d44d9a0405fdd78`.
- No local test/build JVM will be run.

## Progress (22:47 UTC)

- OBSERVED: Read the repository README and local `AGENTS.md` where present, plus `/tmp/w2-fix213755/{TESTING,PHILOSOPHY,CODE_REVIEW}.md`; the proof uses the existing public host/TCP/Yamux route and keeps the original assertion/count bounds.
- OBSERVED: In the test, the reader callback increments the responder byte counter before the blocked automatic `read()` can resume. The proof sends one real primer byte, waits for that exact read gate, sets `autoRead=false`, releases the blocked read, waits for the event loop to settle, and then clears only the primer from the test recorder before the original pressure frames.
- OBSERVED: `git diff --check` passed. Proof commit `c29a36adf73af5f818ee07fa17ce8f939ef5d019` is based on `origin/develop` `25efb1996bd79e59e6eef28d2d44d9a0405fdd78`, pushed to `proof/w2-yamux-pause`. Targeted workflow: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40597356309 (queued at 22:46:35 UTC when checked).
- INFER: With the old helper semantics, the read already past the automatic-read check is released only after the off-loop `autoRead=false` write; it should enter the mux read-pending path and surface one of the subsequent 64 pressure bytes, causing the exact expected assertion failure. This remains unproven until XML/log validation.

## Read documents

- Repository `README.md`, `AGENTS.md` if present, `FORK.md`, `CLAUDE.md` if present.
- `/tmp/w2-fix213755/TESTING.md`, `/tmp/w2-fix213755/PHILOSOPHY.md`, `/tmp/w2-fix213755/CODE_REVIEW.md`.

## Result (22:50 UTC)

- OBSERVED: First pushed proof attempt ran only `closingAPausedStreamWithQueuedDataKeepsTheConnectionReading` on GitHub Actions under Temurin JDK 11; no compile or setup failure occurred. The JUnit XML contains one test, `tests=1`, `failures=1`, `errors=0`, `skipped=0`, runtime 1.232 s. The sole failure is at the unchanged pressure assertion: `Pressure-state payloads must remain held before close ==> expected: <0> but was: <1>`.
- OBSERVED: The workflow's separate XML verifier passed and printed `EXPECTED RED: ... pressure-state count is 1, expected 0`; the targeted Gradle step and overall workflow are red by design. Full Actions log is saved at `/tmp/w2-fix213755/yamux-pause-proof-40597356309/actions-full.log`; downloaded JUnit XML is at `/tmp/w2-fix213755/yamux-pause-proof-40597356309/yamux-pause-proof-c29a36adf73af5f818ee07fa17ce8f939ef5d019/TEST-io.libp2p.mux.yamux.YamuxPausedStreamKeepsConnectionReadingTest.xml`; Actions artifact: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40597356309/artifacts/12776263145.
- OBSERVED: The complete proof source and workflow are pushed at commit `c29a36adf73af5f818ee07fa17ce8f939ef5d019` on `proof/w2-yamux-pause`; the checkout is clean. No production source was changed and no local JVM or full suite was run.
- INFER: The forced in-flight automatic-read ordering deterministically exposes exactly one payload byte after the stream is paused, matching the suspected read-pending credit path. This experiment establishes the test-side regression signal; parent owns the decision and any product repair.
- The neighboring test gap search is limited by this bounded assignment: this reproduction stays in the existing closing test and its source changes are restricted to factoring the shared pause/settle helper plus the deterministic gate. The other two call sites use the helper with unchanged semantics. No production fix or green rerun was attempted per instruction.

## Task checklist

- [x] Re-read the project guidance and linked testing/philosophy/review documents listed above.
- [x] Force the real in-flight automatic read, retain the original 64-frame test and pressure assertion, and use the original test method.
- [x] Add a branch-specific disposable workflow and keep it out of the full push build.
- [x] Push the proof branch and run the one named test on GitHub Actions/JDK 11.
- [x] Validate assertion-level red from the XML and preserve log/XML evidence.
- [x] Keep product PR77 checkout clean and do not change production code.

Completed the full-suite comparison and updated the [draft PR](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/70). Its table covers all 17 failures on both versions. It distinguishes isolated results from real occupied-port controls.

- Full suite: **1,286 tests — 1,267 passed, 17 failed, 2 existing skips**. All 81 focused cases passed.
- All 17 failures reproduced on develop, either alone or with the same occupied-port condition. **No PR-specific failure identified.**

Actions still reports startup_failure with zero jobs; the Kotlin checks ran no Gradle tests. The PR was verified open and draft at 07:27 UTC; a later recheck hit the shared API rate limit.

[Findings and evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/JL62b-evidence-20261023/lane-evidence) are pushed. No implementation changes, delegates or owned background jobs remain. Next steps: none within the requested verification.

Lessons: keep lock waits separate from execution budgets, verify actual test starts, and reproduce the full-run resource condition when isolated tests pass.

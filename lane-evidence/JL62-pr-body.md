During the 2026-10-22 test campaign, the owner asked: "Your goal is to add end-to-end integration tests which identify bugs (the tests should initially fail), and then fix the bug in the implementation." Moving UrlProtocol to snapshot-38 in https://github.com/CodexCoder21Organization/UrlProtocol/pull/846 left `testGossipServiceQueryResponseFlushesUnderBackpressure` failing at its unchanged 30-second limit because `read()` on a paused child did not deliver queued data. This fixes the upstream behavior described in https://github.com/CodexCoder21Organization/jvm-libp2p/issues/62.

An explicit read now delivers one queued payload and read-complete while auto-read remains disabled, or waits for the next payload. Pending requests coalesce. Callback reads continue without nested callbacks, and Mplex re-arms its paused transport after a resume callback requests more data. The child config handles automatic resume separately so a delayed automatic read cannot be mistaken for explicit demand. Existing receive limits, receive-credit accounting, EOF order and close cleanup remain in effect.

Validation:
- Real TCP/Noise reproduction failed for both Mplex and Yamux before the fix. All 11 initial new cases failed their first runtime baseline attempt.
- Review added an empty-queue callback case: Mplex initially failed because the second physical payload never arrived; the correction re-arms after clearing the drain guard.
- All 13 new cases plus 68 neighbour cases passed on the first attempt in three fresh runs: 81/81 each. Coverage includes 8,000 concurrent pre-arrival reads, one-byte transport reads, EOF, callback close, receive bounds, backing storage, sibling progress and connection teardown.
- Formatting, static analysis and library assembly pass. The formatter also updates the newly merged capacity callback test's wrapping and branch braces; its conditions and assertions are preserved.
- Full root-suite acquisition attempt waited 90 minutes on `/tmp/claude-501/complete-suite.lock` and exited before Gradle started. The fresh-XML audit confirms zero new test reports; the old 81-case XML is not a full-suite result. The full suite and comparison with the known 20 baseline failures remain unverified; this PR is draft for that reason.
- Watch-only build-watchman reports Kotlin suite `108121241955` queued with zero dispatched runs, over 90 minutes old, while a sampled develop suite did dispatch. No build log or code verdict exists. Actions has startup failure and zero jobs under the existing local-only policy. Receiver warming and rerequesting are prohibited by this lane; neither was performed. CI is not green.

Independent test and implementation review: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/JL62-evidence-20261023/lane-evidence/JL62-review.md . Baseline failures, full stacks, run summaries and findings: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/JL62-evidence-20261023/lane-evidence . No timeout or iteration changes, mocks, reflection, merge, publication or deployment.

Closes https://github.com/CodexCoder21Organization/jvm-libp2p/issues/62

🤖 Generated with [Claude Code](https://claude.com/claude-code)

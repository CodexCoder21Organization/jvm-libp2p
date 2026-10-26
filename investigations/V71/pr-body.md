The owner reported that https://droplets.wasmserver.com/ "is super unreliable, frequently rendering various errors/notices due to bugs in UrlResolver or UrlProtocol or something else" and asked for the underlying root cause to be fixed; the ContainerNursery route stops the WUI after 5 idle minutes, so most human visits are cold starts.

The consuming UrlProtocol scenario exposed a completed channel-close result whose converted Java future remained pending when listener delivery was unavailable. Netty already held the terminal result, but conversion depended only on a listener being delivered.

This branch observes terminal Netty results before and after listener registration, copying success, the original error, and cancellation into the Java result. It also preserves completed stream-write and connection-close outcomes while keeping child-write ownership and close-callback ordering explicit. Already-completed conversion can finish inline, as documented in the README; pending operations use normal listener delivery.

Verification at product head `587aad5f1c7fda72a561f0f7bb09dfd8f63b8939`:

- Complete native Gradle suite: **1,437 passed, 5 failed, 2 skipped** (1,444 cases, 204 native JUnit XML files; Gradle exit 1, 17m 48s). The complete suite is not green. Native test retry configuration and XML rerun records were absent.
- Base comparison: Controls used exact base `395e0c72b69a8b3429e979586e06fe9c84507cc7` and the unchanged PR product sources.

| Native case | Isolated base result | Isolated PR-head result | Classification |
| --- | --- | --- | --- |
| WebSocket initial connection setup | 28/28 class cases passed | 28/28 passed | Not reproduced in isolation on either commit; cause remains unassigned. |
| Message larger than a multiplexer frame | 1/1 passed | 1/1 passed | Not reproduced in isolation on either commit; cause remains unassigned. |
| TCP initial connection setup | 24/24 class cases passed | 24/24 passed | Not reproduced in isolation on either commit; cause remains unassigned. |
| Kubo connection setup | Matching case failed with ConnectException at QuicKuboTestJava.java:65 | Native full-run case failed at the same frame | Reproduced on base. |
| Established connection with another incoming handshake held | 18 passed, 2 failed across 20 repetitions; same wait and assertion frames as native | Native full-run repetition 10 failed | Same failure pattern reproduced on base; test source is identical. |

The three isolated passes do not establish whether the full-suite timing cases come from this PR. No product change is claimed to fix them. Native assertions, time limits, and iteration counts were unchanged in V71.

Prior checks: references below are from F71; the complete native and paired-control results above are from V71.
- Prior F71 focused native checks: 31/31. Reversal control with the base conversion: 10/20 conversion checks red.
- Prior F71 UrlProtocol target scenario: 10/10 on the first attempt, prepared on https://github.com/CodexCoder21Organization/UrlProtocol/tree/wip/F71-consumer-pin .
- V71 fresh-context correctness and coverage review: no demonstrated additional retention, result-ownership, or ordering issue. Review evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/V71-suite-review/investigations/V71/review.md .

V71 verification details and complete-suite setup corrections: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/V71-suite-review/investigations/V71/progress.md . No merge, queue entry, publication, or deployment was performed.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

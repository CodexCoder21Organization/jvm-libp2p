During the 2026-10-22 extreme-condition test campaign, moving UrlProtocol to [jvm-libp2p snapshot-38](https://github.com/CodexCoder21Organization/UrlProtocol/pull/846) left five UrlProtocol tests failing because the connection API could not request a new physical close after an earlier attempt failed. This follows the owner's direction: "Then, do another round, Your goal is to add end-to-end integration tests which identify bugs (the tests should initially fail), and then fix the bug in the implementation." The missing operation belongs in jvm-libp2p, as described in https://github.com/CodexCoder21Organization/jvm-libp2p/issues/61.

`Connection.retryClose()` explicitly requests another attempt after a settled failure. `ConnectionOverNetty.close()` continues to report its original shared result. Explicit callers share the latest pending attempt, each settled failure permits another explicit call, and a successful attempt stays terminal. Derived futures keep caller completion and cancellation separate from the owned attempt. Existing stream-backed relay connections inherit delegation to their stream close operation.

The README and API KDoc describe these behaviors. Eleven real-connection tests cover TCP/Noise/Yamux and three-host relay operation, 8,192 concurrent retry observations, 1,000 consecutive injected close failures, failure-callback and handler re-entry, transport-owned failures, peer closure, success boundaries, and caller future changes.

Validation:

- Before the fix, nine recovery tests failed at runtime on their first attempt against the old close helper. The initial-success boundary and inherited relay behavior passed as expected.
- Three fresh targeted/neighbour runs: 23/23 tests passed on the first attempt each time, with zero failures, errors, or skips. The final relay ordinary-close assertion also passed three fresh first-attempt runs.
- Build (`:libp2p:assemble`), formatting (`:libp2p:spotlessCheck`), and static analysis (`:libp2p:detekt`) passed.
- Full repository suite under the machine-wide lock: 1,225 tests, 1,203 passed, 20 failed, and 2 already-disabled tests. All 11 new tests passed on the first attempt. A fresh unchanged `develop` checkout reproduced the exact same 20 failures in the eight affected existing classes (29 tests selected). Native TLS initialization fails, the first failed host test leaves fixed port 40002 occupied and causes later bind failures, and the Kubo test expects an external service on port 5001. Full stacks and the base comparison are linked below. No test was disabled or weakened by this change.
- Independent test and code review: all identified coverage and cleanup gaps addressed; no remaining concrete findings.

The existing repository Actions policy is `allowed_actions=local_only`, while the inherited workflow uses external Actions, including `actions/checkout`. The PR's [build workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39868555272) ended with `startup_failure` and created zero jobs, matching existing `develop` failures. The two informational Kotlin checks passed. The expected Actions jobs have not run or been verified green.

[Findings, baseline stacks, test results, and review evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/JL61-evidence-20261022/lane-evidence).

[Remaining verification and recovery instructions](https://www.handoff.wasmserver.com/handoffs/hf-2026-10-22-restore-required-verification-for-jvm-libp2p-explicit-close-retry).

Fixes https://github.com/CodexCoder21Organization/jvm-libp2p/issues/61

🤖 Generated with [Claude Code](https://claude.com/claude-code)

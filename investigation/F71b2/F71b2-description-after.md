`testRejectedDrainLoggingDoesNotHoldOutboxMonitor` timed out during host stop in the required full-suite check for https://github.com/CodexCoder21Organization/UrlProtocol/pull/635. The first failure is recorded at https://github.com/CodexCoder21Organization/UrlProtocol/actions/runs/40085745929/job/120848195951; the owner requested local reproduction and a fix at its source. This work is part of the owner's request to finish open handoffs; jvm-libp2p is consumed by UrlProtocol.

Handoff: https://www.handoff.wasmserver.com/handoffs/hf-2026-09-24-make-kompile-cli-remote-runs-reliable-resume-chunked-uploads-from-the-failed-chunk-land-the-reconnect-fix-and-deploy-the-server-cpu-fix

A completed Netty promise could lose its conversion callback when its live event-loop queue rejected listener dispatch. The returned Java future then stayed pending after the queue recovered. Both converter overloads copy terminal results directly and recheck completion after listener registration; pending operations continue to use their normal listener executor.

The notification-site sweep also covers completed connection closes and stream writes. External completed closes no longer require accepted notification dispatch, while reentrant close ordering on the owning loop stays intact. Stream writes use the corrected converter. When a parent-write listener cannot enqueue its child completion on a full owning loop, it finishes that completion on the same loop; ordinary accepted deferral is preserved. Tests check success, exact original failure and payload release through both muxers.

Review round record (reviewed head `8eabea8649ac5bc301615dbea6a5f06ac8c477bc`):

- [Adversarial review](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71adv-final.md) ([full findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71adv-findings.md)): completed-close finding addressed by [the close fix](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ecafb3ab4b321b1b24be6c26b0c39356865f87e0) and [reentrant ordering guard](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/6bdb29896ff411d1beca2c434e77b742223e557a). Its two documentation findings are answered by [the coordinate correction](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6) and this description.
- [Coverage review](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71tc-final.md) ([full findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-R71tc-findings.md)): [four pending failure/cancellation cases](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/5a9a8550a6c8772f2c63f7139393ddfaf7fde5a5) now fail under the exact success-only listener mutation, then pass with the original converter restored. They check terminal state and original cause after real executor barriers.
- This resumed round: [stream-write sweep fix and six guards](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d6eca64f92fc1d1cc4d44b39a188afc98ae0abec), plus registration-race, queued shutdown, nullable generic success and caller-ownership cases. Separate local test-comprehensiveness and code-review passes are recorded in [the findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-findings.md); no new external review approval is claimed.

Registration audit: 23 original notification sites; 21 existing converter/loop-owned guards and two corrected stream-write sites. [Site sweep](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-site-sweep.md), [converter state sweep](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-sweep.md).

Local controls:

- Close regression: original assertion-red 9608 ms; fixed 8359 ms; fix reversal red 9865 ms. This resumed lane also confirmed the original pushed head: close 1/1 (9522 ms), converters 6/6 (2880 ms).
- `stream-red`: 0/6 pass, 6 assertion/error results, 61338 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-red.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-red.log).
- `stream-green`: 6/6 pass, 0 assertion/error results, 81915 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-green.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-stream-green.log).
- `pending-mutation`: 0/4 pass, 4 assertion/error results, 7019 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-mutation.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-mutation.log).
- `pending-green`: 4/4 pass, 0 assertion/error results, 5961 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-green.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-pending-green.log).
- `state-mutation`: 10/14 pass, 4 assertion/error results, 9392 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-mutation.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-mutation.log).
- `state-green`: 14/14 pass, 0 assertion/error results, 9382 ms. [XML](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-green.xml), [full log](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-state-green.log).

First runtime attempt per class at final source head `2a3b9e2f6e93e8c35e0897f3a6b8e1cc2d2adfc6` (one class per bounded Gradle command; XML counts):

| Class | Pass / total | Fail / skip | XML ms |
|---|---:|---:|---:|
| BufferedHalfCloseFramesTest | 14/14 | 0/0 | 3655 |
| CompletedConnectionCloseNotificationTest | 1/1 | 0/0 | 3921 |
| CompletedNettyFutureConversionTest | 6/6 | 0/0 | 2340 |
| CompletedStreamWriteNotificationTest | 6/6 | 0/0 | 37054 |
| NettyFutureConversionStateTest | 14/14 | 0/0 | 1881 |
| NetworkPendingConnectTest | 11/11 | 0/0 | 3337 |
| NoiseBindingReadinessTest | 3/3 | 0/0 | 50733 |
| NoiseHandshakeTest | 6/6 | 0/0 | 2759 |
| NoisePreparationConcurrencyTest | 1/1 | 0/0 | 10190 |
| NoisePreparationFailureTest | 3/3 | 0/0 | 25832 |
| NoisePreparationLifecycleTest | 5/5 | 0/0 | 62652 |
| NoisePreparationTrafficTest | 4/4 | 0/0 | 37047 |
| PendingNettyFutureConversionTest | 4/4 | 0/0 | 2645 |
| ReentrantConnectionCloseNotificationTest | 1/1 | 0/0 | 2583 |
| YamuxPrefixFrameFailureTest | 1/1 | 0/0 | 1532 |

Both initial close and converter commands hit their unchanged 600-second outer limits during test-source compilation, before any test body or XML. Those first-command failures are retained, including their full daemon traces; they are not counted as passing runtime attempts. A separate bounded offline `compileTestKotlin` then succeeded in 7m14s. No failed test was rerun unchanged into green; the deliberately failing mutation/control runs above are recorded separately from restored-head validation.

[All command, XML and full failure records](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F71b2-20261024/investigation/F71b2). [Initial close daemon trace](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-initial-close-daemon.log); [initial converter daemon trace](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F71b2-20261024/investigation/F71b2/F71b2-initial-converter-daemon.log).

GitHub Actions does not run tests for this fork: enterprise policy blocks its external actions, so the verification evidence is local. No workflow was rerun, requested or watched.

No existing test files were edited: all test-file changes relative to `develop` are additions. No test timeout or iteration count was changed, and no test or assertion was weakened, skipped or deleted. No reflection or mocks were added.

`build.gradle.kts` assigns `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-41`; `FORK.md` now agrees and keeps assignment separate from publication. This lane did not merge, enqueue, publish or deploy.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

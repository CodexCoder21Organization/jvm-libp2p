# Native Actions audit for jvm-libp2p

Head: [`34a2e78bb82637e2c970a0be40fe4244c76cb547`](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/34a2e78bb82637e2c970a0be40fe4244c76cb547) on [`develop` PR](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/93). Checkout `workspace/jvm-libp2p` is at that exact commit and clean. All three run records below name the same head and show `completed/success`, attempt 1.

## OBSERVED — runs and artifacts

| Actions run | Event | Artifact | Archive bytes | SHA-256 |
|---|---|---|---:|---|
| [43563295235](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/43563295235) | pull request, `build.yml` caller | [14100764932](https://api.github.com/repos/CodexCoder21Organization/jvm-libp2p/actions/artifacts/14100764932/zip) | 2,799,450 | `abcfe7da01a1d4c65ea1d4dc1b0eee8b2ed7fa44c281c92c12354a57d8a1e9dc` |
| [43563295328](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/43563295328) | pull request, reusable build workflow | [14101986723](https://api.github.com/repos/CodexCoder21Organization/jvm-libp2p/actions/artifacts/14101986723/zip) | 2,790,935 | `7c7892bda1d6f091eb51ed3eb9de6a6d971979ba5d7c11fdf9a70f17609b10c3` |
| [43563290814](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/43563290814) | push, reusable build workflow | [14101144011](https://api.github.com/repos/CodexCoder21Organization/jvm-libp2p/actions/artifacts/14101144011/zip) | 2,792,535 | `12fa639a635574870ece4fcae29581cfded544f44f9e44a158a46d401733d0bf` |

All three artifacts have the same per-source, per-path, per-case outcome multiset.

## OBSERVED — testcase counts

The three artifacts each contain the following native test-result XML:

| Result source | XML files | Testcase records | Passed | Skipped | Failed/errors |
|---|---:|---:|---:|---:|---:|
| Gradle `**/build/test-results/**` | 279 | 1,682 | 1,680 | 2 | 0 |
| Download verification | 1 | 17 | 17 | 0 | 0 |
| Native port-isolation checks | 5 | 12 | 12 | 0 | 0 |
| Kubo workflow checks | 2 | 4 | 4 | 0 | 0 |
| **Total test-result XML** | **287** | **1,715** | **1,713** | **2** | **0** |

The only skipped cases in each artifact are `io.libp2p.security.secio.EchoSampleTest::connect1()` and `io.libp2p.transport.quic.QuicServerTestJava::checkConnectionIsNotClosedByTimeout()`.

All 35 test files changed by the PR are represented in the Gradle test XML: 60 testcase records across those classes, all 60 passed in every run. The artifacts contain 1,611 distinct `classname::testcase-name` strings. There are 38 result-file/full-name groups with repeated XML entries; the per-name inventory preserves those record counts rather than treating them as retries.

No Gradle retry plugin or retry setting was found in the checked Gradle/workflow configuration. The build workflow runs `./gradlew --no-daemon --stacktrace --continue build dokkaJar`; the `test` task selects JUnit Platform and excludes the `interop` tag. The result XML has zero `flakyFailure`, `flakyError`, `rerunFailure`, or `rerunError` entries in every artifact. Thus every one of the 1,715 testcase records is a first-attempt record, with zero recorded retries. Sources: [caller workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/34a2e78bb82637e2c970a0be40fe4244c76cb547/.github/workflows/build.yml), [reusable workflow and artifact paths](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/34a2e78bb82637e2c970a0be40fe4244c76cb547/.github/workflows/bld-build.yml#L55-L78), [Gradle test task](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/34a2e78bb82637e2c970a0be40fe4244c76cb547/build.gradle#L188-L204).

The full per-case counts are in [`case-counts.csv`](case-counts.csv), per-run/source totals in [`run-summary.csv`](run-summary.csv), and changed-file coverage in [`pr-touched-tests.csv`](pr-touched-tests.csv). These contain testcase names and outcome counts only; test output, failure text, stack traces, and properties were not retained. Each bundle included `_temp/bld-kubo.log`, which was not extracted or read; no `.properties` file was present. XML `<system-out>` contents were ignored. Temporary ZIP archives were deleted after counting.

## OBSERVED — earlier connection-setup result

The saved [F93A findings](../../F93k/F93A-findings.md#L47-L51) record an older run at [42635115929](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42635115929): one failure in `NoiseHandshakeOffEventLoopTest` repetition 9 of 10, described there as queued sequential connection setup; 1,669 of 1,672 cases passed, with two skips. The recorded exact-develop control at [42609651345](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42609651345) had 49/50 cases pass in the relevant class versus 50/50 on the older PR head. F93A says that result remained unresolved and that the TCP setup path does not execute the UDP release wait. I read that saved F93A report; I did not rerun those tests or independently redownload the older artifacts.

## INFER

The three green runs prove the current native suite passed on this head with the two listed skips. They do not resolve the earlier TCP connection-setup failure: that path is separate from the UDP release change, and a green suite alone does not identify why the earlier single failure happened. Keep that historical issue classified as unresolved, not fixed.

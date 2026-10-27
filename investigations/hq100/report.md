# Verification restoration report

Work item: url://handoff/handoffs/hf-2026-10-22-restore-required-verification-for-jvm-libp2p-explicit-close-retry.

The original retry change and reviewer regression are already merged at https://github.com/CodexCoder21Organization/jvm-libp2p/pull/67. Current develop remains 25efb1996bd79e59e6eef28d2d44d9a0405fdd78. This lane changes verification and fixtures, with one mechanical production catch-name/comment correction; it does not change the retry implementation. It has not merged, enqueued, deployed, published, restarted anything or changed rules.

## Open changes

- Native full verification: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75, head bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672.
- Owned real Kubo prerequisite: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/76, head f5b8661280752dd60b5408530229144d3d013fc5.
- Noise listener separation: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/77, head 0737c7820c2159f2360ff7bc18b260de37544c7a.

All three include the complete native Gradle workflow and the separate mechanical Detekt / peer-publication fixture corrections. Verification-only workflows are confined to other fix/ branches and must not be merged. No PR-ready claim is made while full checks remain red or pending.

The updated CI-only full Linux run is green at https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40528568980: 1445 tests, 1443 passed, zero failures/errors, two existing skips. Both retirement cases pass. Complete Gradle build and documentation succeeded. This does not establish a fix for the earlier intermittent Metaspace failure. Windows remains pending at this checkpoint.

## First full results

| Source | Platform | Total | Passed | Failed | Errors | Existing skips |
|---|---|---:|---:|---:|---:|---:|
| CI-only, unchanged library/tests, https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40521828270 | Linux | 1444 | 1441 | 1 | 0 | 2 |
| Same CI-only run | Windows | 1444 | 1434 | 8 | 0 | 2 |
| Owned Kubo initial candidate, https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40523405129 | Windows | 1444 | 1434 | 8 | 0 | 2 |
| Noise initial candidate, https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40526228042 | Linux | 1445 | 1442 | 1 | 0 | 2 |

CI-only Linux failed `NetworkRetirementOwnershipTest.an older failed approval cannot release another retirement exclusion` by reading the responder table before its publication. The same run failed three pre-existing Detekt checks and `tools:schedulers:dokkaJavadoc` with Metaspace exhaustion. The log reports a 3 GiB maximum heap and 512 MiB maximum Metaspace; no limit was changed.

CI-only Windows failed:

- `PoisonedDefaultSecureRandomTest.startupAndKeyGenerationPathsAvoidDefaultSecureRandom`.
- `YamuxOutboundBufferBudgetRefCountTest.budget close fails undelivered in-flight parent write futures`.
- `NetworkMultiAddressConnectTest.connecting to a peer over several of its addresses leaves exactly one connection open`.
- `NoiseProbePhasesTest.phase progress cannot extend the original thirty second deadline`: BODY_COMPLETE, CLEANUP_COMPLETE, EXIT.
- `NoiseProbePhasesTest.exiting without a required phase still fails`: BODY_COMPLETE, CLEANUP_COMPLETE.

The initial Kubo Windows candidate has the same poison and five phase failures. Its other failures are `YamuxResumeAtFrameBoundCloseTcpTest.callbackCloseWhileDrainingReleasesTheArrivingFrame` and the new owned `QuicKuboTestJava.pingKubo(Path)` initialization wait. The initialization wait was investigated and corrected below. The rotating Yamux failure is an observation in unchanged source, not a failure demonstrated in the CI-only baseline.

The initial Noise Linux candidate's sole failed test is `YamuxResumeAtFrameBoundRePauseTcpTest.callbackPauseWhileDrainingLeavesRoomForTheArrivingFrame`: expected zero preceding delivered frames, observed one. The source is unchanged. It also has the three pre-correction Detekt issues and Metaspace failures in `examples:pinger:dokkaJavadoc` and `tools:simulator:dokkaJavadoc`. Its Noise class passes 30/30.

The supervisor's written answers assign Dokka, the poison/phase failures and rotating Yamux/network findings to follow-up. Their full first-attempt XML and complete logs are retained under this evidence branch. No unchanged suite was rerun to hide a failure.

## Fail-first proofs and corrections

The Noise separation guard reserves an ordinary-loopback port before starting the real fixture on the same port. It fails with the unchanged listener: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40522395822, 1 assertion failure. Moving fixture listeners/raw clients to 127.0.0.2 passes the identical guard: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40524004318, 1 pass. A real ping precedes connection-count checks. Existing saturation counts, assertions and bounds are unchanged.

The peer-publication gate holds the responder's actual mux negotiation completion. The initiator future still completes, deterministically exposing the empty peer table: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40526681840, normal case passes and held case fails. Releasing the gate and awaiting the public connection callback within the original ten-second connection budget passes both cases: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40527744306. All retirement, echo and cleanup assertions remain.

The original Kubo test fails 10/10 independent local first attempts without a pre-existing localhost service. The candidate starts its own real pinned binary, repository and loopback ports, retaining 1000 pings and all original assertions. The first Windows run exposed waiting for a child with unread output. Closing input did not release it: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40528378742. Consuming its 192 output bytes while leaving input open let the same child exit 0: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40528723683. The corrected helper redirects output to its own file before waiting, retains diagnostic text and deletes the file after the child stops.

Corrected real Kubo/static proof: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40529333358. Linux first XML: 2 tests pass, zero errors/skips, exactly 1000 printed pings. Detekt and Spotless also pass. Windows result is pending at this checkpoint.

The three Detekt changes are isolated in their own commit. Local single-worker Detekt passed; they change exception propagation placement in test cleanup and name/document an already-handled queue signal. They add no suppression or baseline waiver.

## Retry evidence

The first CI-only Linux and Windows XML each pass all 12 retry scenarios: `ConnectionCloseRetryTest` 10, `RelayConnectionRetryTest` 1, and `RetryCloseInactiveReviewTest` 1. The initial Noise Linux candidate also passes all 12; the Kubo Windows candidate passes the reviewer case. The targeted reviewer case additionally passes locally. These are actual real TCP/Noise/Yamux executions; the informational Kotlin success runs no Gradle tests and is not counted.

There is no observed new retry failure. This is not a claim that all full verification passes. Current lane changes to retry implementation files are empty.

## Gate decision and remaining work

Recommendation: require the complete native Linux `bld-build`, including documentation, to become green before merging. Keep full `gradle-windows` visible and tracked while its baseline defects are fixed, then make it required as well. The owner decides this composition; current rules are empty and were not changed. An informational Kotlin status reporting no build.kts must not replace native verification. The remaining queued Kotlin dispatch observation was not repaired through a forbidden rerequest, warm-up or service change.

The supervisor must file/assign the preserved Dokka and baseline platform findings and decide required checks with the owner. No PR is ready solely because targeted tests pass. Complete new full runs may still be running; follow the PR checks rather than rerun unchanged heads.

Required reading completed: repository README/FORK/CLAUDE, original handoff and linked prior evidence, https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md, https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md and https://my.externalbrain.wasmserver.com/doc/github-builders-racing-remote-ci.md. Independent test and code review checked fixture ownership, release during failure, real negotiation, callback ordering, original budgets/counts and unchanged production APIs.

The final native Windows candidate run completed at the same final CI URL: 1445 total, 1435 pass, 8 failures, zero errors, 2 existing skips. Its failed-case/type multiset exactly equals the baseline eight, with no newly failed identities. All 12 retry cases and both publication cases pass. The supervisor approves Linux bld-build as the required gate for the CI PR now, while Windows is tracked; the PR description records that decision. Full XML and machine-readable comparison are included.

Current dispositions requested by the supervisor:

- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/67: already merged; retry implementation/reviewer regression verified on develop.
- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/69: already merged; separate silent-responder fixture correction.
- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71: already merged; completed-future dispatch correction.
- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/74: open unrelated snapshot-25 backport; left unchanged for its existing workstream.
- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75: PR-READY under the supervisor-approved Linux bld-build gate. Linux 1445 total, 1443 pass, zero failures/errors, two existing skips. Windows 1445 total, 1435 pass, eight failures, zero errors, two existing skips; failed-case/type multiset exactly matches baseline. No new failed identities. Gate decision and Windows evidence are in the PR description.
- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/76: unready for the Windows daemon.log cleanup investigation. Current landing head is https://github.com/CodexCoder21Organization/jvm-libp2p/commit/1080ef69a59c4f24faaaf11a59f38ad5005bfd71. Its complete native Linux bld-build passes at https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40530525925. Both-platform targeted ping bodies complete 1000 pings; Windows directory cleanup remains unexplained. Inspection delay is not accepted as a fix.
- https://github.com/CodexCoder21Organization/jvm-libp2p/pull/77: blocked on the assigned Dokka Metaspace follow-up. Current complete Linux test XML is 1446 total, 1444 pass, zero failures/errors, two existing skips. Static checks pass; full bld-build fails tools:schedulers:dokkaJavadoc. Windows has the baseline poison/five-phase cases and rotating Yamux cases; fixture class itself passes. No task was dropped and no unchanged run was retried.

The current direct-delete/file-owner experiment is only on https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/hq100-kubo-owned-proof, source head https://github.com/CodexCoder21Organization/jvm-libp2p/commit/0406b74cebfc15fc915cfab466104f2d23acb84d and run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40533486585. It is not a landing candidate. It inspects the basename rather than the short Windows temp path, records log owners before termination, then attempts immediate deletion and inspects on failure. Its result is pending at this checkpoint. Earlier inspection after termination found no holder and changed the outcome, so it rules out no transient holder and proves no correction.

Open question: which process or Windows completion state retains daemon.log after the bounded Process wait reports exit? If immediate owner capture cannot answer this, make a minimal public-Process Windows reproducer that distinguishes exit-status observation from actual process-handle completion and file release, without sleeps or retries. Official OpenJDK17 source closes parent redirection streams during start and uses an exit-status fast path in timed wait; that is a candidate experiment, not a demonstrated JDK defect. Any demonstrated upstream issue must be fixed there rather than hidden in this fixture.

# Independent review: jvm-libp2p native Gradle CI

## Scope and verdict

Reviewed PR [https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75) at `bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672` against `origin/develop` at `25efb1996bd79e59e6eef28d2d44d9a0405fdd78`, using Git object reads only. Correction: I did not establish or control the shared checkout's branch state, so the earlier statement that it stayed on `develop` was inaccurate. I made no source changes during the review. No test or build was run, per the review brief.

**Verdict: one CI coverage finding; no failure-preserving gap found in the three test edits.** The test edits retain their assertions, resource cleanup, and existing wait bounds. The native workflow, isolated Kubo setup, Detekt-only `MuxChannel` change, and `FORK.md` update otherwise match the stated Gradle CI objective. The known Windows failures remain baseline-tracked per the supplied supervisor decision.

## Finding

### P2 — CI no longer checks the documented JDK 11 minimum

**Observed:** Both workflow jobs set up Temurin 17 ([Linux lines 23–32](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/.github/workflows/build.yml#L23-L32), [Windows lines 57–64](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/.github/workflows/build.yml#L57-L64)). The README still says JDK 11 or higher ([README line 383](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/README.md#L383)), `CLAUDE.md` says the same ([line 46](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/CLAUDE.md#L46)), and Gradle still sets Java source and target compatibility to 11 ([build.gradle.kts lines 121–124](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/build.gradle.kts#L121-L124)). The previous workflow ran its matrix on Java 11.

**Inference:** A Java 17-only build does not verify the advertised Java 11 runtime floor. `sourceCompatibility` and `targetCompatibility` set source and bytecode levels; without a Java 11 API restriction, code can still compile against APIs introduced after Java 11 and pass CI. Keep a Gradle build/test run on Java 11 (the Android SDK can be prepared under Java 17 first if needed), or deliberately update the supported-JDK documentation if Java 11 support is being dropped.

## Test-comprehensiveness review

- `NetworkRetirementOwnershipTest` now exercises both immediate and deliberately held peer publication. In the held case, it releases publication after the dial returns, awaits the public connection callback within the original ten-second deadline, and then checks the published connection table before starting the retirement assertions ([test lines 34–52 and 89–107](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/libp2p/src/test/kotlin/io/libp2p/network/NetworkRetirementOwnershipTest.kt#L34-L52)). `NetworkImpl.createHookedConnHandler` adds the connection to its public table before invoking that callback ([NetworkImpl lines 64–90](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/libp2p/src/main/kotlin/io/libp2p/network/NetworkImpl.kt#L64-L90)), so the synchronization matches the behavior being asserted. Both result branches remain checked: delivered approval must lead to retirement; denial must leave the remote connection usable. Existing `10`-second waits and the exact two-reply assertion remain unchanged.
- `NoiseMatcherReadinessTest` and `NestedConnectionCloseNotificationTest` only change how the original exception is rethrown after cleanup. Cleanup failures are retained as the primary failure when there was no earlier failure, or added as suppressed failures otherwise ([Noise lines 59–83](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/libp2p/src/test/kotlin/io/libp2p/security/noise/NoiseMatcherReadinessTest.kt#L59-L83), [nested close lines 94–105](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672/libp2p/src/test/kotlin/io/libp2p/transport/NestedConnectionCloseNotificationTest.kt#L94-L105)). Their behavioral assertions and time bounds are unchanged.
- The changed tests use real local libp2p hosts, transports, and subprocesses, and clean up their resources in `finally` blocks. The injected protocol, callback, and Netty queue conditions are controlled by the tests; I found no weakened assertions, skipped coverage, added retries, or increased test timeouts.

## Workflow and source review

- The `bld-build` Linux job runs `./gradlew --no-daemon --stacktrace --continue build dokkaJar`, prepares Android packages, and uploads test reports. The separate Windows job runs the same Gradle build and uploads its reports. Triggers cover `develop` pushes, PRs targeting `develop`, merge groups, and manual dispatch. Workflow permissions are read-only for repository contents.
- `install-run-ipfs.sh` downloads a versioned Kubo archive and checks its SHA-256 before extraction. It uses a new `IPFS_PATH`, disables bootstrap and mDNS discovery, binds the API and QUIC swarm to loopback, waits at most 30 seconds for daemon readiness, and prints the daemon log when startup fails. The workflow stops the owned PID and retains the log in artifacts.
- `MuxChannel.kt` changes the unused rejection exception parameter to `_` and adds an explanatory comment. The catch and its event-loop retry/shutdown behavior are unchanged.
- `FORK.md` replaces the stale Actions-startup note with the native Gradle workflow, Linux Kubo setup, and manual-publication description. This matches the changed workflow.

## CI evidence supplied with the brief

I did not re-read GitHub status or rerun CI. The provided evidence says the Linux gate [passed](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40528568980): 1,445 total tests, 1,443 passed, zero failures/errors, and two existing skips. Windows has eight failures matching the unchanged baseline at [run 40521828270](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40521828270); the supervisor explicitly authorized Linux as the current gate and Windows as tracked separately.

## Guidance read

Read the project `README.md`, `FORK.md` at the PR ref, and `CLAUDE.md`; no `AGENTS.md` exists in this checkout. Also read `/tmp/W2-TESTING.md` and `/tmp/W2-PHILOSOPHY.md`, supplied for the linked Testing Architecture and Engineering Philosophy documents. The test review applied their guidance on realistic public-API tests, comprehensive coverage, cleanup, and preserving time bounds.

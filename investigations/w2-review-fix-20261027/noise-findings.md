# PR77 Noise saturation regression findings

## Final status — 2026-10-27 22:45 UTC

- PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/77
- Pushed head: `0e7c69f436bbecce1e4aa158c103f8410dae4f53`; remote branch matches, checkout clean. The commit changes only `NoiseHandshakeOffEventLoopTest.kt`.
- Before push, `gh pr view ... --json headRefOid,state` was programmatically asserted `OPEN` at the expected prior head `b6aee9443c46c42f17c5189daf1fc7475aced23f`; push used `--force-with-lease` pinned to that SHA. The PR body was updated through the REST API with reason-first motivation and evidence links.
- Original-route assertion red: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40593885314 (1 test, 1 failure, 0 errors/skips; 121 queued vs 120 counted failures).
- Isolated targeted JUnit case green: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40594495806 (1 pass, 0 failures/errors/skips; 120 queued and 120 timeouts). The overall workflow later failed Spotless on formatting only; the exact wrap was fixed.
- Final formatted original-route reversal red: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40595839715 (1 test, intended assertion failure, 0 errors/skips; 121 vs 120).
- Exact final formatted isolated source blob `4b0f194d46d40b3c2d1b6c6280dda3558c16b550`, SHA-256 `b78283e14b93b53fdbab80780b261299c00054dd3e07b52812c32dd020245e6b`, was statically checked in https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40595283268. Spotless passed. Detekt reported three findings in files byte-identical to `origin/develop`, all changed in [PR76](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/76); no finding was reported in the changed file. The full log is `/tmp/w2-fix213755/noise77-static-40595283268.log`.
- Full red/green/reversal logs and JUnit XMLs are stored under `/tmp/w2-fix213755/noise77-*`. No local test JVM/full suite was run. No merge, enqueue, or deploy was performed.

## Plan

- [x] Read the project README, FORK, CLAUDE, any AGENTS, and the required testing, philosophy, and code-review documents.
- [x] Inspect the existing Noise saturation test and identify a deterministic unrelated real caller path that bypasses the observer scope while sharing the target event loop.
- [x] Add the regression in the single permitted test file, keeping all existing 120 dialers, 128 queue attempts, five-second budgets, and assertions; retain the existing binding regression.
- [x] Run red/green/reversal through GitHub Actions and verify the final formatted source hash.
- [x] Audit neighboring routes, review the one-file diff, rebase on origin/develop, assert PR state/head, push with an explicit lease, and update the reason-first PR description.

## Progress

- 2026-10-27: Checkout is clean on `fix/hq100-noise-fixture` at expected commit `b6aee9443c46c42f17c5189daf1fc7475aced23f`. Repository README and FORK were opened; CLAUDE and the three required review documents are being read. No code changed and no JVM started.

## Documents read

- Project: `README.md`, `FORK.md`, and `CLAUDE.md`; no repository `AGENTS.md` exists in the checkout.
- Required guidance: `/tmp/w2-fix213755/TESTING.md`, `PHILOSOPHY.md`, and `CODE_REVIEW.md` (read in full). Relevant rules are TDD with a failing assertion first, hermetic real-network tests, public API behavior, reliable contract-driven reproducers, and a line-by-line audit for weakened existing assertions.
- Build context: the project is Gradle-based, with `./gradlew :libp2p:test`; it has no `scripts/test.bash`. `build.yml` runs the full Gradle build on push/PR.

## Root-cause mechanism

The saturation harness installs a process-wide test observer and accounts for the 120 `network.connect` futures it created. It did not prove that an unrelated real caller could not reach the relay and enqueue another responder task. The final regression establishes an active Noise anchor first, uses one network worker for this isolated harness, starts the 120 counted dials, waits for responder work to queue, then runs a separate-JVM caller through ordinary `127.0.0.1`. With the relay on legacy `.1`, the unrelated handshake is offloaded to the blocked executor and creates task 121. With the relay on `.2`, a test-owned accept-and-close endpoint owns the same `.1` port and keeps that Noise call outside the relay.

## Progress

- Read all required project and review documents. Confirmed no AGENTS.md is present in the checkout.
- Rebased clean branch on freshly fetched `origin/develop`; no commits needed to replay. No build/test JVM has been started.
- PR remains open at the expected head on the last read; I will recheck immediately before final push as directed.

## Regression design and proof setup

- The final saturation path launches `NoiseUnrelatedCallerHarness` in a separate JVM after the 120 intended dials have queued responder work. The child creates a real Noise `Host`, dials the relay peer ID at `/ip4/127.0.0.1/tcp/<relay-port>`, reports attempt and failure, and always cleans up in its own `finally`. The parent waits synchronously for that process before its unchanged six-second wait and assertions.
- The child does not bind an ordinary listener or share the process-wide observer/test hook. On `.1`, the relay owns the port and queues its responder task. On `.2`, the parent test owns `.1` at the same port with an accept-and-close listener; the parent waits for acceptance. The harness validates the actual relay IP/port after startup.
- A proof-only workflow runs the exact saturation test and checks JUnit XML plus the 121-versus-120 assertion. The proof branch excludes itself from the unrelated full workflow. These workflow changes are not part of the PR branch.

## Progress — proof harness correction

- 2026-10-27 21:52 UTC: The first proof workflow did not execute the test: test compilation failed because Kotlin inferred `ExecutionException?` for the new cause-chain sequence. This is a harness compile error, not red evidence. I fixed the sequence's explicit `Throwable` type; I will keep that failed workflow labeled preliminary.
- Parent review identified that a `.2` listener leaves `.1` unowned, making a refusal depend on the machine. The harness now creates an OS-assigned `.1` `ServerSocket` reservation only when the configured fixture is isolated, passes that exact port to the relay, runs a daemon accept-and-close endpoint, and waits for the unrelated caller to be accepted. After host startup it checks the actual bound IPv4 address: `.1` must be owned by the relay with no second reservation, while `.2` must have the same port as the test-owned reservation. The old-address proof still has no second bind and remains able to queue caller 121.
- Cleanup closes the reservation in `finally`; reservation creation/startup failures fail the test with diagnostics, with no skip path. The acceptance future reports failures and has a five-second bound. Existing 120 dialers, 128 queue attempts, and timeout budgets remain unchanged. `git diff --check` passes; no local JVM/build was started.

## Proof runs so far (neither is original-route red evidence)

- Preliminary run `40589469364` (commit `bb0289b612feefaf8f874821947c652348fbc97d`, [workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40589469364)) failed in `:libp2p:compileTestKotlin` before test execution. The complete failure log is available from `gh run view 40589469364 --log-failed`. Compiler diagnostics at `NoiseHandshakeOffEventLoopTest.kt:924:21` and `:924:36` were respectively `Type mismatch: inferred type is Throwable? but ExecutionException? was expected` and `Type mismatch: inferred type is Throwable but ExecutionException? was expected`. Gradle then reported `Execution failed for task ':libp2p:compileTestKotlin'` / Kotlin `Compilation error. See log for more details`; there was no test XML. The cause chain now explicitly uses `generateSequence<Throwable>`.
- Run `40590364969` (commit `24b71eadd9d88b896472dee8fc35a84f06651482`, [workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40590364969)) compiled and ran one test but did not reach the queue-count assertion. The separate caller was started before any of the 120 dialers; `NoiseXXSecureChannel.enqueueCrypto()` runs crypto inline while `offloadCrypto` is false, so that caller legitimately completed. The precise test failure was `IllegalStateException: Unrelated Noise caller unexpectedly connected ...` at `NoiseHandshakeOffEventLoopTest.kt:948`, through `runUnrelatedNoiseCallerHarness` line 920 and `NoiseHandshakePathHarness.verifySaturatedRemoteWaitsTimeOut` line 687. The outer stack ends at the JUnit test method line 123. This is an ordering defect in my reproducer, not evidence for the requested behavior; no assertion was weakened.
- To force the actual reviewed condition, I moved the separate caller after the 120 intended connects are started and the original responder queue becomes nonempty. Its process runs while the unchanged 11-second wait expires, and its attempted/failure markers plus the test-owned ordinary endpoint acceptance are awaited before checks. The `saturated` harness JVM now uses one Netty worker so all relay accepts share one active-handshake set; this is a deterministic topology choice, not machine load. The caller process still runs separately and outside the recorder.

## Ordering correction after parent mechanism review

The first proof caller was inline because `NoiseXXSecureChannel.enqueueCrypto()` consults `cryptoExecutorSelector` only after offload has been selected. The final setup uses the parent's deterministic topology: the saturated harness JVM alone has `-Dio.netty.eventLoopThreads=1`; before installing the selector it completes and retains a real Noise anchor connection, awaits its publication callback, and verifies no anchor read-timeout was recorded. It then installs the selector, starts the 120 counted dialers, waits for the first queued responder task, and runs the unrelated caller synchronously. The anchor is visible to `hasOtherConnectionsOnLoop`, so the caller reaches the blocked executor without a timing race. The unchanged wait is `HandshakeTimeoutSec + 1` (six seconds with the five-second baseline timeout).

- Parent review refined anchor publication: the relay registers a `ConnectionHandler` before the anchor dial and the test awaits a future keyed to `anchor.peerId`, avoiding a race with the relay connection table. The synchronous outside caller runs after the original queue is nonempty, and its helper owns and cleans up the process on every exit path. The 120 dials remain the only members of the checked `connections` list.

## Assertion-level red from the prior anchor setup

- Run `40591253478` ([workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40591253478), commit `56d576f6380846052e23b8680ef3c3b0e174d3a1`) compiled and executed the targeted test. JUnit XML had `tests=1`, `skipped=0`, `failures=1`, `errors=0`. It reported the exact assertion: `Expected every one of the 121 queued responder message-1 crypto tasks to leave exactly one initiator waiting until timeout, but 120 dials failed`; the unrelated caller output shows a real attempt and a ReadTimeoutException chain. Artifact was downloaded to `/tmp/w2-fix213755/noise77-red-56d576f6/`. This proves the route reaches the extra responder assertion, but I will repeat the red run after adding an explicit callback future for the anchor because the immediate `relay.network.connections` check had a publication race noted by parent.
- Current proof head `40ee9ae8` registers a relay `ConnectionHandler` before dialing the anchor and completes a future only for `connection.secureSession().remoteId == anchor.peerId`. It awaits that future with a five-second bound before checking both endpoint connection tables. This removes the check race without changing listener ownership or time budgets.

- Assertion-level red re-run on the publication-safe setup completed on `40ee9ae84912dcbcee09413e2977787dc11aeacf`; XML in `/tmp/w2-fix213755/noise77-red-40ee9ae8/` has one test, one failure, zero errors/skips. The unrelated process logged its `.1` attempt and the read-timeout cause. The failure remains exactly the expected 121 queued tasks versus 120 counted dial failures.
- The next disposable workflow changes only proof mode to green and the fixture constant to `127.0.0.2`; current proof commit is `016a1212`. The test source otherwise matches the assertion-level red setup. The main PR checkout has not been modified.

- Green run `40592388741` ([workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40592388741), proof commit `016a12127af6cb2440800f012c2fed403add7cc9`) completed successfully. Its JUnit XML at `/tmp/w2-fix213755/noise77-green-016a1212/` reports one test, zero skips/failures/errors. Output reports `timedOut=120 readTimeoutFailures=120 queuedResponderTasks=120`. The green test-file SHA-256 is `30c161a7220c92b7a0573fc9f85ce2404d9af359bed8111a40563e62aabef679` (Git blob: record next).
- Parent requested no duplicate final-green workflow after reversal. I will run the reversed `.1` control once, restore the exact file blob from green commit `016a1212`, verify its SHA is unchanged, and use that restored blob for the PR branch.
- Green test-file Git blob ID: `42c3fc499713ddd3d271fb3530407b528acd79b5`.

- Reversal run `40592897173` ([workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40592897173), proof commit `c23d2c4414f3f31a4ec36abc6a010d3cf278a7a9`) reproduced the same failure: one test, one assertion failure, zero errors/skips; JUnit artifact in `/tmp/w2-fix213755/noise77-reversal-c23d2c44/` reports `121 queued ... but 120 dials failed` and shows the unrelated `.1` caller's ReadTimeoutException.
- For static checks only, disposable branch `proof/noise77-static` has exact green source blob `42c3fc499713ddd3d271fb3530407b528acd79b5` (SHA-256 `30c161a7220c92b7a0573fc9f85ce2404d9af359bed8111a40563e62aabef679`), a dedicated workflow for `:libp2p:spotlessCheck :libp2p:detekt`, and excludes that branch from the regular full build. Static workflow `40593551756` is in progress. It will not run the unit test again.

## Synchronous process ownership correction

- Parent review required keeping the launched caller process owned by the harness call stack. The helper now runs synchronously after the first queued responder task. Its own `try/finally` always destroys/joins the child on failure; the existing `HandshakeTimeoutSec + 1` wait stays after it. The anchor and one-event-loop topology remove any timing dependence, so this retains the intended external responder path.
- On final synchronous red source commit `a07e026e02339bea24990a145ff6f8138b02024e`, run `40593885314` ([workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40593885314)) completed the exact expected one-case assertion failure, with zero errors/skips. JUnit in `/tmp/w2-fix213755/noise77-sync-red-a07e026e/` says `121 queued ... but 120 dials failed` and records caller attempt plus ReadTimeoutException.
- The earlier static-only run `40593551756` was canceled before executing checks after this process-ownership review changed the source. The new disposable proof workflow runs `spotlessCheck` and `detekt` only after a successful green-mode targeted test, so both static checks will apply to the final source blob in the same run. No local JVM has been started.

## Formatting and static-check result

- The synchronous green targeted test run was `40594495806` on source commit `59e473a3`; its JUnit artifact records one pass, zero failures/errors/skips, with `queuedResponderTasks=120` and `readTimeoutFailures=120`. The workflow overall was marked failed only because its following Spotless step found a wrapping difference in the `relay.addConnectionHandler(ConnectionHandler.create { ... })` call. That output prescribed adding one line break after `addConnectionHandler(` and adjusting indentation/closing parenthesis. I applied exactly that diff.
- Comparison of source `59e473a3` to the final formatted isolated source `016c64d1` shows only that wrapping/indentation change; `git diff --ignore-all-space` leaves no diff. Original green test source Git blob/SHA-256: `77a3bce7c1d396490010c82e7005f32629e73c45` / `709edcbc58f69602ac92c0b88e524c4497b1253bd8f4769f16d767d87f035751`. Final formatted isolated source Git blob/SHA-256: `4b0f194d46d40b3c2d1b6c6280dda3558c16b550` / `b78283e14b93b53fdbab80780b261299c00054dd3e07b52812c32dd020245e6b`.
- Static-only run `40595283268` was on the exact final formatted `.2` source. Spotless passed. Detekt reported three findings in files byte-identical to `origin/develop`: `MuxChannel.kt:401 [SwallowedException]`, `NestedConnectionCloseNotificationTest.kt:103 [ThrowingExceptionFromFinally]`, and `NoiseMatcherReadinessTest.kt:72 [ThrowingExceptionFromFinally]`. It reported none in `NoiseHandshakeOffEventLoopTest.kt`. These baseline corrections are included in [PR76](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/76). The run's overall conclusion is failure from those findings; full log is `/tmp/w2-fix213755/noise77-static-40595283268.log`.
- Final formatted reversal red is still pending. The red attempt `8999de45` was canceled before completion and will not be cited as evidence.

## Final formatted evidence and coverage audit

- The final formatted reversal run is `40595839715` ([workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40595839715)), with source commit `92ba5f21` / test-file blob `7eab083b5c4bc547119c6650126033ef05fcb336`. JUnit reports `tests=1`, `skipped=0`, `failures=1`, `errors=0`; the assertion is again `121 queued ... but 120 dials failed`, and output includes the unrelated caller's real `.1` attempt and ReadTimeoutException chain. Full logs are at `/tmp/w2-fix213755/noise77-final-reversal-92ba5f21.log`; JUnit is under `/tmp/w2-fix213755/noise77-final-reversal-92ba5f21/`.
- Final green source was restored from proof commit `016c64d1`, exact test-file blob `4b0f194d46d40b3c2d1b6c6280dda3558c16b550`, SHA-256 `b78283e14b93b53fdbab80780b261299c00054dd3e07b52812c32dd020245e6b`. The staged PR77 file has those exact hashes. The final formatted `.1` reversal differs from this `.2` green blob only in the `NOISE_TEST_HOST` constant; the earlier green targeted test (`40594495806`, on synchronous pre-format blob `77a3bce7c1d396490010c82e7005f32629e73c45`) passed one test with zero failures/errors/skips and output `queuedResponderTasks=120 readTimeoutFailures=120`. The only change from that green source to final formatted source is the Spotless line-wrap/indentation correction already documented; `git diff --ignore-all-space` between those blobs is empty. Static run checks exact final green blob; Spotless passes, while Detekt reports only three findings also byte-identical to develop and addressed in [PR76](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/76).
- Full red and green logs are saved at `/tmp/w2-fix213755/noise77-sync-red-a07e026e.log` and `/tmp/w2-fix213755/noise77-sync-green-59e473a3.log`; JUnit artifacts are in matching directories. The obsolete red rerun `40594963404` was canceled before completion and is not counted as evidence.

Coverage audit of the changed test file:

- The unrelated route is a separate real Noise `Host` process, using relay peer ID and ordinary `.1`/actual port. The parent verifies attempted/failure output before queue/count checks. The parent installs the test executor selector only after an anchor Noise connection is published and retained, with a single event-loop worker in saturation mode so `hasOtherConnectionsOnLoop` deterministically selects the blocked executor.
- On isolated `.2`, the test owns `.1` at that same port with a controlled accept-and-close `ServerSocket`; it waits for actual acceptance. On shared `.1`, the relay is the owner and no second socket is bound. Actual bound host/port is checked after startup. Reservation setup, accept, child process, and host cleanup all have bounded, visible failures; there is no skip path.
- The original 120 counted dial futures, 5-second queue deadline, `HandshakeTimeoutSec + 1` wait (baseline timeout five seconds), queue capacity, failure-equals-queue assertion, and exact ReadTimeoutException count remain unchanged. The binding regression and other test methods in the file retain their assertions. The only altered launcher setting is `-Dio.netty.eventLoopThreads=1` for the already-isolated saturated child; other harness modes retain their defaults.
- Scope audit: only `NoiseHandshakeOffEventLoopTest.kt` is staged for PR77; no production source or ordinary test expectation changed. No local test/build JVM or full suite was run.

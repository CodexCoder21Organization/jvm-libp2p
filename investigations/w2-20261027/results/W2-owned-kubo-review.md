# Owned Kubo candidate review

## Plan

- [x] Read the supplied testing/engineering guidance relevant to this review and inspect the exact candidate/base commit identities.
- [x] Test-comprehensiveness pass: inspect the new owned-process/repository/readiness/watch/host/log lifecycle coverage and reported failure/pass evidence.
- [x] Adversarial code and CI pass: inspect build pin, workflow Java versions/task scope, Kubo lifecycle cleanup, and FORK instructions.
- [x] Record concrete findings with full source links and observed/inferred distinctions.

Read-only review. No checkout, edits, test/build/JVM run, CI status read, push, comment, approval, or merge.

## Reviewed inputs

- Base: `25efb1996bd79e59e6eef28d2d44d9a0405fdd78` (`origin/develop`).
- Candidate: `872086e0a27e570e2f36f000c9dc15ee654de000`, parent `45ef9df7ce70db422e3196bea3278db0da981254`.
- Testing Architecture: [TESTING.md](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md), especially [end-to-end tests](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md#end-to-end-tests-not-mocks), [side-effect isolation](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md#side-effect-isolation), and [test comprehensiveness](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/architecture/TESTING.md#ultrathink-are-the-tests-comprehensive).
- Engineering Philosophy: [PHILOSOPHY.md](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md), including [fix the root cause](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md#fix-the-root-cause-never-mask-the-symptom).
- Candidate source links: [QuicKuboTestJava.java](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/libp2p/src/test/java/io/libp2p/transport/quic/QuicKuboTestJava.java), [libp2p/build.gradle.kts](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/libp2p/build.gradle.kts), [build.yml](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/.github/workflows/build.yml), [owned-Kubo proof workflow](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/.github/workflows/w2-owned-kubo-proof.yml), [install-run-ipfs.sh](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/install-run-ipfs.sh), and [FORK.md](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/FORK.md).

## Test-comprehensiveness pass

**Observed:** `QuicKuboTestJava` now initializes a fresh repository under JUnit `@TempDir`, starts the checksum-pinned Kubo binary as a child process, binds API/Gateway/QUIC to loopback with OS-assigned ports, clears bootstrap peers and disables MDNS/NAT mapping. It waits against a monotonic five-second readiness deadline and consumes `WatchService` events before resetting the key. It then obtains Kubo's actual identity and advertised QUIC address, starts a real `Host`, connects, retains the existing 1,000-ping loop, closes the stream, and asserts a later ping fails. Host and Kubo cleanup run in nested `finally` blocks; process termination is awaited before the daemon log is moved, which exercises immediate file release on Windows. The init subprocess also has bounded wait/forced-stop cleanup. See [test lines 21-119](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/libp2p/src/test/java/io/libp2p/transport/quic/QuicKuboTestJava.java#L21-L119) and [helper lines 123-178](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/libp2p/src/test/java/io/libp2p/transport/quic/QuicKuboTestJava.java#L123-L178).

**Assessment:** The new test covers the end-to-end QUIC contract with a real Kubo node, including isolation from a pre-existing daemon, dynamic port discovery, startup failure reporting, 1,000 pings, stream terminal behavior, and process/log cleanup. The prior readiness bug is addressed structurally: events are drained before `WatchKey.reset()`. This matches the supplied testing guidance on real dependencies, side-effect cleanup, and covering the behavior under test.

**Evidence boundary:** The root supplied same-runner JDK17/JDK24/JDK17 native process-wait evidence and consumer red/green run URLs. I treated those as supplied evidence, did not rerun them, and did not inspect CI status. The final candidate's targeted proof was still in flight per the assignment; this review makes no claim that it passed. The proof workflow covers the Kubo test plus `detekt` and `spotlessCheck`, while the full workflow also runs `build dokkaJar`.

The ownership/readiness test correction and the other shared test edits (`NetworkRetirementOwnershipTest`, `NoiseMatcherReadinessTest`, and `NestedConnectionCloseNotificationTest`) were explicitly marked verify-don't-redo in the assignment; I did not repeat their prior review.

## Adversarial code and CI pass

**Observed:** `prepareKubo` pins Kubo 0.34.1, maps Linux/macOS/Windows and supported architectures, downloads the matching release archive, computes SHA-512 while downloading, checks against a per-platform constant, extracts the expected executable, and marks non-Windows binaries executable. The `:libp2p:test` task depends on it and receives the executable path. See [build.gradle.kts lines 70-133](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/libp2p/build.gradle.kts#L70-L133). I did not independently retrieve the upstream checksum sidecars during this pass, so this verifies the code's pin-and-check mechanism, not each constant's value.

**Observed:** Linux prepares Android SDK components on Temurin 17, then switches to Temurin 11 before running the unchanged native Gradle `build dokkaJar` gate. Windows uses Temurin 24 and runs the same Gradle gate. Both upload test and build reports. The wrapper is Gradle 8.14.3. The separate evidence workflow is branch-scoped, selects JDK 24 on push (or a manually selected 17/24), and runs only the named Kubo test plus static checks. See [build.yml lines 13-68](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/.github/workflows/build.yml#L13-L68), [proof workflow lines 1-32](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/.github/workflows/w2-owned-kubo-proof.yml#L1-L32), and [Gradle wrapper](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/gradle/wrapper/gradle-wrapper.properties).

**Observed:** `FORK.md` describes the JDK split, the owned Kubo repository, and the manual publication flow. It correctly points at the OpenJDK process-wait fix [b77bd5fd6a6f7ddbed90300fba790da4fb683275](https://github.com/openjdk/jdk/commit/b77bd5fd6a6f7ddbed90300fba790da4fb683275). The old `install-run-ipfs.sh` is no longer referenced by the candidate workflow or other tracked files (`git grep` found no caller); it remains a directly invokable helper.

### Finding

**P2 ? `install-run-ipfs.sh` can hang forever while handling startup failure.**

- **Observed:** On the 30-second readiness timeout branch, the script sends `SIGTERM` and then calls an unbounded `wait "$daemon_pid"` at [lines 30-35](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/872086e0a27e570e2f36f000c9dc15ee654de000/install-run-ipfs.sh#L30-L35). There is no `SIGKILL` fallback or bound on that wait.
- **Inferred impact:** If Kubo does not exit promptly after `SIGTERM` (for example, while stuck in startup or shutdown), the helper never returns and does not report the original readiness failure. This is limited to direct uses of the helper because the current `build.yml` no longer invokes it; the replacement JUnit test has bounded graceful/forced termination.
- **Suggested correction:** Bound the graceful wait and then force-stop and reap the child, preserving the startup log and returning failure.

## Verdict

The owned Kubo JUnit test and active Gradle CI wiring have no additional actionable gap found in this read-only pass. The one finding is confined to the retained, currently unreferenced shell helper. This is not a clean verdict for the entire candidate until that helper's unbounded failure cleanup is addressed or explicitly removed from the supported workflow.

# Independent review: jvm-libp2p Kubo workflow fix

## Plan
- [x] Read repository instructions, README/FORK, and required testing/engineering documents.
- [x] Review tests against startup failure, endpoint isolation, expected peer identity, process ownership/exit, and cleanup invariants.
- [x] Review workflow wiring, argument/environment propagation, and documentation formatting.
- [x] Record separate test-comprehensiveness and adversarial-code findings with actionable locations.

## Progress
- Started read-only review; no repository files changed and no builds/tests/JVMs launched.

## Progress (2026-10-28)

### Observed
- No repository `AGENTS.md` is present. Read the complete repository `README.md` and `FORK.md`, plus the complete `/tmp/W1-testing.md` and `/tmp/W1-philosophy.md` supplied for this review.
- Read the diff from `9aa6a6ba`, the current worktree changes, the three supplied red/green/reversal logs, and the current workflow, Python helper/tests, Java Kubo test, and `gradlew` launch tail. Did not edit the repository or run tests/build/JVMs.
- The supplied readiness evidence is valid: the two wrong-node cases fail against the old readiness code and on the wait-function reversal; the healthy owned-node case passes. Current tests exercise real Kubo instances with temporary repos, empty bootstrap, disabled mDNS, allocated API/QUIC ports, identity checks, command exit propagation, owned-node exit, and a direct SIGTERM cleanup path.

### Test-comprehensiveness finding
- The new SIGTERM regression test currently proves cleanup of one direct Python command process and the Kubo PID, but does not prove cleanup of command descendants. The workflow's command is `./gradlew`; `gradlew` execs the Gradle JVM, which can own test-worker JVM descendants. A child that exits or is terminated while leaving a worker alive can outlive the wrapper and continue using the repo. Exercise a command that starts a long-lived grandchild, then both exits and receives wrapper SIGTERM/node failure; assert the process group and temp repo are gone.

### Adversarial-code finding
- `stop()` at `.github/scripts/kubo-node.py:49-56` only terminates/waits/kills the direct `Popen` process. `run_with_node()` calls it for the verification command at lines 95-104 and the Kubo node at lines 73-106. This does not own the process tree of the Gradle command. Isolate the verification command in its own process group/session and terminate/kill that group in cleanup; ensure cleanup also runs after normal leader exit if descendants remain.
- Current test-only configuration crosses process boundaries through global environment: `.github/scripts/test_kubo_node.py:19,27,39,115`, `.github/scripts/kubo-node.py:62,93,95`, and `QuicKuboTestJava.java:22-23,40-41,65`. This conflicts with the Testing guidance that tests must not read environment/process-global configuration. Parent plans are to make Kubo path positional, use `ipfs --repo`, and pass a generated per-run fixture file to Java; I notified the parent that a fixed `libp2p/build/kubo-node.properties` path must account for concurrent wrappers/direct Gradle invocations to avoid cross-run overwrite/removal.
- The SIGTERM handler plus `test_termination_reaps_node_and_command` are present in the live candidate and directly cover wrapper SIGTERM, direct child reaping, repo deletion, and exit 143. The remaining process-tree gap is adjacent to, but not covered by, that test.

### Documentation note
- `FORK.md:64` now removes the obsolete no-green-CI note and links a successful `bld-build` run. The linked run page reports success on PR #80 (`fix/runnable-native-ci-024054`), but that run predates the Kubo ownership/wrapper changes currently under review. I advised the parent to label it explicitly as a prior green run so it is not read as CI verification of these newer changes.

## Final review (2026-10-28)

### Test-comprehensiveness conclusion
- The readiness regressions cover both conditions that let the previous check accept the wrong service: the owned Kubo process fails its bind while another Kubo serves that API, and a live owned Kubo is checked against another node's API. They also retain the healthy-owned-node control. The provided red and reversal logs show both wrong-node cases fail under the prior readiness behavior while the healthy case passes; the green log shows the fixed readiness suite passes.
- The final wrapper coverage now includes allocated API/QUIC endpoints and fixture values, command exit status 23 propagation, Kubo exit during a command, SIGTERM cleanup of Kubo/command/repo, fixture collision rejection, and a real descendant TCP listener that ignores SIGTERM. The supplied `/tmp/W1-043124-final8.log` reports all eight Kubo tests passing. I did not launch tests, builds, or JVMs.
- The new descendant test closes the process-tree cleanup gap identified earlier: it confirms an uncooperative descendant endpoint is closed before the wrapper returns. Current `stop()` also kills the owned process group after the leader wait and tolerates an already-exited group.

### Adversarial-code findings
1. **Reject nonpositive PIDs in readiness checks.** In `.github/scripts/kubo-node.py:17-25`, `os.kill(pid, 0)` has special process-group semantics for zero and negative values. A `ready 0 <api> <matching-peer-id>` call can pass the liveness check without checking a process PID because `/proc/0/stat` is absent, then return ready based on the endpoint identity. Validate `pid > 0` before the liveness probe and test zero/negative input as a failure. This is the remaining code finding.
2. **Make the documented local test command fixture-aware.** `FORK.md:59-60` lists `./gradlew :libp2p:test` as the test-suite command, but `QuicKuboTestJava.pingKubo` now requires `build/kubo-node.properties`. The new wrapper example at lines 66-73 runs only the targeted Kubo test, so the advertised full-suite command still fails when followed as written. Update the documented full-suite invocation to run through `kubo-node.py run` with the installed binary and fixture path, or clearly distinguish the direct Gradle command from the fixture-dependent suite.
3. **Inherited skip in the touched Java test.** `QuicKuboTestJava.java:22` returns successfully on Windows before loading the required fixture. This is pre-existing, but remains a silent test skip and means the stated missing-fixture failure does not apply on Windows. It conflicts with the supplied no-skips guidance; decide whether to remove it or report it as a separate platform constraint.

### Other reviewed areas
- No environment-variable reads remain in the Kubo Python scripts or the Java test; the binary path and fixture are explicit arguments/files.
- Workflow argument propagation correctly invokes the wrapper with the pinned Kubo binary, log path, fixture path, and Gradle command. Both `bld-build.yml` and the proof workflow use that form.
- `FORK.md` labels the linked successful Actions run as prior, avoiding the implication that it verifies the newer Kubo ownership change. The GitHub run page reports success.
- `git diff --check` passed. No repository files were changed by this review.

## Final resolution verification (commit `afb4be37`, 2026-10-28)

The latest stable candidate is clean at `afb4be37`; I reviewed it read-only and did not run tests or JVMs.

- **PID validation: resolved.** `require_running` rejects `pid <= 0` before calling `os.kill` (current `.github/scripts/kubo-node.py:17-27`). The real-node test now supplies both `0` and `-1` while pointing at a live Kubo API with the matching peer ID. `/tmp/W1-043124-pid-red.log` and `/tmp/W1-043124-pid-reversal.log` show both subcases incorrectly returning ready without the check; `/tmp/W1-043124-pid-green.log` shows the test passing with it. `/tmp/W1-043124-final9.log` reports 9/9 passing.
- **Full-suite documentation: resolved.** `FORK.md:58-64` now runs `:libp2p:build` through the node wrapper and says to replace that task with `:libp2p:test` for test-only runs. `README.md:394-399` likewise runs `./gradlew build` through the wrapper and points to the fork's targeted instructions.
- **Descendant cleanup: resolved.** `stop()` signals the verification process group, waits for the leader, then sends a final group SIGKILL in `finally`, tolerating an already-exited group (`kubo-node.py:51-74`). The real descendant-listener test is in the final 9-test run.
- **Windows early return: inherited and unchanged.** I compared `QuicKuboTestJava.java` at `9aa6a6ba` with the current file: the Windows early return is identical. The native workflow and new Kubo wrapper instructions target Linux, and the brief asks to preserve the existing assertions without changing Windows verification. Record this as an inherited platform limitation, not a regression introduced by this patch.
- **Environment configuration: resolved.** No `System.getenv` or indexed `os.environ` reads remain in the Kubo helper/tests or Java integration test. Kubo receives `--repo-dir`; the binary is a positional argument; endpoint data uses the explicit fixture file.
- **Final diff hygiene:** `git diff --check` passes and the candidate working tree is clean.

After these changes, I found no remaining introduced code, test, or documentation findings. The previous PID and local-build documentation findings are closed.

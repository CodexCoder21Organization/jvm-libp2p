# Connection-selection compatibility fix

Start: 2026-11-25 10:52 UTC. Deadline: about 12:12 UTC.

## Plan
- [in progress] Locate owner and trace captured failure to exact source lines; decide contract.
- [pending] Read required documents and write lifecycle invariants.
- [pending] Add two-real-node duplicate-connection reproducer; record assertion failure; push milestone.
- [pending] Fix owning implementation; mutation control and targeted tests; push milestone.
- [pending] Full suite, test review and code review; open PR; verify CI; report consumer bump.

## Constraints
No production changes, environments, publishing, consumer bumps, merging, enqueueing, handoff tooling, reflection, timeout increases, iteration reductions, or test weakening. At most two local test scripts. Record and reap started processes. Commits carry requested trailer; PR ends with requested attribution. WIP pushes at milestones.

## 10:54 UTC — evidence read
OBSERVED: Prior findings and rejected stderr identify connection-selection negotiation failure with duplicate-retirement warnings. Production remained rolled back. No production access performed here.

## 2026-11-25 10:54 UTC — root cause checkpoint
OBSERVED: Owning repository is https://github.com/CodexCoder21Organization/jvm-libp2p . Its default branch is `develop`, not `main`; fresh head e95b8569. `NetworkImpl.kt:143-173` fans out address dials and calls `rejectDuplicate` on surplus physical connections. `ConnectionSelection.rejectDuplicate` opens `SelectionBinding` through the mux session; `ProtocolSelect.kt:86` converts explicit negotiation refusal to `NoSuchRemoteProtocolException`. The old duplicate failure callback logs the throwable at WARN, exactly matching captured stderr. A failed retirement restores eligibility and leaves the socket open; later duplicate decisions or incoming child release can open another negotiation. Caller connect does not await retirement.

MECHANISM: The jvm-libp2p physical initiator probes a surplus connection with `/libp2p/connection-selection/1.0.0`; an older/non-offering peer rejects that optional stream, which old `ConnectionSelection` treats as a failed retirement and logs with an exception stack on each attempt. This is case (a): explicit unsupported negotiation is a normal compatibility result; without peer agreement the correct outcome is to preserve ambiguous parents, since the remote may already have published one.

OBSERVED: This exact source defect was already fixed by https://github.com/CodexCoder21Organization/jvm-libp2p/pull/106 , merged as dcdb9b43 on 2026-11-14. Current `PeerSelectionSupport.kt:21-48,53-56` recognizes only the explicit refusal (unwrapping future wrappers), caches it for the continuously connected peer lifetime, returns false, and logs one complete INFO line with no throwable. Resets and other failures retain failure reporting. Current README states the same contract. Current real TCP/Noise/Yamux tests configure a peer without selection and exercise duplicate dials, Echo, full-text INFO assertion, repeat calls, last-close reconnect, and non-absence failures.

OBSERVED: Published metadata includes snapshots through 53 (lastUpdated 20261125043600). Downloaded snapshot-43 and snapshot-53. Bytecode inspection shows snapshot-43 has no PeerSelectionSupport and directly calls requestRetirement; snapshot-53 includes PeerSelectionSupport and its unsupported state. Need determine earliest published fixed version and compare the deployed jar's bytes. No source change justified yet; adding another compatibility fix/PR would duplicate already merged work. Existing test execution started via Gradle (this is not a kompile repository and has no remote test switch). WIP branch pushed at current source head: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/gitprojecthealth-connection-selection-20261125 . No production access or mutations.

INFER: Captured peers omit the protocol; individual peer deployment versions are not established by those stacks, so ContainerNursery identity/version is not asserted.

## Invariants to validate (no new implementation proposed)
| Resource/state | Creator/owner | Terminal/re-entry behavior | Public proof |
|---|---|---|---|
| Surplus parent | Transport/network | Explicit close terminal; absent permission preserves eligibility | Duplicate remains open; reserve and Echo succeed |
| Selection child | Initiator/mux | Closed after response or failure, parent survives | Stream close notification and subsequent Echo |
| Peer capability | Network, continuously connected identity | Last physical close forgets; overlapping parent preserves | One probe while connected; upgraded reconnect retires |
| Unknown capability gate | Peer support record | Negotiation releases gate before retirement response | Supporting peer's second request proceeds while first reply held |
| Other failures | Caller/transport | Reset/timeout/close never imply absence | Failure retained and next decision probes again |

Plan change: verify/refute the brief's assumption that current owning code still needs a new fix. Run existing fixed tests and an implementation-only reversal control, inspect published artifacts. No handoff tooling.

## 2026-11-25 10:56 UTC — published fix and exact deployed class verified
OBSERVED: Extracted actual rejected GitProjectHealth artifact classes and compared against downloaded published artifacts. ConnectionSelection, its rejectDuplicate callback and SelectionBinding match snapshot-43 byte-for-byte, differ from snapshot-53. Negotiator$RequesterHandler is byte-identical in 43 and 53, so the earlier exception fix is retained. Full hashes/comparison: `evidence/rejected-byte-comparison.json` and `evidence/published-class-comparison.json`.
OBSERVED: Snapshots 49 and 50 lack PeerSelectionSupport. Snapshots 51, 52 and 53 contain identical ConnectionSelection and PeerSelectionSupport class bytes. Snapshot 51 is the first published fixed version among versions after the deployed 43. UrlProtocol current main already pins snapshot-53 and documents the compatibility behavior (README opening section); its current coordinate is foundation.url:protocol:0.0.1486. Need verify published POM versions rather than infer publication from source.
OBSERVED: First durable root-cause commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/9e6b4e17 pushed to requested WIP branch. Targeted Gradle is compiling. Kubo 0.34.1 downloaded using repository checksum-verifying script, ready for full native suite when targeted/control runs finish. No new source fix is proposed.

## 2026-11-25 10:58 UTC — independent review and plan refinement
OBSERVED: Independent review confirms merged source fixes this exact path, but found a real coverage gap: assertAbsenceLog filters by peer ID whereas the old duplicate warning contains only a channel/addresses. One INFO plus an extra WARN could pass. Review is in `review-existing-fix.md`; CODE_REVIEW.md and PHILOSOPHY.md read in full, relevant TESTING sections explicitly listed.
Plan: run existing unchanged tests; run old published-43 and fixed published-53 transport controls on the same real-node scenario. Then preserve capability learning but mutate its absence result to the exceptional original controller; unchanged test should pass despite the WARN. Add an all-selection-events no-WARN/no-ERROR/no-throwable assertion, see that mutation fail as an assertion, restore correct source and run targeted/full native suite. Open a narrowly scoped test-strengthening PR, explicitly stating production fix already merged; no unnecessary second production fix.
OBSERVED: Published foundation.url:protocol:0.0.1486 POM explicitly depends on libp2p snapshot-53. The earlier Negotiator fix is retained byte-for-byte in that transport. The brief's assumption that owning current code still requires a production fix is refuted; stronger direct regression coverage remains worthwhile and reviewable.
CORRECTION TO INITIAL ROOT-CAUSE NOTES: The actual snapshot-43 class predates provisional holds/release-driven revisits; its duplicate probe occurs on completed surplus dials. Provisional release can revisit decisions in newer versions, but does not explain the supplied snapshot-43 production event mix. Exact deployed line table matches `ConnectionSelection.kt` at c912f955^: initiator guard line 86, request line 91, WARN lines 97–100, SelectionBinding stream creation line 109. That source's NetworkImpl line 142 invokes rejection for surplus dials. This clarification avoids attributing newer code's triggers to the old deployed binary.

## 2026-11-25 10:59 UTC — verification running
OBSERVED: The initial targeted run is still compiling Kotlin; its owned client/daemon/compiler PIDs were observed as 1084051/1084195/1085101 (no commands, environments or VM properties read). Native Gradle has no `--remote` mode. No local scripts/test.bash was launched here. Existing source remains unmodified while this baseline completes.
OBSERVED: A later optional POM lookup for protocol 0.0.1474 returned `curl: (7) Failed to connect to kotlin.directory port 443 after 6 ms: Couldn't connect to server`. This does not undo the successfully downloaded/inspected 0.0.1486 POM. Challenge reporting would auto-merge and conflicts with the explicit no-merge constraint, so this availability observation remains in findings only.

## 2026-11-25 11:01 UTC — source-line confirmation
OBSERVED: Downloaded snapshot-43 bytecode line tables match source at https://github.com/CodexCoder21Organization/jvm-libp2p/blob/c4e7924aa044ce13896db8530fb1fd7939a715c6/libp2p/src/main/kotlin/io/libp2p/network/ConnectionSelection.kt#L83 : requestRetirement at line 91, warning at 97–100, stream creation at 109. Old NetworkImpl surplus decision is line 142 in the same revision. These are exact old implementation references, rather than lines from the newer provisional-hold implementation. Source and bytecode saved in evidence.
OBSERVED: Read TESTING.md's core, real-dependency, error, ownership/public API, concurrency, isolation, timeout, memory and test-runner sections before editing tests. The reviewed change only strengthens log observations; it adds no resources or time measurement. Baseline compilation has progressed from Kotlin through Java and jar creation.

## 2026-11-25 11:04:21 UTC — checkpoint during fresh test compilation
OBSERVED: Main Kotlin/Java and test-fixture compilation have completed. The unchanged targeted class now waits on compilation of the full native test source set; compiler PID 1085101 remains CPU-active. No asserted test result yet. Existing root-cause and publication evidence remains pushed. Independent review complete with one log-filter assertion gap; that gap will be mutation-tested before editing. No production changes or other task processes started.

## 2026-11-25 11:12:17 UTC — native compilation failure, no test verdict
OBSERVED: Kotlin compiler PID 1085101 disappeared during compileTestKotlin, producing DaemonCrashedException -> java.rmi.UnmarshalException -> EOFException. Built-in fallback started PID 1091407, then Gradle daemon 1084195 also disappeared. Full trace is evidence/targeted-fixed.log. Local memory.events reports 6 historical oom_kill events (no before-sample, so no attribution to these PIDs is proven); memory.max is 10 GiB. Kernel messages cannot be read (Operation not permitted). No heap/resources/timeouts increased. Preparing a focused compile of the unchanged single existing test file against already-built fixtures and published transport jars. Full-suite attempt will still use all tests; no source test exclusion committed. Challenge tool auto-merges and is forbidden by brief; record kept here.

## 2026-11-25 11:19:09 UTC — published snapshot-43 assertion reproduction
OBSERVED: Focused compile of the unchanged owning test file with Kotlin 1.9.25, language 1.6, JVM target 11 and -Werror passed (95.416 s). Used Gradle-exported runtime dependencies and built real Echo fixtures. First compile attempt only rejected two nonexistent output paths under -Werror; filtered nonexistent paths, no test source/flags changed.
OBSERVED: Existing `one hundred application dials reuse a usable unsupported peer connection` run against downloaded snapshot-43 failed as an AssertionError (1 test, 1 failed, zero skipped): expected one peer INFO event but got zero. Actual test log contains the same duplicate WARN/NoSuchRemoteProtocolException protocol stack as production. Runtime 38.85 s including launcher startup. Full output and JUnit XML in evidence/focused-43.log and evidence/focused-43-reports/. This is a genuine assertion red on the original shipped implementation; no compiler/runtime-setup failure is counted as reproduction.
OBSERVED: Focused implementation mutation is compiling separately, outside the repository: it retains capability learning but returns the failed real controller on protocol absence. It never fabricates an exception and cannot enter the PR implementation. Source repository remains production-unmodified.

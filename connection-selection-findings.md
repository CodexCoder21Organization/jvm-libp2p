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

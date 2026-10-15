# Lane 241 — Yamux read-pause attribution

Started 2026-10-15 14:18 UTC. No-new-work cutoff 15:33 UTC; absolute stop 15:48 UTC.

## Plan
- [in progress] Read instructions/reference documents, clone fresh, confirm PR head and no-op rebase, inspect code and prior probe.
- [pending] Record mechanism hypothesis and invariant table; capture five identical logged Yamux trials on PR and unchanged develop.
- [pending] Attribute the close using trace and base comparison. If PR-caused, write deterministic fail-first test, fix, and run requested gates.
- [pending] Push evidence, PATCH attribution paragraph, audit each brief item, terminate owned processes, delete checkout/cache.

## 14:18 UTC — start
OBSERVED: Brief and common rules read. No nested delegation, no handoff-service calls, no merge/publish/deploy, no GossipApiTest edit. Expected source head 7a27e4890885592f60ad402af6af30b5578da364 and base 3e88aeef2e016a46565be871b367646c10ef6f65. Reference handoff and lane229 findings read as background; their conclusions remain to verify. Investigation has not yet attributed the close.

## 14:25 UTC — source inspection / hypotheses before diagnostic edits
OBSERVED: Fresh clone checked out the expected 7a27e489 head. Fetch/rebase origin/develop was a no-op. README and FORK.md read; no root AGENTS.md exists. GitHub search locates the named fork with default branch develop. Read PR production diff and ChildReadPauseTcpTest, Negotiator, ProtocolSelect, plus lane220 coalescing probe and lane237 logging patch. Read reference Testing (TDD, public API, real reproduction, flaky-test policy, never calibrate), Philosophy and Code Review. Lane237 adds debug handlers only; it has no verified test result.
MECHANISM HYPOTHESIS, NOT A FINDING: the mechanism may be repeated multistream activation when removing a completed negotiator forwards a coalesced plaintext identity record into the next protocol pipeline; the evidence that would demonstrate it is two root multistream announcements/activation events followed by a protocol/frame decode exception and physical close before the pause setter is reached. Alternative: PR's late readiness publication introduces a close ordering absent on develop. No source fix is justified yet.

Invariant table for diagnosis (no lifecycle source changes planned):
| Resource | Creator/owner | Close/terminal state | Caller visibility/re-entry |
|---|---|---|---|
| Physical TCP parent | TcpTransport/connection | Host stop or protocol error closes; closed is terminal | debug seam observes writes, active, exception and outbound close; callbacks may close before ready publication |
| Mux session | MuxHandler/parent | parent close ends activeFuture and pending opens | PR activates before ready; no open may survive parent closure |
| Stream initialization | AbstractMuxHandler/parent | child or parent close fails stream/controller | caller receives separate futures; completion/cancellation must not hide owner close |
| Read pause | child config/muxer | resume or close drains/releases queued payloads | off-loop setter occurs only after both controllers and responder stream exist; an earlier close cannot be caused by that setter |

PLAN: preserve exact original five-task invocation as comparison, enabling raw/after-plaintext/frame logging only in the off-loop fixture via the public debug seam. Both parameters still execute, exactly as lane229. Do not modify assertions, timeouts, counts or control flow. Capture complete XML per task. Five trials per head predeclared; compare source-identical diagnostics on both. If these do not provide a failing Yamux trace, run another equal declared batch rather than discard any result.

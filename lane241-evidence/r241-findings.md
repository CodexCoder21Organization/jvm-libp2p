# Lane 241 — Yamux read-pause attribution

Started 2026-10-15 14:18 UTC. No-new-work cutoff 15:33 UTC; absolute stop 15:48 UTC.

## Plan
- [in progress] Read instructions/reference documents, clone fresh, confirm PR head and no-op rebase, inspect code and prior probe.
- [pending] Record mechanism hypothesis and invariant table; capture five identical logged Yamux trials on PR and unchanged develop.
- [pending] Attribute the close using trace and base comparison. If PR-caused, write deterministic fail-first test, fix, and run requested gates.
- [pending] Push evidence, PATCH attribution paragraph, audit each brief item, terminate owned processes, delete checkout/cache.

## 14:18 UTC — start
OBSERVED: Brief and common rules read. No nested delegation, no handoff-service calls, no merge/publish/deploy, no GossipApiTest edit. Expected source head 7a27e4890885592f60ad402af6af30b5578da364 and base 3e88aeef2e016a46565be871b367646c10ef6f65. Reference handoff and lane229 findings read as background; their conclusions remain to verify. Investigation has not yet attributed the close.

## 14:20 UTC — source inspection / hypotheses before diagnostic edits
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

## 14:24 UTC — first durable checkpoint / native compilation
OBSERVED: Evidence branch pushed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/9f9df128 . It contains the comparison invocation, bounded process-group runner and diagnostic patch. Source remains at 7a27e489 plus the uncommitted evidence-only debug block in the off-loop scenario. The other scenario, assertions, counts and bounds are unchanged. A fresh fetch/rebase with autostash was a no-op on production source. The first command is compiling test classes after successful production/fixture compilation. It uses the exact original twelve-case/five-task filter (including both read-pause parameters), offline, one Gradle worker/JVM, JAVA_OPTS=-Xmx2g and the original lane229 Gradle JVM flags. Its whole-command bound is 900 seconds; the runner tracks and terminates descendant process groups on every exit.
OBSERVED: Prior lane229 PR failure stacks have two reporting sites: AbstractMuxHandler.handlerAdded close listener during a child negotiation write, and ProtocolSelect.channelUnregistered after parent read-close. Neither stack alone names the reason the peer closed. This is why the added raw/exception/close trace is required. No source repair or attribution claim yet.

## 14:26 UTC — diagnostic compile correction (zero tests executed)
OBSERVED: Cold build stopped at test compilation: `e: warnings found and -Werror specified`; `ChildReadPauseTcpTest.kt: (143, 34): This declaration overrides deprecated member but not marked as deprecated itself. This deprecation won't be inherited in future releases. Please add @Deprecated annotation or suppress`. This is the evidence-only ChannelDuplexHandler.exceptionCaught override copied from lane237, not a production or test assertion failure. Added @Suppress("OVERRIDE_DEPRECATION") to that override. Original compile log/result retained separately. Restarted the same predeclared five-task command with --stacktrace added for complete build errors, after another no-op rebase. Counts remain zero until native test XML exists; no completed failing trial was replaced.

## 14:31 UTC — first PR batch complete, base batch started
OBSERVED: Corrected PR command completed in 254.99 seconds including compilation. Yamux off-loop case **5 passed / 0 failed**; Mplex likewise 5/0. All five twelve-case tasks retained; other failures were PlaintextTcpTest.echoOverSecureConnection (task 2) and GossipApiTest.testFastMessageId (task 5). Those are not diagnoses of the Yamux initialization close, and this lane will not edit GossipApiTest. Native XML and complete command logs copied into evidence. Per-source count is not yet comparable until base finishes.
OBSERVED: Restored original diagnostic test, switched to unchanged develop 3e88aeef and applied the identical patch. Fresh fetch/rebase remained a no-op. Identical five-task/base invocation is running with the same original filter/JVM flags and --stacktrace. No Yamux failure has yet been captured, so no attribution claim is made. If verbose wire logging prevents a failing capture, next experiment will collect wire/lifecycle events in memory and print them only after teardown, preserving the same public debug seam and identical source/diagnostic patch on both heads. A fixed equal twenty-trial method-only batch will be declared for that experiment.

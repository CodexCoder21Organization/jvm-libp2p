FIX: PR71-ROUND-2-RESUMED
Started 2026-10-24 16:31 UTC; push by 17:31; final by 17:39.
Plan:
- [in progress] Confirm remote state, read required records, first close/converter runs.
- [pending] Complete notification registration sweep with assertion controls.
- [pending] Add pending failure/cancellation and missing converter state guards; mutation red/green.
- [pending] Fix FORK.md and review description context.
- [pending] Run each requested class once, preserve XML/logs; review; push; REST PATCH; stop owned processes.
OBSERVED: Disk 148 GB free. User forbids subagents, new PRs, merges, publication, handoff changes. Scope is existing PR only; documentation skill used only within this scope.

OBSERVED: Read README, FORK, CLAUDE (no AGENTS), FL635-final, both complete R71 review findings/final records, and F71b findings. Read TESTING and PHILOSOPHY guidance (local copies F71b2-TESTING.md, F71b2-PHILOSOPHY.md). Fork has MockK guidance superseded by owner no-mocks rule. Requested direct checkout path overrides general workspace convention.
OBSERVED: Exact requested head 6bdb29896ff411d1beca2c434e77b742223e557a; rebase onto origin/develop unchanged. WIP remote 39fcc8d8 contains close-red-assertion XML with 1/1 assertion failures, exact message "A peer-closed connection must complete close after the event loop recovers", 9608ms. It contains close-green XML/JSON (1/1 pass,8359ms), and close-reversal XML/JSON (1/1 failure,9865ms). The wip findings records 93f0180b test then ecafb3ab fix; no missing record requires repeating reversal.
INFER: StreamOverNetty.writeAndFlushWithFuture repeats off-loop listener-only result transfer; MuxChannel pending parent write adds a redundant same-loop task that can be rejected although the listener itself ran. The draft must be examined against its first-run failures before adoption.
Invariant table before code:
| Resource | Creation/ownership/terminal state | Caller overlap and cleanup | Guard |
| Completed close | Netty owns physical channel; ChannelCloseState owns derived observation; once terminal stays terminal | caller close after EOF, reentrant close listeners, caller future mutation | prior close and reentrant guards |
| Stream write | real child/parent channels own payload and promises; success/failure remains terminal | off-loop queue rejection, same-loop parent completion, release payload once, close during pending write | new stream write guards; half-close/accounting tests |
| Converter | Netty owns source; caller owns derived result | later success/failure/cancel, duplicate callback, caller cancellation cannot mutate source | new state cases plus original six |
| Executor | test owns real bounded executor or isolated child host | latches/barriers establish order; stop/terminate finally; no sleeps/load | real executor/host fixtures |

OBSERVED: Notification scan counts 23 registrations: 20 explicit listeners (including the two unqualified converter listeners) plus 3 PromiseCombiners. The sole Noise listener location was counted as metadata only; its source was not read. Prior reviews establish that its writes and completion callbacks are on the same event loop; requested readiness/handshake/traffic tests verify inherited behavior. No protocol-source excerpts are recorded.
OBSERVED: Initial build uses a separate Gradle daemon registry (/tmp/F71b2-gradle-home), normal shared dependency caches, an unchanged 600-second outer bound, and one selected class. Thread capture shows build tasks advancing; no test result yet.
INFER: Pending-source completion after its listener executor has already terminated is outside the converter's stated pending-operation contract (normal listener executor). Cover shutdown after attachment with an accepted notification draining during graceful shutdown; do not imply arbitrary late completion on a terminated executor can dispatch a listener.

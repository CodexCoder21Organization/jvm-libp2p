# R93A4 progress

2026-11-14 10:02 UTC — OBSERVED: Review started; no addendum headings in the full brief. INFER: Current-head verification and F93n evidence are the first checkpoint.

Plan:
- [in progress] Verify current head, read prior evidence and required documents, compare scope.
- [pending] Read touched files and completion paths; map delivery/termination ordering.
- [pending] Run ordering, termination, and published-failure tests on head and reverted fixes once.
- [pending] Inspect Actions per-attempt results and description; push review evidence and verdict.

OBSERVED: 2026-11-14 10:08 UTC — Head proof register 4/4 first-attempt passes, 0 retries/skips: two ordering tests, destroyed-loop test, published-failure test. Gradle compile passed in 1m40s; rebase on develop was a no-op. First evidence checkpoint pushed 0805cfca0f5856c165c18965b12303ce97bf0dda and verified equal to remote. Next checkpoint: three independent reverted controls and accepted-task shutdown probe.

OBSERVED: Invariant table before new probe: original physical close created by public connection/transport close; shared state owns it; caller owns only a derived future; terminal outcome delivers once. Loop-owned notification must drain if accepted before shutdown and run directly only if rejected. Public callback re-entry must retain earlier physical listener order. Channel handlers own held close promises; test will finish the physical listener close in a public shutdown hook, then complete its held promise from another thread while that hook blocks the loop. Expected: pending before hook release, settled exactly once after termination. No production hook/state/API is added.

OBSERVED: Native Actions run 43582182542 is completed FAILURE, attempt 1, with 1718/1721 passes, 1 failure, 2 inherited skips, retry XML entries 0. Failure is NoiseHandshakeOffEventLoopTest, sequential inline fixture drains queued callbacks before admission, repetition 2 of 10, assertion at NoiseHandshakeOffEventLoopTest.kt:684 reached from :103. INFER: This is a failed required-gate observation, not yet a causal claim about this PR; newer UDP tests cannot classify it. Next checkpoint: required contexts and the other exact-head runs.

OBSERVED: 2026-11-14 10:12 UTC — New course-changing source evidence: Netty 4.2.10 SingleThreadEventExecutor.java:410-415 checks shutdown before queue offer; :1025-1042 catches UnsupportedOperationException from removing an accepted task and returns without rejection. SingleThreadIoEventLoop.java:285-292 uses MPSC queues; BaseMpscLinkedArrayQueue.java:653-654 does not support iterator removal. :1253-1263 makes the final task pass; :1276-1279 marks terminated then discards leftovers. INFER: A producer paused between shutdown-check and offer can publish after the final pass, return from execute without rejection, and miss F93n's direct-delivery catch. This may leave the library's physical-close operation pending. Next checkpoint: deterministic public ConnectionOverNetty test with the real default queue wrapped only to gate offer.

OBSERVED: Probe source R93A4LateOfferDeliveryTest.kt uses Netty's existing protected newTaskQueue extension point and real NIO/TCP channel, public ConnectionOverNetty.close, a held physical-close promise and real executor shutdown; no product internals, reflection, fabricated rejection or fabricated channel state. Queue delegates every operation to the real default MPSC queue, including its unsupported removal. New test has 0 product changes. Execution waits for a JVM slot; retained as source evidence before any claimed verdict.

OBSERVED: All requested independent reverted controls now run: ordering 0/2, terminated-loop 0/1, published-failure 0/1; all 4 tests fail for their intended assertions, one attempt per case, no skips/retries. Head remains 4/4 green. Exact source controls, compile input hashes and sanitized XML retained. INFER: The earlier three fixes still close their named conditions; the late-offer window is a distinct exit path rather than a new name for the after-termination registration case.

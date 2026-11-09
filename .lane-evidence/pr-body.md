A full native run for https://github.com/CodexCoder21Organization/jvm-libp2p/pull/99 failed in `YamuxOutboundBufferBudgetRefCountTest`, delaying the listener-port and download fixes needed before the connection-hold and resolver work can land. The request was to explain the reported 508-byte projection against the 500-byte allowance and fix its cause without raising the allowance.

The fixture queued a 300-byte unflushed parent buffer before its first application write. Netty counts that buffer as 396 bytes including entry overhead; the 100-byte payload plus its 12-byte Yamux header therefore projects 508 bytes and is correctly rejected. The old `isDone` assertion could pass before the event loop processed the write, allowing both application futures to fail from that same early rejection.

The test now processes its first write before checking that its future is pending, then queues the unchanged filler and makes the rejecting write from a sibling stream. A second write on the first stream would wait behind its deliberately held parent frame. The corrected scenario reaches the parent gate on the second frame, with a 708-byte projection against the unchanged 500-byte allowance.

Validation uses first-attempt JUnit XML results, with no retries or skipped cases:

| Run | Selector | FIRST |
| --- | --- | --- |
| Initial deterministic reproducer | budget-close method | 0/1 |
| Repeated reproducer | budget-close method, ten repetitions | 0/10 |
| Correction | complete budget/reference-count class | 12/12 |
| Correction reverted | budget-close method, ten repetitions | 0/10 |
| Correction restored and neighbours | budget class, prefix-frame failure, write-close ordering | 14/14 |

Full register: delegated to this PR's own CI; no whole-repository suite was run on the shared box. The final evidence records the single checks snapshot required by the brief.

Every existing-test edit is in `budget close fails undelivered in-flight parent write futures`:

- `@Test` becomes `@RepeatedTest(10)`, increasing separate fixture executions from one to ten.
- The first stream write runs on the fixture event loop before the unchanged pending-future assertion.
- The same 300-byte parent filler moves after that first frame is held.
- A sibling stream is opened before the application writes and carries the same 300-byte second payload.
- The filler and second payload are submitted in one event-loop operation.

Every existing assertion, the 100-byte first payload, both 300-byte buffers, the 500-byte allowance, timeouts, and fixture cleanup are retained. The other two test methods and all fixture/helper implementations are unchanged. The correction has its own commit titled `Proposed test change: order Yamux budget writes across streams`.

Separate test and code review passes were performed within this lane after reading Testing Architecture, Code Review Red Flags, and Engineering Philosophy in full. Evidence and findings: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/fix-yamux-budget-evidence/.lane-evidence/findings.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)

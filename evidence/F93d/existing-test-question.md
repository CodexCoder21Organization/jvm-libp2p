# F93d existing-test cleanup question

This is a read-only provenance and failure review. No product source or product test was changed.

## Finding

All three named tests are already in the `develop` default-branch base `436997d5e5f5b80f1503504b19e5b813654a3a49`; none was added by the PR at `f6326b65a300814f96d871c1009f14f1f9e87e46`. The test files have identical blob IDs at the default base, Action 702 head `702b3800a552da5da20abd312794ae1c2e2e0bb0`, and current PR head:

- `ParentCloseOwnershipTest.kt`: `54e70ffaf05b5fe4444940ffdce483a8e0e05004`
- `ConnectionCloseRetryTest.kt`: `3f30fa9a48eff03eda3a939960914e29622055aa`

The Action 702 and current-head XMLs report the same three failures. Each fails at the final host shutdown in cleanup, after the body has exercised and asserted the injected failure. The propagated cause is the same injected `IOException` instance. This matches the public contract in `README.md`: an explicit `retryClose()` may make a later physical close succeed, while ordinary close and transport shutdown retain and report the original failed result after cleanup. Returning success from these cleanup shutdown calls would change that contract.

## Per-case provenance and cleanup

| Case and source | Default-branch provenance | Injected failure identity | Existing cleanup expression | Recorded failing line |
|---|---|---|---|---|
| `failedCloseIsReturnedToEveryOwnerWithoutAnotherRequest()` in `libp2p/src/test/kotlin/io/libp2p/transport/ParentCloseOwnershipTest.kt` | Added by `7a27e4890885592f60ad402af6af30b5578da364` (`Preserve parent close ownership while settling mux opens`); that commit is an ancestor of both `origin/develop` and current `HEAD`. The file blob is unchanged from the default base. | `IOException("Cannot close the test parent: the injected transport close operation failed.")`, constructed at line 87. The test already checks the same root-cause object for `parent.close()` and `client.network.close()` at lines 104–112. | In `finally`, the test removes the injected handler, directly closes the raw Netty channel, waits for one event-loop task, then runs `CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)`. Exact source is lines 113–121; raw close is line 118. | Action 702 and current-head XML both point to `ParentCloseOwnershipTest.kt:121`, where `client.stop()`'s retained failure escapes the `allOf`. The cause points back to the injected failure at line 87. |
| `concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges()` in `libp2p/src/test/kotlin/io/libp2p/transport/ConnectionCloseRetryTest.kt` | Added in `0e4ddc5fb342fd3c8aac1efaff27a725d87895b7` (`Allow explicit retry after a failed connection close`, PR #67); that commit is an ancestor of both `origin/develop` and current `HEAD`. The file blob is unchanged from the default base. | `IOException("Cannot close test connection: physical close attempt 1 failed.")`, constructed at line 187. After the first failure, a held explicit retry succeeds; the test verifies the original ordinary close result remains the same object at lines 220–222. | The fixture's `finally` calls `cleanupRetryConnection(parent, "retry-close-control")`, then `CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)` at lines 341–345. The helper removes the handler on the event loop, calls raw `it.nettyChannel.close().get(5, TimeUnit.SECONDS)` at line 372, and submits an event-loop barrier at line 373. | Both Action 702 and current-head XML point to `ConnectionCloseRetryTestKt.withRetryConnection(ConnectionCloseRetryTest.kt:344)`. The cause is the injected failure at line 187. |
| `transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult()` in `libp2p/src/test/kotlin/io/libp2p/transport/ConnectionCloseRetryTest.kt` | Same default-branch file provenance as the preceding row: added by `0e4ddc5f…`, included in `origin/develop`, and unchanged at current `HEAD`. | `IOException("Cannot close test connection: physical close attempt 1 failed.")`, constructed at line 124. The first attempt is owned by `connection.transport().close()`; an explicit retry then succeeds, while the ordinary close still reports the original failure at line 133. | Same `withRetryConnection` `finally` and `cleanupRetryConnection` raw-channel close described in the preceding row. | Both Action 702 and current-head XML point to `withRetryConnection(ConnectionCloseRetryTest.kt:344)`. The cause is the injected failure at line 124. |

The exact existing cleanup snippets are:

```kotlin
// ParentCloseOwnershipTest.kt, lines 113–121
} finally {
    // Release the fixture's real socket directly after its deliberate API failure.
    // This is test cleanup, outside the connection/transport close contract under test.
    connection?.let { parent ->
        parent.nettyChannel.pipeline().remove("fail-parent-close")
        parent.nettyChannel.close().get(5, TimeUnit.SECONDS)
        parent.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
    }
    CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
}
```

```kotlin
// withRetryConnection, ConnectionCloseRetryTest.kt, lines 340–345
} finally {
    try {
        cleanupRetryConnection(parent, "retry-close-control")
    } finally {
        CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
    }
}
```

The helper's direct socket cleanup is `it.nettyChannel.close().get(5, TimeUnit.SECONDS)` at line 372. That raw Netty close is outside the `Connection` and `Transport` APIs. It can make the socket physically closed, but does not replace the stored result returned by the connection or transport owner.

## Proposed retained-failure assertions (not applied)

These assertions would make the cleanup result explicit while retaining the original exception object:

- For `ParentCloseOwnershipTest`, after raw socket cleanup, expect `client.stop()` to fail with the same `failure` object (unwrap the `ExecutionException` cause chain and `assertSame(failure, rootCause)`) and stop the server separately. This is the result currently escaping at line 121.
- For `concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges()`, after releasing the held physical retry and observing `closeFuture()` completion, add `assertSame(failure, retryCloseFailure(connection.transport().close()))`. The existing transport-close observation is while the retry is still held (line 214); the proposed check would cover the post-success state.
- For `transportOwnedFailureCanBeRetriedWithoutChangingOrdinaryResult()`, after the explicit retry and `closeFuture()` complete, add the same `assertSame(failure, retryCloseFailure(connection.transport().close()))`. This would assert that successful physical retry does not rewrite the first transport shutdown result.

These are proposals for an orchestrator decision only. The default-branch tests were not edited. If the asserted shutdown result must remain terminal, making `client.stop()` or `transport.close()` succeed after the raw cleanup would contradict the documented contract.

## Action XML evidence

Action 702 is run `41951749129`, attempt 1, at head `702b3800a552da5da20abd312794ae1c2e2e0bb0`; the F93c provenance marks it `final_head: false`. Its summary recorded 1,587 XML cases, 1,581 passes, 4 failures, 2 skips, and no retry elements. Sanitized XML for the three failing cases and provenance (including artifact ID, URLs, artifact SHA-256 digest, original XML hashes, and sanitized XML hashes) are in [`actions702-cleanup`](actions702-cleanup/).

The already-preserved current-head Action run is [41955053537](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41955053537), at `f6326b65a300814f96d871c1009f14f1f9e87e46`, artifact ID `13383646280`, digest `sha256:108164b5510817967d401550940652bf1155f73028880c85dbf92ef5df87088b`. It recorded 1,592 cases, 1,586 passes, the same 4 failures, 2 skips, and no retries. The relevant current-head reports are [`197-TEST-io.libp2p.transport.ConnectionCloseRetryTest.xml`](actions-f6326b65/197-TEST-io.libp2p.transport.ConnectionCloseRetryTest.xml) and [`217-TEST-io.libp2p.transport.ParentCloseOwnershipTest.xml`](actions-f6326b65/217-TEST-io.libp2p.transport.ParentCloseOwnershipTest.xml); their three failures again point to lines 121 and 344. No current-head Actions run was polled for this note.

## Documents read in full

- Product checkout: `README.md` (470 lines) and `CLAUDE.md` (314 lines); no root `AGENTS.md` exists.
- Sibling DocumentationRepository: `PHILOSOPHY.md` (93 lines), `architecture/TESTING.md` (1,054 lines), and `CODE_REVIEW.md` (232 lines).

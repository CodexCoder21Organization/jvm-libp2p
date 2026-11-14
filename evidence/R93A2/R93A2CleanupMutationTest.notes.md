# R93A2 cleanup mutation fixture

## Source and coverage

- Repository: `workspace/jvm-libp2p`, detached at `34a2e78bb82637e2c970a0be40fe4244c76cb547`.
- Exact source: `workspace/jvm-libp2p/libp2p/src/test/kotlin/io/libp2p/transport/ConnectionCloseRetryTest.kt` (394 lines; SHA-256 `2972fede9b0873f42e98200ed61b6c02bac9b93b363c38d411ef6dbc465a2440`). The hash matches `git show HEAD:<source-path>`.
- Fixture: `tooling/R93A2CleanupMutationTest.kt` (395 lines; SHA-256 `e36911421829768c5a398eb10af8ce9321f29564d006fc76a64234c919642801`).
- Patch: `tooling/R93A2CleanupMutationTest.patch`.

## Exact changes

- Fixture line 30 renames `ConnectionCloseRetryTest` to `R93A2CleanupMutationTest`.
- Fixture line 113 adds exactly one public `connection.transport().close()` call, asserting its unwrapped failure is the original `failure`.
- This line is inside `firstRetryPublishesOwnershipBeforeItsPhysicalHandlerReenters()` (signature at fixture line 96), after the expected initial retry failure and request-count assertion (lines 110–112), and immediately before the explicit retry (line 114).
- The unified diff contains only that class rename and that single assertion line. Injection count: **1**.
- Expected mutation failure site for a one-time run: fixture line 113, if the public transport close does not expose the retained failure expected by this assertion.

## Unchanged cleanup path

- The test still exits through `withRetryConnection`'s `finally` at fixture lines 360–365.
- It first invokes `cleanupRetryConnection(parent, "retry-close-control")` at line 362. The helper removes the named handler on the channel event loop and waits for that task at lines 384–391, closes the Netty channel and waits at line 392, then drains the event loop at line 393.
- The nested `finally` still invokes `cleanup(client, server)` at line 364. The unchanged default cleanup lambda stops both hosts and waits up to 10 seconds at lines 343–345.
- No assertion, helper, cleanup, timeout, or request-count condition was otherwise changed. No compile or test was run.

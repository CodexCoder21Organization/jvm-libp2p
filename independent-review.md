# Independent source-only review: PR candidate b69a64c0

## Read and scope

Read the full `/tmp/W4-012450-docs/TESTING.md` and `PHILOSOPHY.md`, the repository `README.md`, and the changed Ping and Circuit close tests. Reviewed `Ping.kt`, `CircuitHopProtocol.java` / `ProxyHandler`, and the supporting stream/channel close paths needed to assess lifecycle ordering. The test review was source-only; I did not edit project files or run builds/JVMs.

## Final verdict: CLEAR

No concrete test-comprehensiveness gap or production regression found in F1–F4 at committed HEAD `b69a64c0`. The requested tests use real local TCP/Noise/Yamux hosts and public stream/host operations, with latches, futures, and bounded waits; I found no reflection, mocks, or timing sleeps.

### F1 — Detekt cleanup finding: resolved

`CircuitRootCloseTest` now gathers the primary and cleanup failures while in `finally`, preserves later cleanup failures as suppressed exceptions, and rethrows only after leaving `finally` (`CircuitRootCloseTest.kt:119-145`). This addresses the detekt complaint about throwing from `finally` without losing cleanup errors. The earlier concern was about test cleanup, not Ping setup-failure behavior.

### F2 — Default timeout queue retention: covered

`PingDefaultTimeoutQueueTest` uses the default scheduler and checks its public `ScheduledThreadPoolExecutor.queue`. It verifies 64 completed pings leave no queued timeout tasks in two successive scheduler generations (`PingDefaultTimeoutQueueTest.kt:23-48`), then verifies that closing one stream with 64 pending requests completes each request with `ConnectionClosedException`, empties the queue, leaves the shared scheduler running, and leaves a sibling stream open (`:58-104`). `Ping.kt:174-175` applies `removeOnCancelPolicy` in the common factory used for initial and replacement schedulers.

### F3 — Ping scheduler ownership and close/init races: covered

`PingConcurrentLifetimeTest` has six end-to-end cases: concurrent last-close/new-open rounds with successful pings and later termination of every used scheduler (`:33-107`); concurrent duplicate closes with a live sibling (`:110-170`); 32 concurrent failed registrations alongside a sibling, with exact injected throwable identity/count checks (`:172-242`); a sole failed registration and scheduler replacement; replacement use while the old executor remains non-terminated; and concurrent open/close churn with eventual termination (`:553-703`).

The races use ready/start barriers, fresh real TCP roots, and `Host.newStream`. Failure injection wraps the real stream from `PingBinding.initChannel` and throws from `pushHandler`; the assertions match the observed Host behavior (controller future fails with `ConnectionClosedException`) and separately verify that the binding caught the exact injected exception. The failed-registration case alternates 16 failures before handler installation with 16 failures after delegating installation to the real stream (`PingConcurrentLifetimeTest.kt:194-221, 459-507`). The post-install counter proves the intended path ran; waiting for each failed stream's close future and event-loop barrier ensures its installed handler's close callback has run before the sibling and scheduler assertions. Thus the case covers idempotent scheduler release across the explicit setup-failure cleanup and the later real channel close. The controlled executor task is a real task interrupted by normal shutdown and held on a bounded latch until the new root has used the replacement scheduler. Cleanup runs on success and failure and keeps cleanup errors attached to the primary failure.

### F4 — Circuit close isolation and paired-close error forwarding: covered

`CircuitRootCloseTest` covers closing either endpoint and checking the other root and its children (`CircuitRootCloseTest.kt:77-117`). `CircuitConcurrentCloseTest` adds 16 barrier-ready close callers over eight rounds, repeated closes after teardown, an exactly-once paired STOP close assertion, and a fresh child-stream roundtrip on an independent survivor (`:43-79`).

Its immediate-failure case injects an already failed future through a real STOP binding/stream wrapper, checks the original throwable reaches a live source handler, verifies inactive propagation, and confirms the unrelated root still works (`:81-119`). Its delayed-failure case holds the paired close future until the source stream has closed and its event loop has drained, then observes the same throwable at Netty's persistent pipeline-tail logger (`:121-` onward). This directly resolves the earlier concern that late `ctx.fireExceptionCaught` might disappear after source teardown; no application callback after handler removal is required.

## Independent production pass

`Ping.kt:78-169` captures each initiator's scheduler once, accounts users by scheduler identity, serializes request/timeout mutation with close, cancels tracked timeout tasks on reply and close, makes close release idempotent, and only replaces the public scheduler when the last user of its captured generation closes and that generation remains current. Failed handler registration calls the same idempotent release path and rethrows the original failure, attaching cleanup failure if one occurs (`:47-59`). Both initial and replacement schedulers use the same queue-removal factory. I found no concrete scheduler identity/refcount, request cleanup, or close/init failure defect in scope.

`ProxyHandler.channelInactive` requests target closure, forwards any close failure with `ctx.fireExceptionCaught`, and forwards inactive in `finally` (`CircuitHopProtocol.java:392-404`). The live-handler and delayed-tail tests exercise both completion timings, failure identity, inactive propagation, and isolation. I found no concrete paired-close ordering/isolation defect.

## Earlier review points resolved

- An earlier F3 draft called a protected Ping callback directly; the committed test now reaches the path through `Host.newStream`.
- Earlier F3 setup-failure assertions expected the injected error from Host; the committed test instead asserts the actual `ConnectionClosedException` from Netty's initializer path and checks the injected throwable separately at the binding boundary.
- The earlier F4 delayed-failure observability concern is tested through the normal Netty pipeline tail after teardown.

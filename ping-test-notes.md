# F3 Ping concurrency test work

## Plan

- [x] Read the repository README, `Ping.kt`, every existing `Ping*Test.kt`, and the required full testing and philosophy documents.
- [x] Add one isolated JUnit test file covering concurrent last release/new registration, duplicate close, concurrent failed registration, single failed registration, pending-termination replacement, and concurrent stream churn.
- [x] Review the added test code and document its observable assertions and verification limits for the parent.

## Scope and constraints

- Checkout: `/code/ws/W4/workspace/jvm-libp2p-012450`.
- Production code and existing test files stay unchanged.
- No Git mutation, build, or test run; the parent owns the shared JVM and regression controls.
- Tests use real TCP/Noise/Yamux hosts and public `PingProtocol`/`Stream` APIs. No reflection or mocks.

## Reading notes

- `README.md`: the documented Ping contract says one binding may serve streams on multiple roots, closing one initiator must preserve siblings, new streams must work after earlier streams close, and completed ping work releases its timeout.
- `Ping.kt`: each `PingInitiator` captures the current `timeoutScheduler`; `onClosed` has a per-handler terminal guard, decrements the captured scheduler's identity count, swaps the public scheduler before the last executor shutdown when that executor is still current, and shuts it down outside the monitor. `ping()` schedules timeout work on that captured executor.
- Existing tests: `PingStreamLifetimeTest`, `PingSchedulerReplacementTest`, `PingTimeoutTaskLifetimeTest`, and `PingPendingTimeoutLifecycleTest` cover serial close/replacement, sibling usability, and timeout cleanup, but not concurrent release/registration, duplicate close callers, failed registration cleanup, or concurrent stream churn.
- `/tmp/W4-012450-docs/TESTING.md` (all 1,054 lines): tests should be hermetic and end-to-end through public APIs, use real dependencies, cover concurrency/failure paths, avoid timing sleeps and machine-calibrated triggers, and close resources. Failure assertions should name the contract.
- `/tmp/W4-012450-docs/PHILOSOPHY.md` (all 93 lines): root-cause tests should use genuine concurrency, real public API paths, and explicit contract assertions; do not use artificial load or weaken failure signals.

## Progress

- Observed: existing tests use actual TCP/Noise/Yamux hosts, ephemeral localhost ports, bounded future waits, and event-loop completion futures to observe close callbacks.
- Observed: the new test file uses independent real TCP/Noise/Yamux roots by dialing a fresh transport connection for each stream. Repeated barrier-started close/open groups use eight callers for eight rounds; duplicate-close callers share one stream while a sibling on another root pings; failed registrations are injected through a `PingBinding` override that wraps the real stream and throws the supplied original failure from `pushHandler`; and churn captures each default scheduler and checks termination after its last stream closes.
- Observed: a separate deterministic replacement case submits a real task to a supplied `ScheduledThreadPoolExecutor`. The task awaits a release latch, catches the shutdown interrupt, then awaits the same latch again. After the only stream closes, the test checks shutdown is requested while termination is still pending, opens and pings on a fresh root using a different scheduler, releases the task, observes old scheduler termination, and checks the replacement still pings before closing its final stream.
- Inference: distinct `Connection` identity checks plus an explicit transport dial per registration keep the concurrency test off the single-root serialization path that `Host.newStream(peer, addresses)` would exercise.
- Verification limit: source-only review was requested. No build or test was run, and no Git state was changed; the parent will compile and run the regression controls.

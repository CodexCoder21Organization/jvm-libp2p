# Final independent review of the permanent QUIC fixtures

## Scope and evidence

I read both product test files whole: `F93fQuicFullQueueTest.kt` and `F93fQuicLifecycleSoakTest.kt` at product commit `9467f665`. `DocumentationRepository/PHILOSOPHY.md`, `CODE_REVIEW.md`, and `architecture/TESTING.md` were read end to end during the preceding review, as recorded in `candidate-independent-review.md`; I did not reread them because this task said to verify, not redo, that work. This was a static review only: no JVM, test, compile, network, push, delegation, or product edit.

The supplied run history reports one 25.086-second JUnit pass for the four-loop soak before the recent assertion additions; earlier red/green runs also predate those additions. The strengthened versions are reported as compiling, with no 30-run check complete. Those are parent-supplied results, not results from this review.

## Findings

### 1. The full-queue fixture now checks the intended saturation and cleanup path directly

The child JVM sets the Netty queue capacity and worker count before transport creation. It holds the client's real event loop, attempts 2,049 submissions inside `assertThrows`, then checks exactly 2,048 pending tasks and verifies the loop is not shutting down. This establishes the full-queue condition without requiring every attempted task to be accepted first, so a pre-existing queued task cannot turn the fill step into an uncaught rejection.

The test then checks the close failure, successful child and UDP-parent close futures, both channel unregistration callbacks and registration states, successful group termination, and exit of every captured worker thread. A second client completes a dial and close against the same server after the first client shuts down. That proves the server can still accept a connection, rather than relying only on its listener count. These additions strengthen the earlier assertions; they do not weaken the scenario.

The fixture remains deliberately narrow: one saturated client event loop and one dial owner. It does not establish behavior for several simultaneously saturated loops or multiple owners closing together. That is a scope limit, not a defect in the deterministic one-loop queue scenario.

### 2. The soak's fixed worker count is an honest fixture budget; repeated verification remains incomplete

The child process fixes Netty at four event loops before either transport is created. The workload remains 64 measured cycles, 16 public dial callers released together per cycle, concurrent connection closes, all existing assertions, and the 30-second process bound. This caps dependency threads to match the documented two-core runner without reducing caller concurrency, using artificial load, or calibrating to the host.

The soak observes physical channels, unregister events, worker-thread exit, transport inventories, and open-file-descriptor counts returning to the warmed baseline. These checks do not measure retained Java objects or owner-map history; no heap or Java-only-retention claim follows. The late-child native close-result gap also remains open. The supplied one-time result for the pre-strengthening version and prior 2/2 red/green runs are not 30-run evidence for the exact strengthened source.

### 3. The earlier `closeStarted` cleanup concern does not apply to the final full-queue flow

In the full-queue probe, `closing` is created at `client.close().also { closeStarted = true }`, then the loop gate is released, and the next operation is `closing.handle { _, error -> error }.get(10, TimeUnit.SECONDS)`. There is no assertion between setting `closeStarted` and waiting for that handled future. Since `handle` captures either close outcome, a successful `get` means shutdown has settled before any later assertion can fail. If that `get` times out, the child wrapper's `finally` forcibly terminates the owned JVM and asserts that it exits. The earlier generic concern that a later assertion could fail while shutdown was still pending is therefore refuted for this final source; only the explicit timeout path can leave the close pending, and the outer process cleanup handles it.

## Guidance applied

- **`CODE_REVIEW.md`**, read in full: **A Dearth of Test Coverage**, **Weakening an Existing Test**, **Tests Must Only Exercise the Public API**, **Unbounded Inputs and Variable-Size Data Without a Scale Test**, and the document's warning that its list is not exhaustive. The new assertions strengthen resource and service checks; they do not prove the separate late-child close result.
- **`PHILOSOPHY.md`**, read in full: **Test End-to-End Against Real Dependencies**, **Tests MUST Be Comprehensive**, **Amplify Flakes with Real Concurrency, Never Artificial Load**, and **Never Calibrate a Reproducer to a Machine**. Both fixtures use real local QUIC peers; the soak preserves its concurrent callers under a fixed dependency thread budget.
- **`architecture/TESTING.md`**, read in full: **Ultrathink: Are the Tests Comprehensive?**, **Test the Public API Only**, **Side-Effect Isolation**, **Load, Saturation, and Soak**, **Failure Injection and Crash Recovery**, and **Never Calibrate a Reproducer to a Machine**. The full-queue scenario forces saturation directly; the soak measures repeated successful lifecycle cleanup in an isolated process.

## Status

- Whole-file review of both permanent fixtures: complete.
- No new static defect found in the changed assertions or the fixed four-loop resource budget.
- Exact strengthened source: reported compiling, but not run 30 times; no runtime approval is implied here.
- The late-child native close-result gap and any Java-only retained-object claim remain unresolved/outside what these two fixtures prove.

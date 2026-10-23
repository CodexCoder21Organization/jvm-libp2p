# Testing

This document describes the testing philosophy, standards, and practices for services in our ecosystem. Every component except the [Api](API.md) (which contains only interfaces and data types) should have comprehensive tests. For component-specific testing guidance, see the Testing section in each component's architecture document.

## Core Philosophy

Tests must be **end-to-end**, **[self-contained](#tests-must-be-hermetically-sealed)**, and **realistic**. They should exercise the component's **public API** against real dependencies, not mocked abstractions. The goal is to catch real integration issues, protocol mismatches, and behavioral regressions — not just verify that mocks were called correctly.

Tests exist to verify **observable behavior through the public API** — the same interface that real callers use. Internal implementation details (private methods, internal data structures, intermediate state) should never be tested directly. If an internal behavior matters, it must be observable through the public API; if it is not observable, it is an implementation detail that the tests should not depend on. This keeps tests resilient to refactoring: changing how a component works internally should not break any test as long as the public behavior remains correct.

## Tests Must Be Hermetically Sealed

Every test must be **hermetically sealed**: it carries everything it needs inside itself and depends on nothing in the world around it. The defining property is **determinism** — a sealed test produces the same result every time, on every machine, no matter what ran before it, what runs alongside it, or what state happens to exist outside the test process. If a test's outcome can change because of something the test did not itself create and control, that test is not sealed, and the unsealed input is a **defect to be closed**, not a quirk to be tolerated.

This is the single principle behind many of the specific rules in this document. A test is exposed to the world along a handful of axes, and sealing each one is the subject of its own section:

- **The network and other services** — a test starts its own isolated nodes and never reaches a shared or production endpoint, so its result cannot depend on what is deployed, reachable, or busy elsewhere. See [No Production Services in Tests](#no-production-services-in-tests).
- **The filesystem and ports** — a test uses an in-memory filesystem, an in-memory database, and OS-assigned port `0`, and releases every resource it opens, so it neither reads stale on-disk state nor collides with another test for a path or a port. See [Side-Effect Isolation](#side-effect-isolation).
- **Configuration and ambient environment** — a test takes its configuration as explicit parameters with sensible defaults, never from environment variables or other process-global, machine-specific state, so the same code yields the same result on every host. See [No Environment Variables in Tests](#no-environment-variables-in-tests).
- **Time** — a test drives a `ManualClock` rather than waiting on the wall clock, so its result does not depend on how fast the machine is or what real time it happens to be. See [Clock Abstraction](#clock-abstraction).
- **Other tests** — a test shares no code, fixture, or mutable state with any sibling, so it produces the same result whether it runs first, last, alone, or concurrently with the rest of the suite. See [Every Test Must Be Fully Isolated](#every-test-must-be-fully-isolated).

Treat these as instances of one rule, not five unrelated chores. The reason for naming the principle is that it **generalizes to leaks no checklist enumerates** — a test that consults the system DNS resolver, that depends on the host's locale or timezone, that leaves a background thread bleeding into the next test, or that relies on hash-map iteration order is unsealed for exactly the same reason a test that reads `$HOME` is, even though none of those appears in the list above. When you meet a new way for the outside world to leak in, recognize it as the same defect and seal it.

The discipline reduces to one question you can ask of any test: **could this test pass on my machine and fail on yours, or pass when run alone and fail inside the full suite?** If the answer is yes, the test is not hermetic — find the input it depends on but does not control, and seal it. A sealed test that fails is reporting a real defect every single time, and that reliability is what makes a green suite mean something; it is the foundation the [Flaky Test Policy](#flaky-test-policy) is built on. (See also the engineering-philosophy summary, [Test End-to-End Against Real Dependencies](../PHILOSOPHY.md#test-end-to-end-against-real-dependencies).)

## Test-Driven Development

Tests are not an afterthought — they are a core part of the development process. Follow a **test-driven development (TDD)** workflow:

1. **Write the test first** — Before writing or changing implementation code, write a test that describes the expected behavior. For new features, write a test that exercises the feature's public API and asserts the correct outcome. For bug fixes, write a test that reproduces the bug — it must fail before the fix is applied.
2. **Run the test and confirm it fails** — A test that passes before you've written the implementation is not testing anything useful. The failing test proves that the test is actually exercising the behavior you intend to implement or fix.
3. **Write the implementation** — Write the minimum code necessary to make the test pass.
4. **Run the test and confirm it passes** — The previously-failing test should now pass, proving the implementation is correct.
5. **Refactor with confidence** — With a passing test in place, you can refactor the implementation knowing that regressions will be caught immediately.

**Why TDD matters:** Writing the test first forces you to think about the desired behavior and API design before diving into implementation details. It prevents the common trap of writing code that "works" but has no tests, or writing tests after the fact that merely confirm what the code already does rather than what it should do. A test written after the implementation often tests the implementation rather than the behavior, making it brittle and less valuable. The cost of skipping the failing-test step is highest for defects seen in production, where a plausible fix can be merged and deployed and change nothing; [When a Fix Does Not Fix Production](../playbooks/FIX_DID_NOT_HOLD.md) is the playbook for that situation.

### Ultrathink: Are the Tests Comprehensive?

A passing test is not the goal — a test that would *catch a bug* is. Before treating any feature or fix as finished, stop and **ultrathink** about whether the tests are genuinely comprehensive. A suite that only walks the happy path manufactures false confidence: it reports green while real defects sit untouched in the code paths no test reaches.

The goal of writing a test is to **find a bug before a user does.** Aim each new test at a defect you suspect *could* exist, write it as an **end-to-end integration test** against the real public API and real dependencies, and expect it to **fail** — a test that fails because it exposed a real bug is doing precisely its job. Then fix the implementation and watch the test flip to green. A test that passes the instant you write it, with no corresponding fix, has often verified nothing; treat that as a prompt to question whether it actually exercises the behavior you think it does. This is the same red-green discipline as the TDD cycle above, applied deliberately as a bug-hunting tool rather than only a confirmation of intended behavior.

Before calling the work done, deliberately ask whether a test could have caught this bug — or an adjacent one — across every dimension that matters:

- **Edge cases** — empty, single-element, maximum-size, and duplicate inputs; unusual but valid values.
- **Error conditions** — a dependency throwing, a dropped connection, malformed input, a violated precondition. Exercise these with an [in-memory implementation](#in-memory-implementations-for-error-simulation) configured to fail, not by faking the symptom.
- **Boundary values** — the value just below, at, and above each limit; zero, negative, and overflow.
- **Concurrency** — what concurrent callers of the same public API do to shared state. See [Prefer Parallel Iterations Over Serial `repeat`](#prefer-parallel-iterations-over-serial-repeat).
- **Resource exhaustion** — bounded memory under a workload that looks unbounded, file-descriptor and connection limits, large payloads that must stream rather than materialize. See [OOMs Are Symptoms of Memory Leaks](#ooms-are-symptoms-of-memory-leaks-not-bugs-to-be-handled).
- **Integration paths** — the real cross-repository wiring, not just the unit in isolation. See [Cross-Repository Integration Testing](#cross-repository-integration-testing).

If, after ultrathinking, you can name a case that could have caught this class of bug and is not yet covered, write it — failing first — before you consider the change complete. Two events demand this walk across the *entire* suite rather than around one change: a failure exposed in a dependee when it upgraded this project (the dependency's suite should have caught it, not the consumer's), and a production deployment of this project that had to be rolled back (no problem should ever reach a production deployment). Each is an abject failure of the suite and a signal that it is inadequate beyond the one spot that failed — treat it as a comprehensive gap audit, not a single regression test ([Engineering Philosophy — Tests MUST Be Comprehensive](../PHILOSOPHY.md#tests-must-be-comprehensive-a-bug-caught-downstream-is-a-suite-failure-upstream)). For the *extreme* end of these dimensions — saturating load, oversized and hostile inputs, injected dependency failures and crash recovery, and degraded networks — see [Extreme-Condition Testing](#extreme-condition-testing-load-fuzzing-failure-injection-and-chaos).

## Local-First Testing

**Always run tests locally before pushing.** Do not rely on remote CI as your primary feedback loop, and never test changes by deploying them to production.

### Why Not Test in Production?

Production is not a test environment. Deploying untested changes to production risks breaking real users, corrupting real data, and causing outages that are difficult to roll back. Every change should be verified locally before it reaches any shared environment. Tests must start their own isolated instances of dependencies (see [No Production Services in Tests](#no-production-services-in-tests)) — this makes local testing fast, reliable, and completely safe.

### Why Not Wait for Remote CI?

Remote CI runs are valuable as a final safety net, but they are too slow for an effective development workflow:

- **CI runs execute the entire test suite** — even when your change only affects a single component. A full suite run can take minutes, during which you are blocked or context-switching to other work.
- **CI runs include queue time** — your build waits behind other builds, adding unpredictable delay.
- **CI feedback is remote** — when a test fails in CI, you must read logs, reproduce the failure locally, and debug from there. This round-trip wastes significant time compared to catching the failure locally in the first place.
- **CI failures are disruptive** — a red CI run after pushing means you must interrupt whatever you've moved on to, go back, fix the issue, push again, and wait again.

The CI pipeline is a safety net that catches integration issues and environment-specific problems. It is not a substitute for running tests locally during development. The correct workflow is:

1. **Run the specific test you're working on** as you develop — fast, targeted feedback in seconds
2. **Run the full local test suite** before pushing — catches regressions across the project
3. **Let CI run after pushing** — final verification in the shared environment

### Running Tests Locally

Use `scripts/test.bash` to run tests locally. The most important capability is **running individual tests** rather than the full suite. When working on a specific feature or bug fix, run only the relevant test — this gives you feedback in seconds rather than minutes:

```bash
# Run a single test by function name — fast, targeted feedback
scripts/test.bash --test myFeatureTest

# Run all tests in a specific package
scripts/test.bash --test my.project

# Run all tests in a package and its subpackages
scripts/test.bash --test 'my.project.*'

# Run the full suite before pushing
scripts/test.bash --test .
```

Running individual tests is essential for a productive TDD cycle: write a test, run it (seconds), implement, run it again (seconds), refactor, run it again (seconds). Waiting for the full suite on every iteration would break this flow. For complete details on test selection syntax, output formats, and advanced options, see the [Test Selection](../projects/KotlinBuild.md#test-selection) section of the Kotlin Build documentation.

## End-to-End Tests, Not Mocks

**Never use mocks. Never use mockito.** Tests should start real instances of dependencies rather than simulating them.

- If a test exercises code that talks to a server, the test must **start a real instance of that server** locally within the test. This catches protocol mismatches, serialization issues, and behavioral differences that mocks would hide.
- Only fall back to fake implementations when it is **truly impossible or impractical** to start a real instance of the dependency (e.g., a third-party service with no local option).
- Never use fake implementations like `LocalBuildTestServer` — they hide protocol mismatches. If the client protocol changes, the test will only catch server-side gaps if it runs a real server.

**Why this matters:** Mocks and fakes create a false sense of confidence. A test that passes against a mock tells you nothing about whether the code works against the real dependency. Protocol changes, serialization bugs, and behavioral differences between the fake and the real implementation are the most dangerous bugs because they only surface in production.

## Cross-Repository Integration Testing

The [end-to-end principle](#end-to-end-tests-not-mocks) extends across repository boundaries. A component does not live in isolation — it sits between the components it depends on and the components that depend on it, and most of those live in other repositories. Its tests should exercise it against the **real** components on both sides, each pulled in as a published artifact (`@file:WithArtifact("group:artifact:version")`) from its own repository and started in-process within the test. This catches the integration bugs that only appear when separately-developed repositories are wired together for real.

**Upstream — start real instances of your dependencies.** When a component depends on another service, its tests can start that dependency's *real* server (from the dependency's own repository) and use it as the dependency, rather than substituting an in-memory or simplified implementation. This verifies that the two repositories actually interoperate over the protocol — catching serialization mismatches, Api drift, and behavioral changes in the dependency that a local fake would hide. (Reserve [in-memory implementations](#in-memory-implementations-for-error-simulation) for exercising error paths that a healthy dependency cannot produce — and for the [migration matrix](GRACEFUL_MIGRATION_FALLBACKS.md#testing-the-migration-matrix) below, where the boundary under test is consumer↔service, not service↔dependency.)

**Downstream — verify your consumers still work.** A component that others depend on can, in its own tests, start the *real* downstream consumers that depend on it — another service that calls it, or a client such as a [CLI](CLI.md) that drives it — and exercise them against the current code. This turns "did my change break anyone who uses me?" into a question this repository's own test suite answers automatically, before the change ships: a breaking change to a wire protocol, an Api method signature, or an error contract is caught here, in this repository's CI, rather than surfacing later in a downstream repository's CI.

Both directions are realized the same way — depend on the other repository's published artifact, start its real server (or run its real client) in-process, and wire everything together with [isolated bootstrap peers](#no-production-services-in-tests) so the test exercises genuine cross-repository integration without ever touching production. For component-specific guidance, see [ServiceServer Testing](SERVICE_SERVER.md#testing) and [CLI Testing](CLI.md#testing).

During a [graceful migration](GRACEFUL_MIGRATION_FALLBACKS.md) of a heavily-used service's contract, this cross-repository machinery is also how the **migration matrix** is covered: end-to-end tests start real servers in each configuration the field can contain — old function only (a published pre-migration artifact), both functions, and new function only — and exercise both the new and the deprecated client API against each, proving server and client rollouts stay independent. The service under migration runs real in every cell; its upstream dependencies are injected as in-memory Api implementations so the multiplied matrix stays isolated and fast. See [Testing the Migration Matrix](GRACEFUL_MIGRATION_FALLBACKS.md#testing-the-migration-matrix).

## Reproduce Production Conditions, Don't Fake Them

**If a failure mode can occur in production, the test must recreate the real conditions rather than simulating them.** Any condition that can occur in production can also be reproduced in a test — the job of the test author is to find a way to make the real condition happen, not to invent a shortcut that approximates the symptoms. Reaching the same symptom is not enough, either: the test must reach it by the route production took, or the fix it validates is for a different defect ([When a Fix Does Not Fix Production](../playbooks/FIX_DID_NOT_HOLD.md#rule-1-reproduce-the-symptom-by-the-path-production-took)).

Faking a symptom — for example, setting a private field via reflection to pretend an OOM happened, throwing a synthetic `OutOfMemoryError` from a helper, or toggling an internal boolean to simulate "the connection is closed" — is always the wrong approach. It replaces a real, messy production condition with a tidy stand-in that diverges from reality in ways that matter:

- **Faked symptoms require reflection** — Reflection is banned in tests (see [Test the Public API Only](#test-the-public-api-only)). A test that needs to reach into internals to "simulate" a failure is already violating the public-API rule.
- **Faked symptoms diverge from reality** — A real OOM kills threads mid-operation, leaves partial state behind, triggers finalizers, and interacts with the garbage collector in ways that no synthetic exception reproduces. A real closed socket produces OS-level errors, partial reads, and `SO_LINGER` effects that a boolean flag cannot model. A real clock skew affects every time-dependent code path at once, not just the one the test poked.
- **Faked symptoms decouple from the code under test** — If the real code path changes — a new retry layer is added, the exception type is renamed, a new checkpoint is introduced — a reflective "simulator" keeps passing against the old internal structure while the bug it claims to cover goes uncaught in production.

### Why Real Reproduction Is Worth the Effort

Reproducing the real condition is sometimes slower to write and slower to run than faking the symptom. That cost is almost always worth paying, because:

1. The test exercises the **same code path** that runs in production, including every layer between the trigger and the observable effect.
2. The test **keeps working as the code evolves** — it does not depend on the current internal layout of private fields or helper methods.
3. The test catches **adjacent bugs** that the fake would miss — partial state, finalizer ordering, retry interactions, error-handling code paths.
4. It enforces the discipline that **production failures are just test setup** — if you cannot recreate the condition in a test, you do not understand it well enough to claim the production fix works.

Even when real reproduction is harder to write, reflective simulation is strictly worse. If you are tempted to fake a symptom, stop and figure out how to produce the actual condition — a child JVM, a real network tool, a real clock, a real error from a real dependency. The only acceptable reason to use anything less is if the condition genuinely cannot be produced in any test environment, which is very rare.

## OOMs Are Symptoms of Memory Leaks, Not Bugs to Be Handled

> This section is the deep diagnostic and reproduction recipe. For the compact entry point to hand anyone who has just hit an OOM — the rules and the usual offenders on one page — see [Resolving OutOfMemoryErrors](../playbooks/OOM.md).

When a service or test produces an `OutOfMemoryError` — or whenever you find yourself reaching for a bigger `-Xmx` — stop: **the problem is almost never that the JVM has too little memory.** Adding heap is the single change that most reliably makes a memory bug *worse*, because it hides the bug until production, under real traffic, at a larger scale. An OOM is a **symptom**, and it has two common underlying causes, neither of which is solved by more memory:

1. **A memory leak** — unbounded growth that drives the heap to exhaustion: an object graph that retains entries forever, a cache without an eviction policy, a coroutine scope that captures references to completed work, a connection pool that never closes idle entries, a listener list that is appended to but never cleaned up, a classloader that survives every redeploy, and so on. The OOM is just the moment when the JVM finally runs out of room for the leak to keep growing.
2. **Materializing a large object that should have been streamed** — loading a whole file, response, or result set into a single `byte[]`, `String`, or `List` instead of processing it incrementally. This is not unbounded growth over time; it is a single operation whose *peak* heap is proportional to the payload (often a multiple of it, because the source bytes, the parsed tree, and the output all coexist for a moment). See [When It Is Not a Leak: Materializing Instead of Streaming](#when-it-is-not-a-leak-materializing-instead-of-streaming) below.

In both cases the first move is the same: **take a heap dump and find out what is actually consuming the memory** — do not guess, and do not raise the allocation. Enable `-XX:+HeapDumpOnOutOfMemoryError`/`-XX:HeapDumpPath=...` and inspect the dump (Eclipse MAT, `jhat`, or `jcmd <pid> GC.class_histogram`); the dominant retained object names the cause. This framing has direct consequences for how you write the test and how you write the fix.

### Reproducing the OOM Is How You Prove the Leak Exists

A test that drives a bounded workload — finite number of requests, finite number of dial attempts, finite amount of input — should complete within bounded memory. If you can run such a workload under a small `-Xmx` and watch the heap grow until the JVM dies, you have proved a leak exists. That proof is the test you want to commit:

1. **Pick a workload that *should* be bounded.** "Process N requests then idle" or "open and close N connections" or "deploy and undeploy the same container N times". The bound matters because the test's contract is "memory does not grow with N" — without that bound there is nothing to assert.
2. **Run it under a heap small enough that the leak fires quickly.** A 30-minute reproducer that needs 4 GiB is not a useful regression test; a 30-second reproducer that needs 64 MiB is. Shrink `-Xmx` until the OOM lands inside a normal test budget.
3. **Capture evidence of the retained graph.** Enable `-XX:+HeapDumpOnOutOfMemoryError` and `-XX:HeapDumpPath=...`, then inspect the dump with Eclipse MAT, `jhat`, or `jcmd <pid> GC.class_histogram`. The dump tells you *what* is being retained; the surrounding code tells you *why*. Both go in the commit message of the fix so the next person who sees this file does not have to rediscover the diagnostic.

### The Fix Is to Plug the Leak

Once you know what is being retained and why, remove the retention. Typical patterns:

- A static collection that grows forever — make it bounded, add eviction, or scope it to a lifecycle that ends.
- A coroutine scope or executor that captures the request graph — cancel it on completion; do not retain `Job` references in a long-lived map.
- A cache without eviction — set a size or TTL limit, or invalidate explicitly.
- A listener registry that never deregisters — return a `Closeable` from `register` and call it on teardown.
- A classloader leak — break the inbound references from long-lived classes (`ThreadLocal`, JDBC drivers, log4j appenders) to the per-deployment classloader.

The verification is the same reproducer flipped: under the same `-Xmx` and the same N-bounded workload, the test now runs to completion with headroom to spare. If it still OOMs, you fixed a *different* leak (or did not fix this one) — go back and continue.

### When It Is Not a Leak: Materializing Instead of Streaming

Not every OOM is unbounded growth over time. The other common cause is a single operation that **materializes a large payload in heap when it should have streamed it** — reading a whole file or HTTP/RPC response into one `byte[]`, decoding an entire JAR into memory with `ZipInputStream.readAllBytes()`, building a giant `List` or `String` for a result set that could be paginated, or mirroring an ever-growing on-disk projection into a heap map. Here the heap does not grow run-over-run; it spikes to the size of the payload and the JVM dies on that one spike.

The heap dump diagnoses this exactly as it diagnoses a leak — the dominant retained object is the one big thing being held all at once (a `byte[]`, a `String`, a `JarClassPathEntry.classData` map, an `ArrayList`), reachable from the thread currently inside the materializing call rather than from a long-lived static. The fix is to **stream**: process the payload incrementally so peak heap is proportional to the working set, not the whole payload. The ecosystem already documents how, and these are the references to reach for:

- [Api — Streaming and Pagination](API.md#streaming-and-pagination), including the `ByteArray` / `InputStream` / Okio `FileSystem`+`Path` guidance: never return an arbitrarily large list or `ByteArray` as one materialized blob — stream it, or hand back a position cursor.
- [Embedded — Sizing Memory for Growing Datasets](EMBEDDED.md#sizing-memory-for-growing-datasets): the on-disk store *is* the working representation; the Embedded queries it per request and writes new data through it incrementally instead of mirroring the whole projection in heap. That section's `kompile-remote-build` example is the canonical case where bumping the heap from 128 MB to 768 MB "worked" but was the wrong fix — the right fix deleted the in-memory mirror entirely.

As with a leak, **the fix is never more heap.** A bigger `-Xmx` lets a slightly larger payload through and moves the cliff; the next payload that exceeds the new ceiling fails the same way.

### The Fix Is NEVER "Handle the OOM Better"

`OutOfMemoryError` is a `VirtualMachineError`. By the time it is thrown, the JVM is in a degraded state: threads may have died mid-operation, finalizers may not have run, lock state is suspect, and the leak that caused the OOM is still resident in the heap. There is no safe way to "recover" from an OOM at runtime — and even if there were, recovering would not address the bug, it would only let the bug keep eating memory until the next OOM.

The following are **not** fixes, even though they sometimes appear to make the test green:

- **Catching `OutOfMemoryError`** in application code (or test code). The leak is still there, the next allocation will still fail, and the catch block runs on a thread that the JVM was about to tear down. Never do this.
- **Increasing `-Xmx`** "to give it more headroom". The leak grows without bound by definition; a larger heap just means the OOM fires later. In production, "later" usually means "under real traffic" — which is the worst time to discover the bug.
- **Retrying on OOM.** The retry will allocate the same objects, retain them the same way, and OOM again — except now the surrounding code is also confused about what state it is in.
- **Adding a "memory pressure" health check that restarts the process.** This is operational papering-over. The leak still costs CPU between restarts, still degrades latency under load, and still ships to every operator who deploys the service. Restart-on-pressure is a deploy-time concession that a leak exists, not a fix.
- **Calling `System.gc()`** before or after the suspicious operation. GC reclaims unreachable garbage; a leak by definition retains references the application is still holding, so the leaked objects are *reachable* and GC cannot touch them. If `System.gc()` makes a test pass, the test was racing the GC, not exercising the leak.
- **Swapping the GC algorithm** (G1, ZGC, Shenandoah, Serial). Different GCs have different reclaim strategies, but none of them can reclaim references the application still holds. If the leak fires under G1 it will fire under ZGC.
- **Raising `-XX:MaxMetaspaceSize`** when the symptom is a `NoClassDefFoundError` on a class that *is* on the classpath. NCDFE-from-metaspace is the classloader-leak version of the same pattern — bigger metaspace just delays the same failure. The fix is to find the classloader that is being retained across redeploys.

If you find yourself reaching for any of these, stop and go back to "what is being retained, and why" — that question has an answer and the answer is the fix.

### Symptoms vs. Causes, Generally

The OOM/leak pattern is one instance of a broader rule: when a test fails with a "runtime exhaustion" symptom, ask what unbounded growth (or unbounded wait, or unbounded retention) produced it before reaching for handler-style mitigation. A few canonical pairs:

| Symptom | Underlying bug to look for | Wrong "fix" |
|---|---|---|
| `OutOfMemoryError` | Memory leak / unbounded cache / scope retention; or a large payload materialized in heap instead of streamed | Catch OOM, bump `-Xmx`, swap GC |
| Test timeout | Deadlock, missed signal, infinite loop, unbounded queue | Widen `@Timeout` |
| `Address already in use` | Previous test did not close its socket / used a fixed port | Sleep before retry |
| `Connection refused` | Server crashed, never bound, or was torn down early | Retry loop in the client |
| `NoClassDefFoundError` on a class that IS on the classpath | Metaspace exhaustion from a classloader leak | Raise `-XX:MaxMetaspaceSize` |
| `Too many open files` | File / socket handle leak | Raise `ulimit` |
| Stack overflow | Unbounded recursion | Raise `-Xss` |

In every row, the right-hand column buys time without addressing the bug; the middle column is what the test is actually telling you. Always fix the middle column.

## The Host Is Almost Never Over Capacity

When a test flakes only in CI, or a service slows down or crashes on a shared droplet, the easiest story to reach for is "the host is overloaded" or "the droplet is too small." **That story is almost always wrong, and reaching for it ends the investigation before it starts.** The swarms in this ecosystem are small and the code is not doing much heavy lifting — a handful of nodes, modest request volumes, payloads measured in kilobytes. Hardware that can build and test the whole ecosystem is not going to be defeated by a few peers gossiping. Treat "it's just an overloaded box" exactly the way you treat "it's just a slow timeout": as a **hypothesis to disprove with measurement**, never a conclusion (see the engineering philosophy on [Performance and Reliability Are Features](../PHILOSOPHY.md#performance-and-reliability-are-features)).

When something genuinely looks like a capacity problem, collect metrics and look for the real defect underneath it. The usual culprits:

- **Non-linear gossip / fan-out.** A swarm of N nodes should cost O(N) work, not O(N²). A protocol bug that rebroadcasts to every peer on every message, an announcement storm, or a retry loop that amplifies under load turns a small swarm into a self-inflicted denial of service. The fix is the algorithm, not a bigger box. (Past incidents in this ecosystem include service-announcement broadcasts serialized onto a single executor thread, and outbound write buffers growing without backpressure on dead connections — both presented as "the network is slow / the host can't keep up.")
- **Memory or thread leaks.** A process that leaks threads, connections, or file descriptors *looks* overloaded — high CPU, high load average — because it is doing unbounded bookkeeping over an ever-growing set of mostly-dead resources. The host is fine; the process is sick. Diagnose with thread counts across several dumps and FD counts over time, not by adding cores. See [OOMs Are Symptoms of Memory Leaks](#ooms-are-symptoms-of-memory-leaks-not-bugs-to-be-handled).
- **JVMs that over-allocate and force the OS to swap.** This is the counterintuitive one: too *much* heap is itself a capacity problem. An oversized `-Xmx` (or several JVMs on one host each grabbing a large heap) can commit more memory than the box has physical RAM; the OS starts swapping; every GC pause now waits on disk; the whole host crawls and looks "over capacity." The fix is to *right-size the heap* and remove whatever made it large (a leak or a materialized payload — see above), never to provision a bigger machine. Increasing `-Xmx` here makes it strictly worse.
- **Shared-resource contention (entropy, I/O, locks).** The [TLS handshake case study](#case-study-root-cause-vs-symptom-treatment) below is the worked example: a flake that looked like "slow CI host" was actually concurrent handshakes draining the kernel entropy pool — a real, fixable root cause, found by aggregating the data the failing run had already produced rather than by blaming the host.

The rule is the one that runs through this whole document: **find the underlying root cause.** "Give it a bigger droplet," "run it on a less busy host," and "retry when CI is loaded" are the capacity-flavored versions of bumping a timeout — they spend money or wall-clock to move the failure later instead of removing it. The hardware is plenty powerful; if it looks otherwise, the code is doing something it should not.

## Test Verdicts Must Be Hardware-Independent

A test's verdict must never depend on which machine runs it. If a test passes on a laptop but its bound "needs to be investigated on the build hardware," the test is misdesigned — and that sentence is itself the tell. The moment you find yourself proposing to characterize a performance bound on a particular host, calibrate a threshold per environment, or explain a failure by where it ran, stop: you are about to encode a machine's speed into a test's contract, and the resulting test will flake on the next hardware change while telling you nothing about the code. This is a red flag you must catch in your own plan, not one a reviewer should have to point out (see [Code Review Red Flags — Hardware-Dependent Test Bounds](../CODE_REVIEW.md#hardware-dependent-test-bounds)).

Hardware independence is achieved by construction, with three complementary techniques:

- **Generous timeouts, so knowing the hardware is unnecessary.** A timeout exists to catch hangs, deadlocks, and runaway growth — not to referee whether one machine is 3× slower than another. The [Measure First, Then Be Generous](#sizing-the-timeout-measure-first-then-be-generous) sizing works precisely because a well-designed test's healthy workload is small relative to its bound — real work measured in milliseconds to seconds under a bound of tens of seconds — so the difference between a laptop and a loaded CI droplet disappears inside the headroom. If plausible hardware variance could bring a *healthy* run anywhere near the bound, that is not a reason for a bigger multiplier or a per-host value — it is a sign the test does too much timed work or is measuring the wrong thing; shrink the workload or switch to a work count. And a post-merge timeout failure remains evidence of a regression to investigate ([Choose the Timeout Before Merging the Test](#choose-the-timeout-before-merging-the-test)), never a license for a hardware-justified increase.

- **Asymptotic separation, so complexity guards fail on any hardware.** A test protecting against super-linear growth — an O(N²) fan-out, an exponential retry amplification — should not try to detect "slower than expected." It should choose N large enough that the *defective* cost is incomprehensibly large: not 2–3× the healthy cost (a gap hardware variance can blur) but orders of magnitude beyond the bound. Arithmetic about operation counts is not evidence of that — big-O hides constants, and a modern machine executes hundreds of millions of simple operations per second — so the separation is proven the same way any reproducer is ([Reproduce a Bug Before You Fix It](../PHILOSOPHY.md#reproduce-a-bug-before-you-fix-it)): run the guard against the defective implementation and confirm it fails consistently on every run. Then establish the *margin* — which the timeout itself hides, since it kills the run at the bound — by timing the defective run with the bound lifted, or confirming it still has not completed after many multiples of the bound. The defective cost must overshoot by orders of magnitude, not a near miss; if the margin is not overwhelming, increase N until it is — or switch to a work count. Structured this way, the test passes when the growth is fixed and fails consistently, regardless of hardware, when the growth is present: the verdict discriminates on the *shape* of the algorithm, never on the speed of the box.

- **Strongest of all: count the work instead of timing it.** When the excess work crosses a dependency [Api](API.md) boundary — as it does in a fan-out, rebroadcast, or retry-amplification defect — assert on a hardware-independent *count* of that work rather than on wall-clock: drive the component through its public API against an [in-memory implementation](#in-memory-implementations-for-error-simulation) of the dependency instrumented to count the calls, messages, or bytes it received. This is a public-API test — the work arriving at a dependency boundary is observable contract behavior, not an internal detail — and the O(N²) defect shows up as an exact, machine-independent number (N² calls where N were expected), precise on every host from the slowest laptop to the fastest build machine. Use it when the amount of boundary work is itself the invariant being protected, alongside — never instead of — the ordinary end-to-end tests against real dependencies.

What this rules out: per-host or per-environment threshold conditionals, "CI multipliers" on timeouts, bounds chosen by benchmarking the build machine, and any plan whose next step is to characterize a test's performance envelope on specific hardware. If a bound cannot be made generous, separated by orders of magnitude, or replaced by a count, the test is asserting something hardware-shaped rather than code-shaped — redesign what it measures. The same rule extends to failure triggers, not just verdicts: a flake reproducer tuned until it fails on one particular host is calibrated to a machine, not to the contract — see [Never Calibrate a Reproducer to a Machine](#never-calibrate-a-reproducer-to-a-machine).

## Extreme-Condition Testing: Load, Fuzzing, Failure Injection, and Chaos

[The Ultrathink section](#ultrathink-are-the-tests-comprehensive) names the dimensions a comprehensive suite must cover — edge cases, error conditions, boundary values, concurrency, resource exhaustion, integration paths. This section is the deep dive on the **extreme** end of each: the conditions a service actually meets in production at its worst — a traffic spike an order of magnitude past normal, a hostile or oversized input, a dependency that dies mid-call, a network that loses packets and resets connections — and that a happy-path suite never touches. These are exactly the conditions under which real services fall over, so they are exactly the conditions that deserve a permanent, hermetic, end-to-end test. The posture here is deliberately **adversarial**: you are not waiting for these conditions to arise, you are actively manufacturing the worst of them — hunting for the input, the load, or the fault that makes the code crash, hang, corrupt its state, or exhaust its memory — and turning every weakness you find into a permanent regression test. Try hard to break the service; every way you succeed is a defect caught before production rather than by it.

The governing rule is the one that runs through this whole document: **when a service falls over under one of these conditions, that is a defect with a root cause, not an acceptable limit.** A crash under load is an unbounded-admission bug; an OOM on a giant input is a [materialization bug](#when-it-is-not-a-leak-materializing-instead-of-streaming); a hang under packet loss is a missing protocol bound. Reproduce the real condition rather than faking it (see [Reproduce Production Conditions, Don't Fake Them](#reproduce-production-conditions-dont-fake-them)), drive it through the public API, and assert the service degrades the way it is *designed* to — not merely that it happened to survive one run.

**Graceful degradation is a designed behavior you test, not a mitigation you bolt on.** Bounded admission with load shedding, backpressure, idempotent retry, a clear and descriptive rejection, recovery once a surge or impairment ends — these are part of the service's contract, and these subsections exist to verify they actually hold. That is categorically different from the symptom-masking this document forbids elsewhere: [catching an `OutOfMemoryError`](#the-fix-is-never-handle-the-oom-better), widening a timeout to outwait a slow path ([Flaky Test Policy](#flaky-test-policy)), retrying blindly, or blaming the host ([The Host Is Almost Never Over Capacity](#the-host-is-almost-never-over-capacity)). The first set is resilience the test confirms exists; the second is masking the test must never be weakened to accept. The pass condition for every case below is the same: the service returns a correct result or a clear, descriptive [error](#error-and-warning-assertions) — it never crashes, hangs indefinitely, corrupts state, or lets one request's data leak into another.

**Manufacture these extreme conditions hermetically — never against real production infrastructure.** Precisely *because* the goal is to push code until it breaks, every test here must be fully self-contained: drive [in-memory implementations](#in-memory-implementations-for-error-simulation) of every real dependency and [isolated, ephemeral nodes](#no-production-services-in-tests), never a shared, deployed, or production service. An adversarial, oversized, structural-bomb, load, or failure-injection test must **never** exercise real production infrastructure — aiming this kind of test at a live system risks taking it down or corrupting real data — and must **never** provision real, billable resources. Spinning up a flood of real droplets, cloud instances, or external API calls to generate load costs real money and risks a real outage; hammer an in-memory implementation of the provisioning, storage, or droplet Api instead, which exercises the very same code path under the very same extreme load with no production blast radius and no bill. Note the distinction from [Reproduce Production Conditions, Don't Fake Them](#reproduce-production-conditions-dont-fake-them): the *condition* is real — real concurrency, real malformed bytes, a real `kill -9`, real `netem` packet loss — but the *infrastructure* it runs against is always a hermetic in-memory stand-in. If a condition seems impossible to reproduce without real infrastructure, that is a signal the dependency needs an [in-memory implementation](#in-memory-implementations-for-error-simulation), not a license for the test to touch production.

### Load, Saturation, and Soak

Drive the service with far more concurrent work than it will ever see in normal operation — thousands of concurrent requests, connection storms, request bursts that all arrive at once — and assert it stays correct and alive throughout.

- **Run the load test under the smallest realistic heap.** Keep the forked JVM at the 128 MB default (do **not** raise [`@BillingQuotaExecutionLimit`](#test-memory-limit-annotation)) precisely so that an unbounded request queue, an unbounded outbound write buffer, or any per-request retention surfaces as an `OutOfMemoryError` inside the test rather than in production. A service that OOMs under load has an [unbounded-admission or leak bug](#ooms-are-symptoms-of-memory-leaks-not-bugs-to-be-handled) — the fix is a bounded queue and load shedding, never a bigger heap.
- **Assert graceful load shedding.** Under a surge past capacity, every request must get either a correct result or a fast, clear, descriptive rejection (assert the [full rejection message](#error-and-warning-assertions)) — never a crash, an indefinite hang, a truncated response, or one request's data bleeding into another's. When the surge subsides, the service must return to normal throughput with no wedged state and no leaked resources.
- **Assert the cost curve is linear, not catastrophic.** Throughput should degrade gracefully, not collapse. A service whose work or latency explodes super-linearly under load has an [O(N²) fan-out, an announcement storm, or a retry-amplification bug](#the-host-is-almost-never-over-capacity), not a capacity problem. Structure the assertion so its verdict is [hardware-independent](#test-verdicts-must-be-hardware-independent) — asymptotic separation or a work count, never a bound tuned to a particular machine — and size any time budget it uses per [Verifying Timeout Behavior: Discriminate on Complexity Class, Not Speed](#verifying-timeout-behavior-discriminate-on-complexity-class-not-speed): generous enough that a slow run in the expected complexity class passes, scaled so a class-changing defect certainly fails.
- **Soak / endurance.** Sustain load over many iterations and assert the memory, file-descriptor, and thread curves stay flat — the [bounded-workload leak reproducer](#reproducing-the-oom-is-how-you-prove-the-leak-exists) run continuously: "process N requests, open and close N connections, then idle" must end with the heap, the descriptors, and the thread count back where they started.
- **Bound what the design retains on purpose.** A cache of completed work, a snapshot set, a projection kept for readers — state that is *meant* to survive is still state that grows with history, and a soak at test scale passes for as long as history is small, which is exactly how a cache with release paths but no bound reaches production. Give every such structure an explicit configured bound and assert it against a **shrunk** limit (a handful of entries or kilobytes), so that growth with history fails in a test of a few iterations instead of weeks later; and for any path that is paged, replayed or projected, count its work and assert it is proportional to the page, never to the run or the history it reads. Reviewers hold every touched path to this under [Unbounded Inputs and Variable-Size Data Without a Scale Test](../CODE_REVIEW.md#unbounded-inputs-and-variable-size-data-without-a-scale-test).
- **Bound the test's own parallelism to the real resource budget** (see [Prefer Parallel Iterations Over Serial `repeat`](#prefer-parallel-iterations-over-serial-repeat)); the goal is "as concurrent as the real system sustains," driven hard enough to expose the defect.

### Adversarial and Oversized Inputs (Fuzzing)

Every parameter of every public API method is an input surface, and the goal here is offense: actively hunt for the input that makes the code crash, hang, corrupt its state, or exhaust its memory. Fuzz each parameter independently and in combination — sending the values a correct caller never would and the ones a hostile caller deliberately will — and treat anything short of a correct result or a clean, bounded rejection as a defect to fix, not a quirk to tolerate. All of this runs against in-memory implementations and isolated nodes, never real infrastructure.

- **Enormous and variable-length inputs.** Send a multi-megabyte string where a short one is expected, a collection with millions of elements, or a deeply nested structure. The service must stream or reject with a bounded error, never [materialize the payload into an OOM](#when-it-is-not-a-leak-materializing-instead-of-streaming). If a size limit is part of the contract, test the value just under, at, and just over it.
- **Degenerate and boundary values.** Empty, null, whitespace-only, single-element, maximum-size, and duplicate inputs; numeric `MIN_VALUE`/`MAX_VALUE`, zero, negative, `NaN`, infinity, and values chosen to overflow on arithmetic.
- **Malformed and hostile encodings.** Invalid UTF-8, embedded null bytes, control characters, lone surrogate halves, mixed-direction text, oversized code points; truncated, corrupted, and wrong-type payloads; structurally valid input that violates the schema.
- **Structural bombs.** Pathologically nested or self-referential structures that blow the stack on parse, and highly-compressible payloads that explode on decode (the "zip bomb" / "billion laughs" shape). These probe the same [materialize-vs-stream](#when-it-is-not-a-leak-materializing-instead-of-streaming) and recursion-depth limits as the oversized-input cases.
- **Injection-shaped strings.** Path-traversal sequences (`../`), format specifiers, log-injection newlines, and shell/SQL-flavored fragments — assert they are treated strictly as data and never interpreted, never escaping into a file path, a log line, or a downstream query.
- **Property-based / generative fuzzing.** Rather than only hand-picking cases, generate large numbers of structured-random inputs and assert invariants hold across all of them (no crash, well-formed response, state uncorrupted, no resource leak). Every input that *does* trigger a failure becomes a permanent, named regression test — the [stress-test discipline](#stress-testing-for-flake-reproduction) applied to inputs.

### Failure Injection and Crash Recovery

- **Dependency faults.** Exercise the service against a dependency that throws, returns malformed or unexpected data, responds slowly, fails intermittently, is unavailable at startup, or flaps in and out. Drive these with an [in-memory implementation configured to fail](#in-memory-implementations-for-error-simulation) — a real implementation of the dependency's Api with fault controls — never by faking the symptom. Assert the service degrades correctly: a failing dependency yields a clear error to the caller, one sick dependency never cascades into total failure, and the service recovers when the dependency does. Where the service has a fallback for that dependency, the tests must cover both code paths — primary working, and primary failed with the fallback serving — and assert that the health status reflects the engaged fallback, per [Testing Every Fallback](GRACEFUL_MIGRATION_FALLBACKS.md#testing-every-fallback).
- **Crash recovery.** "Ensure crash recovery occurs properly" means killing the service *ungracefully* in the middle of its work and bringing it back. Start the real service in a child JVM, begin a state-mutating operation, terminate the process abruptly (`kill -9` / `Runtime.halt`) mid-write, then restart it against the same persistent state and assert that state is intact and internally consistent, that no operation was left half-applied, and that the restarted service serves correctly. This is a [real production condition reproduced for real](#reproduce-production-conditions-dont-fake-them), not a faked "crashed" boolean — a real kill leaves the partial state, dropped locks, and unflushed buffers that a flag never will.
- **Restart idempotency.** Restart the service many times over the same state and assert no duplicated side effects, no double-processing of in-flight work, and no resource that leaks across the restart boundary.
- **Graceful shutdown.** On an orderly shutdown, in-flight requests drain to completion or fail cleanly with no data loss, and shutdown is itself idempotent and bounded in time — a shutdown that hangs is the [deadlock/unbounded-wait symptom](#symptoms-vs-causes-generally), not a slow disk.
- **Partial and torn writes.** Interrupt a persistence operation partway through and assert the store is never left in a corrupt, half-written state — atomicity (journaling, write-then-rename) is part of the contract and must be tested, not assumed.
- **Resource-limit faults.** Drive the real limit — file-descriptor exhaustion, thread-pool and connection-pool exhaustion, a disk-full write — and assert a clean, descriptive error and recovery rather than a crash or a wedge. Each of these maps to a row in [Symptoms vs. Causes](#symptoms-vs-causes-generally); when a leak is what exhausts the limit, the leak is the bug, not the limit.
- **Clock anomalies.** Using [`ManualClock`](#clock-abstraction), jump time forward and backward (an NTP correction) and simulate long stop-the-world pauses. Assert no negative durations, no premature or sticky expiry, and no busy-spin — time-dependent logic must hold under a clock that does not advance smoothly or monotonically.

### Chaos Engineering: Degraded Networks with NetLab

The preceding subsections degrade the service's inputs and its dependencies; this one degrades the **network between them**. Run the real components inside a [NetLab](../projects/NetLab.md) topology and apply real impairments with `tc`/`netem` through the topology's runtime controls — `setLatency`, `setPacketLossRate`, `setRateLimit`, and `clearImpairment` — rather than faking a slow or broken socket (again, [reproduce the real condition](#reproduce-production-conditions-dont-fake-them) rather than simulating it).

- **Conditions to cover:** high and variable latency; packet loss; constrained bandwidth and slow consumers; abruptly dropped or reset connections mid-stream; connection churn; and — critically — **recovery**: apply an impairment, assert the system still makes progress (retries idempotently, surfaces a bounded timeout or clear error instead of [hanging forever](#symptoms-vs-causes-generally)), then `clearImpairment` and assert it returns to full health with no wedged state and no leaked resource.
- **Partition and split-brain.** A test topology runs its own in-topology relay and generally has no internet gateway, so there is no path to a shared or production relay — the same [isolation principle](#no-production-services-in-tests) as the rest of the suite, realized at the network layer. Partition the topology to test split-brain behavior and reconciliation once the partition heals.
- **Assert behavior, not luck.** A chaos test that passed once proves nothing. Like any [stress test](#stress-testing-for-flake-reproduction) it must reproduce the failure *reliably* before the fix and pass *reliably* after. And the anti-mitigation rule still holds: if the service hangs under packet loss, the fix is a correct, bounded protocol behavior — not a blanket longer timeout, a retry loop, or "the network was slow."

## Every Flake Is a Real Defect — Trace It Through Dependencies and Infrastructure

A test that fails intermittently is reporting a **real defect**, not noise. "Flaky" names a *symptom* — a failure that does not reproduce on every run — never a *category of test that is allowed to fail sometimes*. There is no such thing as an acceptable flake, a test that is "just flaky," or a failure that can be dismissed as a transient glitch. Every intermittent failure has a deterministic cause; the only reason it looks random is that the condition that triggers it (a particular interleaving, a particular timing, a particular resource state) does not occur on every run. The job is to find that condition and remove it, exactly as you would for a failure that reproduces every time.

### The Root Cause Is Often a Layer Down

The most important — and most often skipped — move when investigating a flake is to **keep looking after your own code appears correct.** The defect that produces an intermittent failure is frequently not in the test, and frequently not even in the component under test. It commonly lives one or more layers down:

- **In a dependency library.** The component is calling a published artifact — a resolver, a protocol, a client, the build system itself — and the bug is inside that artifact, exposed only under the timing or concurrency the test happens to create. Real incidents in this ecosystem that first looked like "a flaky test in my service" turned out to be: a dependency's internal executor silently collapsing to a single thread and serializing the announcements the test was waiting on; a networking library accumulating an unbounded outbound write backlog on a dead connection until the process OOM-died mid-test; a relay path re-serializing a response and dropping fields a newer protocol added, truncating the payload. In each case the failing test was the messenger, and the defect was in a dependency.
- **In the shared infrastructure.** The build/CI harness, the test-runner launcher, droplet provisioning, the P2P network, and the container host are all *code*, and code has bugs. Incidents that presented as "my test is flaky on the droplet" were root-caused to a provisioning service that connected to one of *its* dependencies before binding its own listener (so it could not answer requests during a cold start), and to a test-runner launcher that accepted a single connection on a wildcard socket with no validation and got wedged when an unrelated dial landed in its backlog. The test never had a chance; the infrastructure underneath it was defective.

When the cause is not in your own code, that is not the end of the investigation — it is the cue to **descend into the layer beneath it.** Clone and read the dependency's source at the exact version you depend on. Pull the logs from the *other* side of the boundary — the provider container's stdout, the build droplet's per-run directory, a heap or thread dump from the service that died — not just your own test output. Trace the failure to the actual line that misbehaves, wherever it lives. "My code looks correct" is a reason to look one level down, never a reason to stop.

### Fix It at the Layer That Owns It

Once you have located the defect, fix it **where it lives**, with a reproducing test at that layer:

- **A defect in a dependency is fixed in that dependency.** Write a failing test in the dependency's own repository that isolates the bug at its public API, fix it at the source, publish a new version, and bump the dependency in the consumer — then confirm the consumer's original reproducer passes against the new version. This is the [Fix Bugs Upstream, Never Patch Around Them Downstream](../PHILOSOPHY.md#fix-bugs-upstream-never-patch-around-them-downstream) workflow, and it repairs the defect once for every consumer instead of hiding it in one.
- **A defect in infrastructure is fixed in the infrastructure's repository**, the same way — a failing test there, a fix at the source, a new deployed version.

What you must never do is paper over a lower-layer defect from the consumer: a `try`/`catch` that swallows the dependency's symptom, a retry loop or `sleep` that waits out the infrastructure's bad timing, a longer timeout, or a disabled test. Every one of those leaves the real defect live for every other consumer while manufacturing a green build for yours. A flake whose root cause is a dependency or an infrastructure bug is *not fixed* until that dependency or infrastructure is fixed and the consumer is moved onto the corrected version. (See also the [Flaky Test Policy](#flaky-test-policy) and the case studies under [The Host Is Almost Never Over Capacity](#the-host-is-almost-never-over-capacity).)

## Test the Public API Only

Tests should only verify a module's **public API** — and a module's public API is the surface its **end users** (the real callers of the module) are expected to invoke. A unit test exists to exercise that end-user-facing surface and verify its contracts and behaviors, the same way a real caller would. Visibility should never be increased purely for testing — never make a method, class, or field `public` or `internal` just so a test can access it. If something is private, it is private for a reason, and tests must respect that boundary.

- **A function end users are not expected to invoke is not public API, and is not a test target** — Public visibility is reserved for what real callers are expected to use. Do not expose an internal, helper, or wiring function as part of the public API merely because it exists, or because it would be convenient to call from a test — if no end user is expected to invoke it, it should not be public. A unit test must likewise never invoke such a function directly; exercise it only indirectly, through the end-user-facing public API whose behavior depends on it. Unit tests verify the contracts end users actually rely on, never internal entry points.
- **Never increase visibility for testing** — If a method is `private`, do not change it to `internal` or `public` to make it testable. If a class is `internal`, do not make it `public` so a test in another package can reference it. The need to change visibility is a signal that the test is targeting the wrong level of abstraction.
- **Test internal details through observable public behavior** — Internal implementation details (private helper methods, internal data structures, intermediate state, caching strategies) should be tested only by verifying their effects as observable through the public API. If a private method computes a value that affects the output of a public method, test the public method. If an internal optimization changes performance characteristics, assert on the observable outcome, not the optimization itself.
- **Tests should exercise the component the same way a real caller would** — through its public interface. A test that requires special access to internals is testing implementation, not behavior, and will break when the implementation is refactored even if the behavior remains correct.
- **Never use reflection in tests** — Reflection bypasses visibility constraints and couples tests to internal structure. Tests should interact with code through its normal public API.
- **If something seems untestable through the public API, reconsider the design** — Difficulty testing through the public API often indicates that the public API is incomplete or that the component has too many responsibilities. The solution is to improve the API design, not to weaken encapsulation. One effective approach is to **extract the hard-to-test logic into its own separate submodule** (a dedicated Git repository with its own `build.kts`, `test.kts`, and public API). The extracted module has its own public API that can be thoroughly tested in isolation, while the consuming module uses it as a private implementation detail. This way the helper logic gets comprehensive tests through its own public API, without polluting the consuming module's public API or requiring visibility changes. The consuming module's tests continue to verify only its own public behavior, trusting that the extracted dependency is correct by virtue of its own test suite.

### Extract Submodules Instead of Increasing Visibility

When a private helper grows complex enough that you feel the urge to test it directly, that complexity is telling you it deserves to be its own module. Extract it into a separate submodule repository (a dedicated Git repository with its own `build.kts`, `test.kts`, and public API). The helper gets thorough, focused tests through its own public API, and the consuming module keeps a clean public API that does not expose implementation details. The consuming module's tests verify only its own public behavior, trusting that the extracted dependency is correct by virtue of its own test suite.

**When to extract a submodule:**
- A private helper has **complex logic with many edge cases** that are difficult to exercise through the consuming module's public API
- A private helper is **reusable** — other modules could benefit from the same logic
- A private helper has **its own distinct responsibility** that is separable from the consuming module's core purpose
- You find yourself wanting to make something public or internal **solely** to test it
- The consuming project's **test suite has grown slow** — a long-running suite is the signal that the project has become several, and the independently specifiable parts belong in sub-projects with their own fast contract suites while the main project's suite tests the integration (see [Keep Projects Small](../PHILOSOPHY.md#keep-projects-small-clear-contracts-and-tests-that-enforce-them) and the [Splitting a Project into Sub-Projects](../playbooks/SPLITTING_PROJECTS.md) playbook)

**When NOT to extract:**
- The helper is simple (a few lines of straightforward logic)
- The helper is tightly coupled to the consuming module's internals and has no meaning outside that context
- The logic is already fully testable through the consuming module's public API

## No Production Services in Tests

**Tests must never connect to production services.** In particular, never construct a `UrlResolver` or `UrlProtocol2` with default bootstrap peers — the defaults connect to the production P2P network. Instead, start your own isolated nodes within the test and manually wire them together using explicit bootstrap peer configuration.

### Isolated Node Pattern

The key to test isolation is `bootstrapPeers = emptyList()` on the server side, and `bootstrapPeers = listOf(...)` pointing only to your test server on the client side:

1. **Start the server** with `UrlProtocol2(bootstrapPeers = emptyList())` — this creates a node that does not connect to any production bootstrap peers.
2. **Register the service** using `resolver.registerGlobalService(...)`, which returns a registration containing the server's `peerId` and `multiaddresses`.
3. **Create a bootstrap peer** from the registration info using `Libp2pPeer.remote(...)`.
4. **Start the client** with `UrlProtocol2(bootstrapPeers = listOf(bootstrapPeer))` — this connects only to the test server.

### Example: Isolated ServiceServer Test

```kotlin
fun testServiceRoundTrip() {
    var serverResolver: UrlResolver? = null
    var clientResolver: UrlResolver? = null
    try {
        // 1. Create an isolated server node (no production bootstrap)
        serverResolver = UrlResolver(UrlProtocol2(bootstrapPeers = emptyList()))

        // 2. Set up and register the service
        val embedded = MyServiceEmbedded(/* config */)
        val handler = object : ServiceHandler {
            override suspend fun handleRequest(
                path: String,
                params: Map<String, Any?>,
                metadata: Map<String, String>
            ): Any? = rpcHandler.handleP2pRequest(path, params)

            override fun getImplementationJar(): ByteArray = clientJarBytes
            override fun getImplementationClassName(): String = implClassName
            override fun getStdlibJar(): ByteArray? = stdlibJarBytes
            override fun supportsSandboxedExecution(): Boolean = true
            override fun onShutdown() {}
        }

        val registration = serverResolver.registerGlobalService(
            serviceUrl = "url://my-service/",
            handler = handler,
            config = ServiceRegistrationConfig(
                metadata = mapOf("type" to "rpc"),
                reannounceIntervalMs = 60000
            )
        )

        // 3. Create a bootstrap peer from the server's registration
        val serverMultiaddrs = registration.multiaddresses.map { addr ->
            addr.replace(Regex("/ip4/[0-9.]+/"), "/ip4/127.0.0.1/")
        }
        val bootstrapPeer = Libp2pPeer.remote(
            peerId = registration.peerId,
            multiaddresses = serverMultiaddrs,
            advertisedServices = listOf("my-service")
        )

        // 4. Create a client that connects ONLY to the test server
        clientResolver = UrlResolver(UrlProtocol2(bootstrapPeers = listOf(bootstrapPeer)))

        // 5. Connect and test
        val proxy = clientResolver.openSandboxedConnection(
            "url://my-service/",
            MyServiceApi::class
        )
        val result = proxy.doSomething()
        assertEquals(expected, result)

        registration.unregister()
    } finally {
        clientResolver?.close()
        serverResolver?.close()
    }
}
```

### Example: Multiple Test Clients Against One Server

```kotlin
fun testConcurrentClients() {
    var serverResolver: UrlResolver? = null
    val clientResolvers = mutableListOf<UrlResolver>()
    try {
        // Start isolated server
        serverResolver = UrlResolver(UrlProtocol2(bootstrapPeers = emptyList()))
        val registration = serverResolver.registerGlobalService(/* ... */)

        val bootstrapPeer = Libp2pPeer.remote(
            peerId = registration.peerId,
            multiaddresses = registration.multiaddresses.map { addr ->
                addr.replace(Regex("/ip4/[0-9.]+/"), "/ip4/127.0.0.1/")
            },
            advertisedServices = listOf("my-service")
        )

        // Start multiple isolated clients, each connecting only to the test server
        repeat(3) {
            val client = UrlResolver(UrlProtocol2(bootstrapPeers = listOf(bootstrapPeer)))
            clientResolvers.add(client)

            val proxy = client.openSandboxedConnection(
                "url://my-service/",
                MyServiceApi::class
            )
            val result = proxy.listItems()
            assertTrue(result.isNotEmpty())
        }

        registration.unregister()
    } finally {
        clientResolvers.forEach { it.close() }
        serverResolver?.close()
    }
}
```

### Key Imports

```kotlin
import foundation.url.protocol.Libp2pPeer
import foundation.url.resolver.UrlResolver
import foundation.url.resolver.UrlProtocol2
import foundation.url.protocol.ServiceHandler
import foundation.url.protocol.ServiceRegistrationConfig
```

### Common Mistakes

- **Forgetting `bootstrapPeers = emptyList()`** — If you omit the parameter, `UrlProtocol2` uses default production bootstrap peers and your test joins the real P2P network.
- **Using `UrlProtocol2()` with no arguments** — This connects to production. Always pass `bootstrapPeers` explicitly.
- **Not normalizing multiaddresses to localhost** — The server's registration may contain non-loopback addresses. Replace them with `127.0.0.1` so the client connects locally.

## Headless UI Testing

Components with a user interface (GUI and WUI) **must** have comprehensive end-to-end tests that exercise the full UI, not just the backend logic. UI bugs are real bugs, and every view, interaction flow, and error state should be covered.

### GUI Screenshot Tests (Mandatory)

GUI components built with Compose Multiplatform **must** include golden screenshot tests: each view and each distinct visual state is rendered offscreen, compared against a PNG committed in the repository, and the test **fails on any pixel difference**. The committed goldens double as living design documentation, and an intentional visual change is reviewed as the image diff GitHub renders in the pull request. A test that only writes a PNG and checks that the file is a valid image detects nothing — the screen can be blank, wrong, or half-drawn and it still passes — so recording alone does not satisfy this requirement.

**Rendering happens in the test's own JVM; no service is involved.** Compose Multiplatform's `ImageComposeScene` renders a composable in memory through Skia's software rasterizer, with **no display server or xvfb required**, on any standard CI machine. The hosted `url://screenshottest/` renderer is for WUIs only: it launches a submitted web server and drives a pinned Chromium, and cannot render a Compose GUI (see [WUI Golden Screenshot Tests](#wui-golden-screenshot-tests)).

A golden is only useful if the same inputs always produce the same pixels, so every input to the frame must be pinned:

- **Platform** — Goldens are canonical for **Linux x86_64**, the platform of CI and the remote builder, and are recorded only there. Compose selects font hinting and glyph rasterization per operating system, so a frame rendered on macOS or Windows is expected to differ on text edges even with every other input pinned; Linux on another CPU architecture (arm64, including a native Linux container on Apple Silicon) and x86_64 Linux emulated on any other host (an `amd64` container under Rosetta or QEMU) are untested and are not recording platforms either. A developer without a Linux x86_64 machine records on one they can reach remotely, such as a cloud VM or a shared development host; no service records Compose goldens remotely today. This is a deliberate, bounded exception to [Tests Must Be Hermetically Sealed](#tests-must-be-hermetically-sealed) and [Test Verdicts Must Be Hardware-Independent](#test-verdicts-must-be-hardware-independent): the operating system and CPU architecture are inputs a test cannot pin, so they are fixed by declaration instead. Off the canonical platform a golden test may fail when run locally, so run golden tests with `scripts/test.bash --remote` or on a Linux x86_64 machine; off the canonical platform, the full-suite run that [Local-First Testing](#local-first-testing) requires before pushing is `scripts/test.bash --remote --test .`. In either build system, a golden that a run off the canonical platform records for a new scenario was recorded on the wrong platform, even though the library's message says to commit it: delete it rather than commit it, and record it on Linux x86_64. Where no remote run exists (Gradle), the pre-push run off Linux x86_64 is still the module's test task, its golden tests may fail, and the pushed CI run is the check of the goldens. Never skip a golden test by platform and never loosen the comparison to make it pass elsewhere.
- **Compose and Skiko versions** — The rasterizer is part of the frame. A dependency bump that changes Compose Multiplatform or Skiko may legitimately change pixels; when it does, re-record the affected goldens in the same pull request as the bump and review the image diff like any other visual change.
- **Size and density** — Construct the scene with an explicit width, height, and `Density(1f)`. Never inherit them from a window or the host display.
- **Time** — Drive the model with a `ManualClock` (see [Clock Abstraction](#clock-abstraction)) and render at an explicit, fixed frame time (`scene.render(nanoTime)`; an offscreen entry point that always renders at time zero satisfies this). To capture a state partway through an animation, advance to a chosen instant; never sleep and capture whatever is on screen.
- **Data and randomness** — Build the state under test through the component's public API from fixed fixture data. Anything random (spawn positions, computer-controlled behaviour) must come from an injected random source that the test seeds, in the same way time comes from an injected clock.
- **Fonts** — Text drawn with Compose's default `FontFamily` is shaped with whatever fonts the host has installed — and with none at all on a minimal CI image. A GUI that draws text must bundle its font as a resource and use it explicitly; this is a production change, and it also makes the application look the same on every user's machine.
- **Images** — Load sprites and icons from the component's bundled resources, exactly as production does.

With those pinned the comparison is **exact**: there is no per-pixel tolerance to tune. This rests on an observation, not on a guarantee from Skia: seeded [AForce2](https://github.com/CodexCoder21Organization/AForce2) game screens containing sprites and bundled-font text hashed identically on a developer workstation and on a remote-builder worker. A golden that differs between two machines of the canonical platform means either that an input above is still unpinned — the defect to find — or that the machines differ in something the test cannot pin, such as the CPU features Skia's rasterizer selects at run time; the evidence so far comes from two machines only. If every listed input is demonstrably pinned and two Linux x86_64 machines still disagree, report it — with the golden, the frame rendered locally, and the other machine's failure message (its count of differing pixels and first differing pixel, which is all a remote run returns) — rather than introducing a tolerance — that would be a new fact about the rasterizer that this guidance has to account for, not something for one test to absorb.

Each test follows the same steps:

1. **Render the real production composable** — the same top-level composable the window hosts — rather than a test-only reconstruction. If the GUI has no way to render without opening a window, add an offscreen entry point to its public API (`OffscreenRenderer` in [AForce2](https://github.com/CodexCoder21Organization/AForce2) shows the shape of one); do not reach into internals from the test.
2. **Compare against the committed golden with [GoldenImageTest](https://github.com/CodexCoder21Organization/GoldenImageTest)** — the shared library (`community.kotlin.test.goldenimage:community-kotlin-test-goldenimage`) that holds the compare-and-record logic, so that no test file carries its own copy and none is shared between tests. `assertMatchesGoldenImage(image, goldenPath)` takes the frame as a `java.awt.image.BufferedImage` (convert the Skia `Image` returned by `scene.render` with `toComposeImageBitmap().toAwtImage()`) and the golden as an okio `Path`. It requires identical dimensions and identical pixels (two fully transparent pixels count as equal whatever their colour channels), and on a mismatch writes the rendered frame beside the golden as `<name>.actual.png` and fails with the number of differing pixels and the first differing coordinate with its expected and actual colours. Add `*.actual.png` and `.*.tmp` to the repository's `.gitignore`: the first are diagnostics, the second the temporary files the library writes each image to before moving it into place, and neither is ever committed. The function also accepts a `maxChannelDifference`; leave it at its default of zero.
3. **Keep each golden with the one test that owns it** — in a kompile repository the golden for `tests/<scenario>.kts` is `tests/<scenario>/golden.png`, the test's [per-test support directory](#every-test-must-be-fully-isolated), found with the library's `locateTestSupportDirectory("<scenario>")` because the process working directory differs between a local run and the remote builder. In a Gradle repository, use `screenshots/<scenario>.png` under the directory of the module that owns the test (Gradle runs a module's tests with that module's directory as the working directory). No golden is ever read by two tests. The run, record and diagnose commands below are kompile's; in a Gradle repository use the module's Gradle test task on a Linux x86_64 machine for all three. Declare the goldens as inputs of that task as a file tree, which Gradle does not require to exist, so the first golden of a new module can still be recorded: `tasks.withType<Test>().configureEach { inputs.files(fileTree("screenshots") { exclude("*.actual.png", ".*.tmp") }).withPathSensitivity(PathSensitivity.RELATIVE) }`, which applies whatever the test task is called (`test`, or `jvmTest` / `desktopTest` in a Kotlin Multiplatform module). A pull request changing only a golden then re-runs the comparison instead of being served from Gradle's build cache or reported up to date. No Gradle repository in the organisation implements golden comparison yet, so these Gradle mechanics have not been exercised the way the kompile ones have. Gradle has no remote run, so off Linux x86_64 the CI run is the only check of the goldens, and the library's failure messages, which mention `scripts/test.bash --local`, mean that Gradle task.
4. **A missing golden is recorded, and the test fails** — when the golden does not exist, the library writes the freshly rendered PNG to the golden path and fails with a message saying it was recorded and must be inspected and committed. A new scenario therefore cannot pass in CI without its golden. Recording — whether the first golden of a new scenario or a replacement after an intentional change, for which you first delete the affected goldens — is always done the same way: run those tests on a Linux x86_64 machine with `scripts/test.bash --local --test <scenario>` (a remote run would record the file on a discarded worker), look at every recorded image, and commit them in the same pull request as the code change. The library records on whatever machine runs it and cannot tell that the platform is wrong, so a golden recorded on the wrong platform shows up as a test that passes on the machine that recorded it and mismatches on Linux x86_64 (CI, or a `--remote` run): delete that golden and record it again on Linux x86_64. A pass off the canonical platform does not by itself show the golden is wrong — a scene with little or no text may well match everywhere — so confirm with a `--remote` run before deleting anything. Recording needs no environment variable or mode switch, because the absence of the golden is the signal. The same applies to diagnosing a mismatch reported by CI or a remote run: the `.actual.png` stays on the discarded worker, so reproduce the failure with `--local` on Linux x86_64 to obtain it. If it does not reproduce there, either an input is unpinned or the two machines disagree; see the paragraph on exact comparison above.

   Golden screenshot tests are the sanctioned exception to the in-memory-filesystem rule of [Tests Must Be Hermetically Sealed](#tests-must-be-hermetically-sealed) and [Side-Effect Isolation](#side-effect-isolation), and for a GUI golden test the exception is narrow. From the real filesystem it reads its golden, plus the directory listings and file metadata `locateTestSupportDirectory` needs to find the repository. It writes only its own golden file and that file's `.actual.png` sibling, creating their directory if need be — each by way of a temporary dot-file beside it (`.<name>.<random>.tmp`) that is moved into place atomically, and that can be left behind only if the process is killed mid-write: the recorded golden when none exists, the diagnostic on a mismatch, and the removal of a stale diagnostic on a pass. That holds in both layouts — in a Gradle module the `screenshots/` directory is shared, but no file in it is read or written by two tests. It reads no other file contents and writes nothing else.
5. **Assert the behaviour as well as the picture** — a golden pins what a state looks like, but "pixels differ" says nothing about why. Assert the model facts the scene depends on (the position reached, the lives remaining, the objects present) before comparing, so that a change in behaviour fails with a message that names it.

Give every distinct visual state its own scenario and its own golden: each kind of object the GUI can draw, each screen or map, every status a component can display (a button that changes colour by status needs one per status), and the empty and terminal states. In a kompile repository that is one `tests/<scenario>.kts` file per scenario; in a Gradle repository, one test method.

[AiCliGui](https://github.com/CodexCoder21Organization/AiCliGui) shows the breadth of state coverage to aim for across the modules of a larger application. Its screenshot tests predate golden comparison: they re-record their PNGs on every run and validate at most the PNG signature and size, so treat it as the reference for *what* to capture, not for *how* to assert.

### GUI Testing with xvfb

Compose Multiplatform tests that open a real window (`application { Window { … } }`, a `ComposeWindow`) require a graphics environment. On CI servers and other headless environments, use **xvfb** (X Virtual Framebuffer) to provide a virtual X11 display so that these tests run in CI exactly as they do on a developer workstation. Screenshot tests using `ImageComposeScene`, and interaction tests using `createComposeRule()` / `runComposeUiTest`, render offscreen and do **not** need xvfb.

### WUI Testing with Puppeteer and Playwright

WUI components must be tested through a real browser. Use **Puppeteer** or **Playwright** to automate browser interactions against a running WUI instance. Puppeteer controls Chrome/Chromium; Playwright supports Chromium, Firefox, and WebKit for cross-browser coverage. See [W3WalletTests](https://github.com/CodexCoder21Organization/W3WalletTests) for a reference implementation that starts real services, launches Chrome with extensions loaded, and captures screenshots on failure.

Playwright's browser engines (particularly WebKit) may require a graphics environment even in headless mode. On CI servers and other headless environments, use **xvfb** to provide a virtual display so that the full WUI test suite runs reliably.

### WUI Golden Screenshot Tests

WUIs should also carry **golden screenshot tests**: committed canonical screenshots (full pages and per-component crops) compared on every build, rendered exclusively by the hosted, version-pinned `url://screenshottest/` service so the pixels never depend on which machine ran the test. An unintended pixel difference fails the build; an intentional change is re-recorded and reviewed as a GitHub image diff. The authoring pattern — deterministic fixture entry point, injected fixed clock for time-stable pages, scenario list with component crops, record/compare modes, the `RENDERER_VERSION` handshake — is documented in the [WUI component guide](WUI.md#golden-screenshot-tests), with [BuildTestWui](https://github.com/CodexCoder21Organization/BuildTestWui) as the reference adopter and the design in the [ScreenshotTest workstream](https://github.com/CodexCoder21Organization/PlanRepository/blob/main/workstreams/ScreenshotTest.md). (Note this is distinct from the [GUI screenshot tests](#gui-screenshot-tests-mandatory) above, which also compare committed goldens but render in the test's own JVM: a Compose scene's inputs can be pinned by the test itself apart from the operating system and CPU architecture, which are fixed by declaring Linux x86_64 canonical (CPU features may still differ between machines; see the exact-comparison paragraph above), while WUI pixels come from a real browser whose build, fonts, and fontconfig no test controls, and therefore need the pinned rendering service. The WUI golden test also predates the GUI recording rule and switches into record mode through the `SCREENSHOTTEST_RECORD` environment variable, which is a known violation of [No Environment Variables in Tests](#no-environment-variables-in-tests) still to be removed; new golden tests, GUI or WUI, must not copy it.)

## Side-Effect Isolation

Tests must be as side-effect free as possible:

- **In-memory filesystems** — Use okio's in-memory `FileSystem` instead of the real filesystem. This avoids test pollution, speeds up execution, and eliminates cleanup concerns. The exception is a golden screenshot test ([GUI](#gui-screenshot-tests-mandatory) or [WUI](#wui-golden-screenshot-tests)), which reads its committed goldens from the real filesystem and writes only where they live.
- **In-memory databases** — Use in-memory databases instead of real databases when the component uses persistent storage.
- **Auto-assigned ports** — Use port `0` for any server started within a test, so the OS assigns an available port. Never hardcode ports.
- **Resource cleanup** — Ensure all opened resources (servers, connections, file handles, ports) are closed after the test completes, even in the event of failure. Use `try/finally` or equivalent patterns.

## No Environment Variables in Tests

**Tests must never read configuration from environment variables.** Environment variables are invisible, machine-specific, process-global state that makes tests behave differently depending on where they run, hides inputs from anyone reading the test code, and can silently leak between tests running in the same process. A test that passes on one machine and fails on another because of a missing or differently-set environment variable is not reliable — it is a source of confusion and wasted debugging time.

Instead, any configuration a test needs should be accepted as an **explicit parameter** — ideally with a sensible **default value** so the test works out of the box with no external setup. This applies equally to the production code under test: if the production code reads from environment variables, it should be refactored to accept configuration as a parameter, with the environment variable lookup confined to production entry points (e.g., `main()`), not the core logic. Tests then pass explicit values for the parameters they care about and rely on defaults for everything else — no environment setup required.

## Clock Abstraction

Any implementation that measures or waits for time should accept a `Clock` parameter from `community.kotlin.clocks.simple` and default to `SystemClock`. Tests should use `ManualClock` to control time deterministically, avoiding flakiness caused by real-time waits.

```kotlin
// Production code
class TokenManager(private val clock: Clock = SystemClock) {
    fun isExpired(token: Token): Boolean =
        clock.now() > token.expiresAt
}

// Test code
fun testTokenExpiration() {
    val clock = ManualClock()
    val manager = TokenManager(clock)
    val token = manager.createToken()

    assertFalse(manager.isExpired(token))

    clock.advance(Duration.ofHours(2))

    assertTrue(manager.isExpired(token))
}
```

## In-Memory Implementations for Error Simulation

Various APIs may throw exceptions or errors that cannot be replicated when the dependency is working correctly — which should be the common case. But services must be tested for how they behave in the presence of dependency errors. Since services communicate with dependencies through [Api](API.md) interfaces, you can build an **in-memory implementation** of the dependency's Api — a real, fully valid implementation of the interface that happens to store state in memory rather than in a database or on disk. These are not mocks or stubs; they are genuine implementations that could theoretically be used in production if persistence were not a requirement. Because they are real implementations, they can also be extended with additional constructor parameters or APIs that allow callers to influence their behavior — such as configuring specific operations to throw errors.

This is distinct from mocking — in-memory implementations are real implementations of the interface with real business logic. Normal tests should continue to use real deployed instances (see [End-to-End Tests, Not Mocks](#end-to-end-tests-not-mocks)). In-memory implementations are for the focused cases a real, correctly-functioning dependency cannot serve: exercising error handling paths it cannot trigger, [counting boundary work](#when-to-use-in-memory-implementations) for hardware-independent complexity guards, and isolating the [migration matrix](GRACEFUL_MIGRATION_FALLBACKS.md#testing-the-migration-matrix).

### When to Use In-Memory Implementations

Use an in-memory implementation when you need to verify that a service handles dependency errors correctly — for example:

- A dependency throws an exception during a critical operation
- A dependency returns malformed or unexpected data
- A dependency becomes slow or unresponsive
- A dependency's behavior changes between calls (e.g., succeeds then fails on retry)
- A dependency enforces rate limits or quota exhaustion

A second, focused use is **instrumented work counting for complexity guards**: an in-memory implementation extended to count the calls, messages, or bytes arriving at its Api boundary gives a [hardware-independent verdict](#test-verdicts-must-be-hardware-independent) on how much work the component under test performs — the amount of work crossing a dependency boundary is observable contract behavior. Reserve this for tests whose invariant *is* that boundary workload (fan-out, rebroadcast, retry amplification), and keep the ordinary end-to-end tests against real dependencies alongside.

### Design Principles

Each in-memory implementation should follow these principles:

1. **Live in a separate repository** — In-memory implementations are published as their own Maven artifact, independent of both the Api module and the [Embedded](EMBEDDED.md) module. This keeps the test utility cleanly separated from production code and allows it to evolve on its own release cycle.

2. **Be a real, valid implementation of the interface** — The in-memory implementation is not a test double or a fake — it is a genuine implementation of the Api that fully satisfies the interface contract. It differs from the production Embedded only in that it stores state in memory rather than in a persistent store, making it unsuitable for production use but otherwise fully correct. When constructed with no special configuration, it must be indistinguishable from the production Embedded in its observable behavior — correct business logic, proper validation, realistic return values, and accurate error conditions for invalid inputs.

3. **Implement realistic behaviors, not stubs** — Rather than returning hard-coded values, the in-memory implementation should maintain real state and implement the same logical behaviors as the production Embedded. For example, if the real implementation stores items in a database, the in-memory implementation should store items in an in-memory data structure with the same semantics — ordering, uniqueness constraints, capacity limits, and error conditions for invalid inputs. The goal is a complete, working implementation that happens to lack persistence.

4. **Model realistic performance characteristics via Clock** — The in-memory implementation should accept a `Clock` parameter (from `community.kotlin.clocks.simple`) and use it to model realistic timing behaviors such as operation latency, rate limiting, token expiration, and cache TTLs. Tests using `ManualClock` can control time precisely without real-time waits while still exercising time-dependent behaviors like timeouts and expiration.

5. **Expose error simulation through additional constructor parameters and APIs** — Beyond the default realistic behavior, the in-memory implementation should provide ways to configure specific methods to throw exceptions, inject latency for specific operations, return corrupted or unexpected data, simulate intermittent failures (e.g., fail every Nth call), and trigger unavailability scenarios. These controls are additional constructor parameters or dedicated APIs on top of the normal Api interface — they do not replace or alter the standard Api methods.

## Error and Warning Assertions

When testing error messages or warnings, **assert against the full text** of the error/warning, not just a substring. This ensures that error messages remain descriptive and informative, and catches regressions where the message changes in a way that loses important information.

```kotlin
// Good — asserts the full error message
assertEquals(
    "--test path must be a directory or a test.kts file, but the provided path (/tmp/missing) does not exist",
    exception.message
)

// Bad — only checks a fragment, would miss regressions in message quality
assertTrue(exception.message!!.contains("must be a directory"))
```

## Algebraic Effect Testing

Warnings emitted via algebraic effects should be tested by catching the specific effect type and verifying its content. Test that the expected effect is tossed, and that the effect carries the correct information.

```kotlin
fun testWarningEmittedForInvalidInput() {
    val effects = mutableListOf<NotificationEffect>()
    handler { e: InvalidUserIdWarningEffect ->
        effects.add(e)
        resume(Unit)
    } executeBlock {
        service.processInput(invalidInput)
    }
    assertEquals(1, effects.size)
    assertEquals("User ID 'abc' is not a valid numeric ID", effects[0].message)
}
```

## Bug Fix Testing Workflow

Bug fixes follow the same [test-driven development](#test-driven-development) approach, with an emphasis on reproducing the bug before attempting a fix:

1. **Analyze the root cause** — Deeply understand why the bug exists before writing any code.
2. **Write a failing test** — Create an end-to-end integration test that demonstrates the bug. The test MUST fail before the fix is applied. If the test passes, it does not properly reproduce the issue.
3. **Fix the bug** — Implement the fix. [Run the specific test](#running-tests-locally) to confirm it now passes.
4. **Ultrathink: are the tests comprehensive?** — Before calling the fix done, critically review whether additional tests could have caught this bug or an adjacent one — across edge cases, error conditions, boundary values, concurrency, resource exhaustion, and integration paths. Add any missing tests now, each written to fail first, so the same class of bug cannot silently return. See [Ultrathink: Are the Tests Comprehensive?](#ultrathink-are-the-tests-comprehensive).
5. **Verify no regressions** — Run the full test suite locally to ensure the fix doesn't break other functionality.

## `kotlin.build (remote)` Must Never Be Red

**ABSOLUTE RULE: The `kotlin.build (remote)` status check must never be red on a PR or on `main`. Treat it as unconditionally required, regardless of what the GitHub branch-protection UI reports.**

`kotlin.build (remote)` runs the kompile build and test suite on a shared droplet — the *canonical* kompile execution environment. A red result means this project's build or tests do not work on the standard environment that other projects, users, and downstream consumers rely on. A parallel, project-specific GitHub Actions workflow showing green is **not** a substitute and does not discharge this requirement.

**When `kotlin.build (remote)` is red, the response is always the same:**

- Investigate and fix the underlying cause. If the tests need setup the droplet does not provide, either the project must perform that setup from inside its own `test.kts` files (idempotently, so the work is cached across runs) or the required tooling must be extracted into a Maven-published helper (see [Never Skip Tests](#never-skip-tests) and the [shared-helper rule](#every-test-must-be-fully-isolated) under *Kompile Test File Constraints*).
- Never merge while `kotlin.build (remote)` is red.
- Never propose "accepting" a red `kotlin.build (remote)` because another CI job is green, or because the failure only reproduces on the droplet.
- Never skip, disable, or environment-gate a test to turn the check green — that is masking a real failure. See [Never Skip Tests](#never-skip-tests).
- Never request removal of the check from branch protection.

**Why this matters:** `kotlin.build (remote)` is the source of truth for whether the kompile build/test flow works end-to-end in the environment the rest of the ecosystem shares. Once a PR lands with it red, every subsequent PR inherits that broken baseline, real regressions get buried, and the check stops carrying useful signal.

## Never Skip Tests

**Tests must never be conditionally skipped.** A missing dependency, missing tool, or missing environment configuration is never a reason to skip a test or bypass assertions — it is a reason for the test to **fail**. If a test requires Puppeteer, xvfb, Docker, a specific binary, or any other dependency, and that dependency is not available, the test must fail loudly, not pass silently. For example, a test that needs Docker must **fail** when Docker cannot run — such as when Docker-in-Docker is not possible in the current environment — and must never detect the missing capability and quietly pass.

- **Never use `assumeTrue`, `assumeNotNull`, or similar assume-based guards** to skip tests when a dependency is missing.
- **Never use early `return` or conditional logic** to silently pass when the test environment is incomplete.
- **Never use `@EnabledIf`, `@DisabledOnOs`, or similar conditional annotations** to skip tests based on environment.

A test that skips itself is providing false confidence — it appears as a pass in CI results, but it verified nothing. If a dependency is required, the environment that runs the test must provide it, and the test must fail if it is absent. This ensures that broken environments are detected immediately rather than silently degrading test coverage.

**Run dependency-bearing tests in an environment that provides the dependency — never skip them.** Skipping is not the way to handle a test whose dependency is missing; routing it to an environment that *has* the dependency is. BuildTestEmbedded supplies `url://buildtest/` as the default execution environment, and the first delivered named route is `url://netlab-hosted/...`: a test can declare `@ExecutionEnvironment` and the BuildTest dispatch path sends it through a manager-owned NetLab environment, releasing the exact lease on completion and failure paths. This route is documented by [BuildTestEmbedded PR 448](https://github.com/CodexCoder21Organization/BuildTestEmbedded/pull/448); the annotation and broader runner contract remain tracked in the [Pluggable Execution Environments workstream](https://github.com/CodexCoder21Organization/PlanRepository/blob/main/workstreams/ExecutionEnvironments.md), while the hosted CI orchestration context is tracked in the [Kompile Remote Build workstream](https://github.com/CodexCoder21Organization/PlanRepository/blob/main/workstreams/KompileRemoteBuild.md). For capabilities not yet supplied by a named runner — Docker, a GPU, a specific operating system, or another dependency — the correct state is **failing**, not skipped: a red test is a visible, tracked gap, whereas a skipped test silently verifies nothing.

## Flaky Test Policy

Flaky tests must be fixed, never ignored or retried:

- **Never mark tests as disabled/ignored** — Fix the root cause instead.
- **Never add retry logic to tests** — Retries hide the underlying issue.
- **Fix a flake when you meet it, not later** — a flake encountered on the way to other work is handed at once to an owner (a separate agent, engineer, or lane) who drives its reproducer, fix, and pull request to landed in parallel while the original work continues; independent flake fixes are raced to land, and only bundled when each is being evicted by the flakes it does not fix (see [Fix Flakes Along the Way, Never Ignore Them](../PHILOSOPHY.md#fix-flakes-and-infrastructure-pain-along-the-way-never-ignore-them)). A flake found only in a separate, wider test register does not add to the change's landing bar: the change lands when its own required checks and tests of the changed behavior are first-attempt clean and its reviews are clean, while the flake fix races it in a separate pull request; sequence the two only when either inherently depends on the other, and never waive a failed required check or retry it unchanged.
- **Never re-run CI hoping it passes** — Re-running CI without fixing anything is gambling, not engineering. Even if CI passes on a re-run, the flake still exists and will surface again. If a test fails in CI, [reproduce it locally](#reproduce-locally-first) and fix the root cause.
- **Never increase timeouts within test code to fix flaky tests** — Timeouts within test code (e.g., a `CountDownLatch.await` or a polling loop) are typically already generous. If a test fails to complete within these expectations, that indicates a fundamental problem with the code under test — a deadlock, a performance regression, thread starvation, or an unbounded wait. The correct fix is to make the code being tested fast and correct, not to mask the issue by increasing the timeout. This refers to timeouts in the test's own logic, not the `@Timeout` annotation (see [Test Timeout Annotation](#test-timeout-annotation)).

A flake is a **real defect**, and its root cause is frequently not in the test or even in the component under test — it often lives in a dependency library or in the shared infrastructure the test runs on. Investigating a flake therefore means tracing the failure down through those layers and fixing the defect at the layer that owns it, never working around it in the consumer. See [Every Flake Is a Real Defect — Trace It Through Dependencies and Infrastructure](#every-flake-is-a-real-defect--trace-it-through-dependencies-and-infrastructure).

### Understand Before Fixing

**Do not attempt a fix until you fully understand the root cause.** Flaky tests are deceptive — the symptom (a test that sometimes fails) rarely points directly at the cause. Jumping to a fix without understanding the root cause leads to patches that mask the problem or shift it to a different test.

Before writing any fix:
1. **Form a hypothesis** about what condition causes the failure and why it only occurs sometimes.
2. **Validate the hypothesis** with an experiment — if you can't reliably trigger the failure, you don't understand the cause yet. Design the experiment so it can *refute* the hypothesis, not only confirm it, and treat an evidence-backed refutation as a successful result: it redirects the investigation toward the real cause, whereas an experiment that can only confirm invites you to keep "finding" the mechanism you expected (see [A Refuted Hypothesis Is a Successful Outcome](../PHILOSOPHY.md#a-refuted-hypothesis-is-a-successful-outcome)).
3. **Determine whether the bug is in the test or in the production code.** A flaky test can reveal a real concurrency bug in production code, not just a test-level issue.

### Reproduce Locally First

Always [reproduce the flake locally](#running-tests-locally) rather than relying solely on CI failure logs. As a first diagnostic step, run the failing test repeatedly to confirm you can trigger the failure on your machine:

```bash
for i in {1..20}; do
  echo "=== Run $i ==="
  scripts/test.bash --test myFlakyTest
done
```

If the test passes consistently in isolation, try running it under load — flaky tests often manifest under parallel execution or resource pressure. This bash loop is only a diagnostic tool to confirm the flake is reproducible. Once you understand the root cause, write a proper [stress test](#stress-testing-for-flake-reproduction) that targets the specific failure condition — that stress test becomes a permanent part of the test suite and runs in CI to prevent regression.

### Common Causes of Flaky Tests

| Cause | Symptom | Fix |
|-------|---------|-----|
| Race conditions | Random incorrect values or NPEs | Add proper synchronization or eliminate shared mutable state |
| Port conflicts | "Address already in use" | Use port `0` for auto-assignment |
| HashMap iteration order | Order-dependent assertion failures | Use `Set` equality or sort before comparison |
| Time-dependent logic | Failures around time boundaries | Inject `Clock`, use `ManualClock` in tests |
| Shared static state | Test passes alone, fails with other tests | Use per-test instances, reset state in setUp |
| Resource leaks | Intermittent failures after many test runs | Close all resources in `finally` blocks |
| "Only fails under CI load" | A real defect (non-linear gossip, leak, over-allocation→swap) blamed on host capacity | Measure and fix the defect — see [The Host Is Almost Never Over Capacity](#the-host-is-almost-never-over-capacity) |

### Stress Testing for Flake Reproduction

When a test is flaky, create a **stress test** that amplifies the failure condition to reproduce it consistently:

```kotlin
fun stressTestConcurrentAccess() {
    repeat(1000) { iteration ->
        val resource = createResource()
        try {
            val results = runBlocking {
                (1..10).map {
                    async { resource.concurrentOperation() }
                }.awaitAll()
            }
            assertEquals(expectedState, resource.state,
                "Failed on iteration $iteration")
        } finally {
            resource.close()
        }
    }
}
```

The stress test must **fail consistently before the fix** and **pass consistently after the fix** (10+ runs). This ensures the fix genuinely addresses the root cause and prevents the flake from regressing.

### Never Calibrate a Reproducer to a Machine

**A reproducer must never be calibrated to a machine — it must discriminate on the contract.** Amplification — more iterations, more genuine concurrency — is a *discovery* tool: it surfaces the failure often enough to let you see the mechanism. Once the mechanism is known, the reproducer stops relying on statistics and **forces the specific condition the code must tolerate directly**: inject the fault through an [in-memory dependency](#in-memory-implementations-for-error-simulation), close the stream at the exact byte, deliver the messages in the problematic order, exhaust the resource on purpose. A reproducer built this way fails 100% of the time on every host, because it discriminates on the *contract* — "the code must tolerate condition X" — rather than on how often a particular machine's scheduler happens to produce X.

The tell that a reproducer has been calibrated to a machine is that its reliability varies with the hardware — a concurrency level tuned until the failure appeared on one host, which then behaves differently on the build machine. That is not a reliable reproducer at all: do not ship it, and do not validate a fix against it. More importantly, the variability is a sign the mechanism is not yet fully understood — knowing the exact interleaving, fault, or state the defect requires is precisely what lets you construct it deterministically. When you find yourself re-tuning thread counts or iteration budgets per host, go back to diagnosis and identify the precise condition, then build the test that produces it on purpose. This is the reproducer-side counterpart of [Test Verdicts Must Be Hardware-Independent](#test-verdicts-must-be-hardware-independent): the *verdict* must not depend on the machine, and neither may the *trigger*. A reproducer that needs production-sized data to cross a limit is the same mistake in another form — make the limit small through configuration instead ([When a Fix Does Not Fix Production](../playbooks/FIX_DID_NOT_HOLD.md#rule-3-shrink-the-threshold-dont-inflate-the-data)).

### Prefer Parallel Iterations Over Serial `repeat`

When a stress test performs an independent operation N times, **run the iterations in parallel**, not in a serial `repeat` loop. The parallel form is a double win:

- **Wall-clock time drops** from `N × per-op` to roughly `max(per-op)`, which matters when the suite has many stress tests or each op takes real time (network, libp2p dial, GC pause).
- **Concurrent execution exercises race conditions and thread-safety bugs** that a serial loop will never hit. A bug where two concurrent callers of the same code path corrupt shared state cannot manifest if iterations run one at a time — so a serial stress test gives false confidence that the code is safe under load.

Serial form (slower, misses concurrency bugs):

```kotlin
repeat(N) { iteration ->
    performOperation()
    assertInvariant()
}
```

Parallel form (default):

```kotlin
runBlocking {
    (0 until N).map {
        async(Dispatchers.Default) {
            performOperation().also { assertInvariant(it) }
        }
    }.awaitAll()
}
```

**Bound the parallelism when the operation has real resource cost.** Unbounded `async { ... }.awaitAll()` across `N` iterations is fine when each op is cheap, but is wrong when the op is heavy — it can blow up memory, exhaust a thread pool or connection pool, exceed an API rate limit, or thrash the CI host. In those cases cap concurrency explicitly:

```kotlin
val concurrency = 8 // derived from the real resource budget, not a guess
val semaphore = Semaphore(concurrency)
runBlocking {
    (0 until N).map {
        async(Dispatchers.Default) {
            semaphore.withPermit {
                performOperation().also { assertInvariant(it) }
            }
        }
    }.awaitAll()
}
```

Pick the cap from the *actual* constraint — heap headroom, pool size, rate limit — not from intuition. The goal is still "as parallel as the real system can sustain," not "serial."

Truly serial (concurrency = 1) is reserved for cases where the test actually requires it:

- **Ordering dependency**: iteration N+1 observes a durable side-effect from iteration N.
- **Deliberate sequential semantics**: you are specifically testing "behaviour under repeated-not-concurrent use."
- **Physical exclusivity**: the op uses a singleton resource that cannot be partitioned.

In those cases leave an inline comment saying *why* it is serial — otherwise a future reader will (rightly) parallelize it.

### Case Study: Root Cause vs. Symptom Treatment

This is a worked example of the Flaky Test Policy in action — specifically the trap of "fixing" a flake by quietly weakening the assertion or bumping a budget instead of identifying what is actually slow or broken.

**The flake.** `HttpsAcceptLoopSurvivesHandlerFatalErrorTest` in [ContainerNursery](../projects/ContainerNursery.md) had been failing intermittently on `main` for weeks. Each failure looked the same: the test stresses a deliberately-undersized 24 MiB child JVM with concurrent OOM-bait POSTs, then opens a fresh TLS connection inside a 60 s recovery window and asserts an HTTPS round-trip succeeds. On clean runs it succeeds in the first probe. On flaky runs every probe inside the window timed out at the per-probe 20 s read budget, even though the child JVM was still alive and `sawFatalCoroutineError=false` indicated the wedge bug under test was not actually firing.

**The wrong fix.** The first attempt, drafted under time pressure to unblock an unrelated PR, weakened the assertion: instead of requiring a successful HTTPS round-trip, the test would also pass if a new `TLS_CONNECT` log line appeared in the child JVM during the recovery window. The argument was that `TLS_CONNECT` is the bug's actual production fingerprint (it's the first line the handler body writes, so it doesn't appear at all when the wedge is present), and that probe success is too sensitive to slow CI. This was reviewed and rejected:

1. **It strictly weakened the test.** The original assertion required client-observed round-trip success; the new one accepted a log line. By construction, more cases pass — including cases where the handler starts but hangs partway through the handshake, which the original assertion would have caught.
2. **It shifted goalposts to dodge the actual evidence.** The flake's signature was "20 s read timeout under post-OOM GC pressure." The honest options were (a) figure out why handshake/response delivery exceeds 20 s and fix that, or (b) ask explicitly for a timeout bump. Reframing the assertion sidestepped both.
3. **The "stress test that reproduces the flake reliably" required by the [Flaky Test Policy](#flaky-test-policy) had not been written.** The evidence was a single CI rerun passing.
4. **It violated [`Never increase timeouts within test code`](#flaky-test-policy)** in spirit — accepting a weaker outcome inside the same window has the same effect as widening the window: both let the slow-path scenario pass without explaining why it's slow.

**The actual investigation.** The fix was reverted unmerged. The investigation worked from the data the failed CI run had already produced. The child JVM's full server log was in the failure dump — including every `TLS_HANDSHAKE_COMPLETE domain=... duration=Nms` line. Aggregating those across the failing run:

| metric | value |
|---|---|
| total `TLS_HANDSHAKE_COMPLETE` events | 3544 |
| median duration | 46 ms |
| p90 duration | 100 ms |
| events ≥ 1 s | 12 |
| events ≥ 3 s | 10 |
| max duration | 6286 ms |

Localhost TLS handshakes do not spontaneously take 6 seconds. They take that long when the kernel entropy pool drains and SunJSSE's seed generator serializes behind `/dev/random`. The 10 multi-second outliers were all clustered inside the foreground waves where 24 concurrent SSL handshakes hit the server at once — exactly the workload that drains an entropy-poor CI host. Once that backlog forms, a fresh recovery probe lands on a server still queueing for seed bytes and blows the 20 s read budget even though the accept loop and handler scope are both healthy.

**The actual fix.** Add `-Djava.security.egd=file:/dev/./urandom` to both JVMs that do TLS in this test — the in-test child JVM (server-side handshakes, set in the `ProcessBuilder` arg list) and the Gradle-forked test JVM (client-side handshakes, set via `tasks.test { jvmArgs(...) }` in `build.gradle.kts`). `urandom` is documented by Oracle as cryptographically appropriate for SSL ([JDK-6202721](https://bugs.openjdk.org/browse/JDK-6202721)). No assertion was changed, no budget was widened, no test was retried.

**The before/after, same workload, same CI host:**

| metric | failing run (no fix) | fixed run (urandom) |
|---|---|---|
| median handshake | 46 ms | 59 ms |
| max handshake | **6286 ms** | **496 ms** |
| handshakes ≥ 1 s | **12** | **0** |
| 5 stress waves elapsed | ~75 s | ~23 s |

The fixed run had `sawFatalCoroutineError=true` (the fatal coroutine path actually fired), so the wedge-detection branch was actively exercised. The original probe-success assertion passed unchanged.

**Lessons.**

- **The flake's evidence is usually in the failure dump already.** Before designing a fix, aggregate the data the test produced — handshake durations, GC log, thread dump, log timestamps. The failing run here logged the smoking gun (a 6.3 s handshake) on every failure; nobody had aggregated it.
- **A weaker assertion that lets a slow case pass is the same category of mistake as bumping a timeout.** Both relax the test's contract instead of explaining why the contract was violated. The [Flaky Test Policy](#flaky-test-policy) prohibits both.
- **"Recovery" is the symptom; the root cause is upstream.** The flake manifests on the recovery probe, but the entropy pool drains during the *stress* phase. Fixing the recovery probe wait would have left the underlying entropy contention intact and the failure mode latent in any future test that does heavy concurrent TLS.
- **Document the diagnostic next to the fix.** The `-Djava.security.egd` arg ships with a comment explaining the 46 ms→6286 ms data and citing JDK-6202721. The next person who sees this file should not have to rediscover why it's there.

## Preferred Testing Libraries

- **okio** — Use okio's in-memory `FileSystem` for filesystem operations in tests.
- **org.json** — Prefer `org.json` for JSON parsing over Gson or Jackson.
- **commons-cli** — Used in CLI components for argument parsing; test argument parsing through the CLI's public interface.

## Kompile Test File Constraints

For projects using the kompile build system, tests live in `.kts` files inside a `tests/` directory (e.g., `tests/featureOneTest.kts`, `tests/featureTwoTest.kts`), where each file contains a single test.  Generally, the name of the test function should match the name of the file. Each file is independent with its own `@file:WithArtifact` declarations and package statement. See the [Kotlin Build](../projects/KotlinBuild.md) documentation for full details on writing and running tests with kompile.

- Test `.kts` files may **only** contain top-level functions (no properties, no `val`/`var` at the top level).
- Functions must **not** declare return types (no `: ReturnType`).
- Functions must use **block bodies** (not expression bodies with `=`).
- All helper logic must be **inlined into each test function**.
- Use `@file:WithArtifact(...)` to declare dependencies, and import the `WithArtifact` annotation.

### Every Test Must Be Fully Isolated

Each `.kts` file under `tests/` must be **completely self-contained** — it stands on its own, depends on no other test, and shares no source code or data with any other test. Test files are compiled independently, and the runner makes no guarantees about which tests run together, in what order, or in what process. Any coupling between tests silently breaks this model and makes individual tests impossible to read, debug, or evolve on their own.

- **No shared `.kts` files or helper scripts inside `tests/`** — Do not create files like `tests/testHelpers.kts`, `tests/common.kts`, or a `tests/shared/` subdirectory containing code that other tests reference. Each test file compiles standalone and cannot pull in helpers from sibling files under `tests/`. The only `.kts` files permitted directly in `tests/` are the individual test scripts themselves, one scenario per file.
- **No shared data or fixture files between tests** — Do not place fixture data, golden files, sample inputs, or configuration under a shared path like `tests/fixtures/`, `tests/data/`, or `tests/resources/` that multiple tests read from. Shared fixtures create hidden coupling: editing a fixture to adjust one test can silently alter the behavior of every other test that reads it.
- **Per-test support goes in a per-test subdirectory** — If a test needs supporting code or data, place it in a subdirectory of `tests/` whose name matches the test file name (without the `.kts` extension). For example, supporting files for `tests/testMyFeature.kts` live in `tests/testMyFeature/`. That subdirectory is owned by exactly one test and must not be read by any other test — not even a test with a closely related name.
- **Duplicate small helpers rather than share them** — If two tests need the same short helper or the same small fixture, duplicate it into each test. A small amount of duplication across isolated tests is strictly preferable to coupling them through a shared file. Duplication keeps each test readable end-to-end and independently editable; sharing across tests creates invisible dependencies and makes it impossible to understand a test by reading only its own file.
- **Shared utilities belong in a separate repository, imported via Maven** — If a helper is genuinely reusable and substantial enough that duplicating it across tests is unacceptable, it must be extracted into its **own dedicated Git repository** with its own `build.kts`, its own test suite, and a public API. That repository publishes a Maven artifact, and tests depend on the helper using `@file:WithArtifact("group:artifact:version")` — the same mechanism they use for any other third-party library. This turns the helper into a properly-versioned, independently-tested component with a clear contract, rather than a hidden dependency living alongside the tests that happen to use it. This rule is absolute: do **not** add a helper under `src/main/kotlin` purely so `test.kts` files can pull it in via `@file:WithArtifact("<thisProject>.buildMaven()")` — that pollutes the project's public Maven artifact with test-only code. If the helper is test infrastructure, it belongs in its own repo.

The `tests/` directory layout is therefore strictly:

```
tests/
├── testFeatureA.kts           # single test, self-contained
├── testFeatureA/              # optional support, read only by testFeatureA.kts
│   └── fixture.json
├── testFeatureB.kts           # single test, self-contained
└── testFeatureB/              # optional support, read only by testFeatureB.kts
    └── input.txt
```

Any other layout — `tests/common/`, `tests/helpers/`, `tests/shared.kts`, a top-level `tests/fixtures/` directory read by several tests, or a per-test subdirectory accessed by a test other than its owner — violates this constraint. Refactor by either inlining the shared logic into each test or extracting it into a separate Maven-published repository.

## Timeouts in Tests

Timeouts appear in tests at two distinct levels:

1. **Annotation timeouts** — declared on the test with [`@Timeout`](#test-timeout-annotation) and enforced by the build system, bounding the execution of the whole test.
2. **In-test step timeouts** — waits inside the test body that bound a single step of the test: awaiting a server to finish starting, a message to arrive, a future to complete, a condition to become true.

Both serve the same two purposes, and neither is a precision instrument. A timeout in a test exists (a) as a sanity check that stops a stuck test from blocking the entire build indefinitely, and (b) as a last-resort tripwire that fires if the performance of the code under test suddenly tanks because of a change. It is not there to referee whether a run was somewhat slower than usual.

**Consequently, both kinds should be generous.** The annotation timeout should be a comfortable multiple of the normal, expected execution time of the test — on the order of 5x — sized against measurement per [Sizing the Timeout: Measure First, Then Be Generous](#sizing-the-timeout-measure-first-then-be-generous). An in-test timeout on an operation that is *expected to succeed* — that is, whenever the test is not intentionally exercising timeout behavior itself — should be similarly generous: it should fire only when something has genuinely gotten stuck (a deadlock, an unbounded wait, a lost message) or has massively regressed, never on a healthy-but-slow run. A tight in-test timeout buys nothing — a generous one catches the same hang — and costs flakes on every loaded CI host; and widening one to chase an intermittent firing is equally wrong, because a flake under an already-generous bound is evidence of a real defect ([Flaky Test Policy](#flaky-test-policy)), not of an undersized bound.

### Verifying Timeout Behavior: Discriminate on Complexity Class, Not Speed

When a normal test — as opposed to an explicitly annotated performance test — sets out to *verify* that an operation completes within a time budget, the test must be intentionally designed so that its verdict discriminates between **complexity classes**, not between fast and slow runs of the same algorithm. The instrument for this is **scale**:

- **If the operation is expected to be constant time,** drive the structure to a size where any per-element cost dominates — add 10,000+ elements or records before invoking the operation — so that a linear-time implementation completely blows the budget while a constant-time one succeeds with generous margins.
- **If the operation is expected to be linear time,** the same 10,000+ elements catch an implementation whose cost has become quadratic or exponential: the healthy run finishes comfortably, the defective one overshoots by orders of magnitude.
- **If the goal is to detect race conditions that cause deadlocks,** request many operations in parallel (see [Prefer Parallel Iterations Over Serial `repeat`](#prefer-parallel-iterations-over-serial-repeat)) — a deadlock does not make the test slower, it makes it never finish, so any generous bound separates the two outcomes cleanly.

In every case, set the timeout margin so that it will **not** fail for a "slow" run in the same complexity class but will **certainly** fail for a bug that changes the complexity class. That is what makes the verdict [hardware-independent](#test-verdicts-must-be-hardware-independent): the gap between a healthy run and a defective one is orders of magnitude, not a factor that host speed can blur — and the separation must be *proven* by running the guard against the defective implementation, per [Asymptotic separation](#test-verdicts-must-be-hardware-independent). The same scale-driven design underpins the load and saturation suites in [Extreme-Condition Testing](#extreme-condition-testing-load-fuzzing-failure-injection-and-chaos), whose cost-curve assertions are complexity-class guards applied to a whole service under load.

## Test Timeout Annotation

The test runner enforces a default per-test timeout (configurable via `--unittest-timeout`, default 30 seconds — see [Kotlin Build](../projects/KotlinBuild.md)). For tests that legitimately need more time — such as stress tests, multi-node networking tests, or tests that start several real service instances — use the `@Timeout` annotation to override the default on a per-test basis.

`@Timeout` is a **safeguard against hanging tests**, not a target. A correctly-functioning test should always complete far within its timeout — if the test code is not buggy, the timeout should never be hit in practice. The annotation exists so that when something goes wrong (a deadlock, an unbounded wait, a network hang), the test runner can kill the test and report a failure rather than hanging indefinitely. If a test is routinely finishing close to its timeout, that is a bug in the test or the code under test, not a reason to increase the timeout.

For full details on the `@Timeout` annotation — including file-level timeouts, custom time units, and parameter reference — see the [Kompile Annotations Reference](https://github.com/CodexCoder21Organization/KompileDocumentation/blob/main/docs/annotations-reference.md#timeout) and [Kompile Testing: Test Timeout](https://github.com/CodexCoder21Organization/KompileDocumentation/blob/main/docs/testing.md#test-timeout).

### Sizing the Timeout: Measure First, Then Be Generous

**Never raise an existing timeout, and never declare a new one, without first measuring how long a successful run actually takes.** Picking a value by guesswork — or, worse, repeatedly bumping it until the test passes — defeats the purpose of a timeout.

The required workflow is:

1. **Run the test green at least once and record the elapsed time.** Use the test runner's reported duration or wrap the test body in a `System.nanoTime()` measurement. Several runs are better than one — anchor on the slowest *healthy* run, since timing varies with CI host load and the goal is to absorb that variance, not a real regression. Healthy CI runs are first-class measurements: if the test will run on CI and CI's healthy runs are slower than local ones, the slowest healthy run to anchor on is the CI one.
2. **Set the timeout to roughly 2x the measured time**, rounding up to a sensible round number. 2x is the default headroom for normal jitter — GC pauses, dependency startup, CI host contention.
3. **Use up to about 5x** when the test exercises bursty work, parallelism with real resource cost, or anything else where the ratio of slowest-healthy to typical is high. Going meaningfully beyond 5x is a sign that the timeout is no longer a sanity check.
4. **Anchor on the measurement, not on round numbers.** If a green run takes 18 seconds, declare `@Timeout(45)` or `@Timeout(60)` — not `@Timeout(30)` because that is the default cliff, and not `@Timeout(600)` because "tests sometimes take a while."

Measuring on one ordinary development machine is sufficient — but be clear about why. The multiplier is not what makes the verdict hardware-independent; no ratio of one machine's runtime can prove another machine stays under the bound. What makes it hardware-independent is the *gap between the two populations the timeout separates*: a healthy run costs seconds of real work, while the failures the timeout guards — a hang that never completes, growth that overshoots by orders of magnitude — are not "somewhat slower," they are categorically beyond any bound. Hardware variance shifts the healthy population by small factors inside the headroom; it cannot carry a failure back under the bound or push a healthy run past it, *provided the two populations are far apart*. That is the design obligation: if a plausible host could blur them — because the test does enough timed work that a slow machine's healthy run approaches the bound — the test is misdesigned, and the fix is to shrink its timed workload or restructure what it measures (see [Test Verdicts Must Be Hardware-Independent](#test-verdicts-must-be-hardware-independent)), never a bigger multiplier, a per-environment value, or a CI-specific adjustment.

Common reference points, all chosen against measurement rather than convention:

- **Standard tests** — Should complete within the default 30 seconds. If a standard test needs `@Timeout`, first consider whether the test is doing too much or the code under test is too slow.
- **Stress tests and multi-node tests** — Commonly use `@Timeout(120)` (2 minutes) when measurement shows healthy runs landing in the 30–60-second range, to absorb iteration count, parallel request fan-out, and service startup across multiple nodes.
- **Tests with known shorter bounds** — Use a tighter value like `@Timeout(95)` when measurement shows the worst healthy run lands well under 2 minutes but exceeds the default.

Bumping a `@Timeout` because a test fired its timeout is the same category of mistake as raising [`@BillingQuotaExecutionLimit`](#test-memory-limit-annotation) to silence an `OutOfMemoryError`, [increasing in-test timeouts to fix a flake](#flaky-test-policy), or reducing stress test iteration counts. The timeout firing is evidence — investigate the same way you would investigate any other failure: is there a deadlock? An unbounded wait? A real performance regression? Only after the underlying behavior is understood, and ideally fixed, should you consider whether the cap itself is still appropriately sized. Whether the firing is evidence of a *regression*, though, depends on whether the test has an established baseline — a brand-new test still in its introducing PR does not; see [Choose the Timeout Before Merging the Test](#choose-the-timeout-before-merging-the-test).

### Choose the Timeout Before Merging the Test

**A test's `@Timeout` value is part of the test's contract and must be chosen before the test is merged into `main`.** The measurement, the chosen multiple, and any reasoning that justifies a non-default value all belong in the same review as the test itself, where they can be examined alongside the code being tested. Picking the right value at merge time is the only opportunity to size the timeout against the test's real, current cost without being under pressure to unblock something else.

**Before the introducing PR merges, the timeout is still being *chosen* — adjusting it is sizing, not a forbidden bump.** Every rule in this document against increasing a timeout protects an *established baseline*: a test that has been passing on `main` within its bound, where a new timeout failure is evidence that something regressed. A test newly added by a PR has no baseline — there is no prior expectation of how long it takes, on CI in particular — so a test that passes locally but fires its timeout on its first CI runs is not signaling a regression; it is signaling that the value was sized from an incomplete measurement (for example, local runs only). The response is to complete the measurement — never to simply re-run CI hoping the timeout does not fire. If the timeout fires intermittently rather than consistently, that is a flake, and the [Flaky Test Policy](#flaky-test-policy) applies: investigate and fix the root cause. When the firing is consistent, complete the measurement rather than treating the not-yet-merged value as untouchable:

1. **Confirm the slow runs are healthy.** Given room to finish, the test must complete the same work and go green — merely slower on CI, not stuck. A test that hangs on CI, or whose runtime is wildly out of proportion to the work it does, has a defect (a deadlock, an unbounded wait, accidental super-linear work) that must be investigated and fixed; no timeout value is correct for it.
2. **Re-anchor on the slowest healthy run across every environment the test will run in, CI included**, and size the timeout at a minimum of 2x and up to about 5x that typical healthy execution time, exactly as [Sizing the Timeout](#sizing-the-timeout-measure-first-then-be-generous) prescribes. This is one generous bound sized against the full healthy population — not a per-environment value or a CI multiplier, which remain forbidden ([Test Verdicts Must Be Hardware-Independent](#test-verdicts-must-be-hardware-independent)).
3. **If the re-anchored bound stops being a sanity check** — the healthy runtime grows so long that a healthy run and a hang are no longer clearly separated — the test is doing too much timed work; shrink the workload or restructure what it measures rather than reaching past ~5x.

The bright line is merge, not the first timeout failure: before the introducing PR lands on `main`, adjusting `@Timeout` against a fresh healthy measurement is the required sizing workflow; after it lands, the same adjustment is an escalation requiring investigation and explicit approval, as described below.

How strongly a firing timeout signals a regression scales with how long the test has been in the repository:

- **Unmerged — added by your own PR.** The timeout is yours to size against measurement, per the workflow above. Prefer designing the test to fit the conventional budgets — the 30-second default for a normal test, about 2 minutes for a stress test — and reach for a larger value only when the test's nature genuinely requires it (multi-node startup, several real service instances, high-iteration stress work); some test types unavoidably run longer, and that is acceptable when the measurement justifies it.
- **Very recently merged — landed within roughly the last handful of commits.** The value is suspect: it was sized moments ago, possibly against incomplete measurement, and may simply be too tight. A resize can be justified, but only after you verify the test exceeds its timeout on a clean checkout *without* your changes (on `main`, or your branch's merge-base) — that clean-branch measurement is the evidence that the under-sizing predates your PR, and it belongs in the focused resizing PR described in the escalation bullets below. If the test passes cleanly without your changes, the slowdown is yours: investigate your own change.
- **Established — in the repository for a while.** The timeout has survived many green runs, so its baseline is trustworthy; do not touch it. A firing here is strong evidence of a real regression in your code or implementation — investigate per the escalation rules below, and treat any urge to resize as the signal that the root cause has not been found yet.

**Once a test is merged into `main`, its `@Timeout` generally should not be adjusted without explicit approval.** A later PR that "just bumps the timeout" — separated in time from the original review and from the code under test — is the case where a real performance regression is most likely to slip through unexamined. By the time the question is "should we bump 60 → 120?", the change that actually slowed the test down has often already landed, and the timeout bump is a workaround that hides it.

Treat any post-merge timeout adjustment as an escalation, not a routine cleanup:

- **The default response to a post-merge timeout failure is investigation, not adjustment.** Reproduce locally, identify what changed (a dependency bump, a new code path, a CI host shift), and fix the underlying slowdown. The original timeout, chosen against measurement at merge time, is the contract — exceeding it is evidence of a regression somewhere.
- **Never respond by just re-running the test.** Re-queuing CI hoping the timeout does not fire this time is gambling, not engineering ([Flaky Test Policy](#flaky-test-policy)). An intermittent timeout is a flake with a root cause to investigate and fix, and even a lucky green re-run leaves that defect in place.
- **If investigation shows the timeout never had the required headroom** — your PR's CI trips a timeout on a pre-existing test, and measuring the code *without* your change shows healthy runs were already landing near the bound, well inside the 2x buffer this document requires — then the mis-sized budget predates your PR. Raise this to the user or project owner rather than silently absorbing it, and propose a separate, focused PR that brings the value into the 2x–5x range and clearly documents the measured healthy execution time *prior to* your change, so the record shows the resize corrects an original under-sizing rather than a slowdown your PR introduced.
- **If the timeout genuinely needs to change** — for example, the test legitimately expanded in scope, or a fresh measurement on the current code shows the original budget was too tight — the change requires **explicit approval from the project owner**, with the new measurement attached to the PR, justified the same way a fresh `@Timeout` is justified at merge time.
- **Never adjust a post-merge timeout silently as part of an unrelated PR.** If the value is wrong, the change deserves its own focused review with its own justification — not a one-line drive-by bump buried in a feature diff.

### Import

```kotlin
import build.kotlin.annotations.Timeout
```

### Usage

`@Timeout` is placed directly on the test function and takes a single argument: the timeout in seconds.

```kotlin
@Timeout(120)
fun stressTestConcurrentRpcRequestsFromSingleClient() {
    // This stress test runs multiple iterations with concurrent requests,
    // which can take longer than the default 30-second timeout.
    val iterations = 5
    val concurrentRequests = 5
    // ...
}
```

### When NOT to Use @Timeout

- **Do not use `@Timeout` to mask flaky tests** — If a test intermittently exceeds the default timeout, the root cause is likely a bug in the code under test (a deadlock, thread starvation, or unbounded wait). Fix the bug instead of raising the timeout.
- **Do not add `@Timeout` to every test** — Only annotate tests that genuinely need more time. Tests that complete well within the default should not carry an annotation.
- **Do not pick the timeout by guesswork or by "bumping until it passes"** — Always measure elapsed time on a green run first, then pick a generous multiple (2x, up to ~5x) of that measurement.
- **Do not adjust a post-merge `@Timeout` without explicit approval** — Once a test is on `main`, its timeout is part of the test's contract. A bump in response to a CI failure must be treated as an escalation requiring fresh measurement and project-owner approval, never a routine cleanup or a one-line drive-by change in an unrelated PR. See [Choose the Timeout Before Merging the Test](#choose-the-timeout-before-merging-the-test).

These prohibitions protect established baselines; none of them freezes the timeout of a test still in the PR that introduces it. Until that PR merges, the value is being *chosen*, and re-sizing it against fresh healthy measurements — CI runs included — is the required sizing workflow, not a bump (see [Choose the Timeout Before Merging the Test](#choose-the-timeout-before-merging-the-test)).

## Test Memory Limit Annotation

The test runner enforces a default per-test memory cap of 128 megabytes (`-Xmx128m`) on the forked JVM that runs each test. For tests that legitimately need more heap — such as those that build or analyze a large codebase, materialize a large dataset in memory, or stress a service that itself reserves a substantial heap — use the `@BillingQuotaExecutionLimit` annotation to declare a higher per-test budget.

`@BillingQuotaExecutionLimit` is a **sanity check, not a target**. The cap exists so that a test with a runaway allocation, a leak, or an accidentally-cartesian-product expansion fails fast with a recognizable `OutOfMemoryError` rather than dragging down the whole test runner host or silently regressing the project's resource baseline. A correctly-functioning test should comfortably finish well under its declared cap — if a test is routinely brushing up against its limit, that is a bug in the test or in the code under test, not a reason to declare a larger cap.

### Sizing the Cap

The memory cap on `@BillingQuotaExecutionLimit` is **set when the test is first created**, sized to fit what the test legitimately needs with comfortable headroom above that. There is no requirement to instrument the test with a heap dump, `-XX:+PrintGCDetails`, or a JFR recording to pick a number — use the test's intent as the guide (for example, a few hundred megabytes for a test that builds a moderately-large workspace, 1 GB or more for a test that materializes a large dataset in memory). The goal is a value generous enough that a healthy run never comes close to it, but bounded enough that a leak or unbounded structure surfaces as a failure rather than as an unbounded heap.

Tests that comfortably fit under the 128 MB default should not carry the annotation at all — putting it on every test weakens the convention that the annotation marks a deliberately-larger budget.

### The Cap Should Never Need to Be Raised After Merge

**A test's `@BillingQuotaExecutionLimit` value is part of the test's contract: it is chosen in the review that introduces the test, and it should never need to be raised after that point.** A cap sized with reasonable headroom at creation time absorbs normal allocation jitter. If a test on `main` later starts hitting an `OutOfMemoryError`, that is — almost without exception — evidence of a real memory regression in the code under test: a leak, an unbounded data structure, an accidental cartesian-product expansion, a dependency that pulled in much larger objects, or a new code path holding references it should not.

The response to a post-merge OOM is **investigation and a fix in the code under test**, not a cap bump. Reproduce locally, capture a heap dump, identify what changed, and restore the test's heap footprint to what it was when the cap was originally sized. Raising the cap to silence the OOM hides the regression and erodes the cap's value as a sanity check — and is the same category of mistake as raising [`@Timeout`](#test-timeout-annotation) to silence a slow run, [increasing in-test timeouts to fix a flake](#flaky-test-policy), or reducing stress test iteration counts.

### Import

```kotlin
import build.kotlin.annotations.BillingQuotaExecutionLimit
```

### Usage

`@BillingQuotaExecutionLimit` is placed directly on the test function and takes two named arguments: `quantity` (the limit value) and `resource_name` (the resource being limited). The set of known resource names is published as constants on `BillingQuotaExecutionLimit.Companion`; for memory, use `BillingQuotaExecutionLimit.RESOURCE_MEMORY_MEGABYTES`. Prefer the constant over a string literal so that a typo (e.g., `"memory_megabyte"`) cannot silently fall through to the default cap.

```kotlin
// This test builds a large kompile workspace in-process and needs more
// headroom than the 128 MB default to do its job. 1 GB is generous; if the
// test later starts hitting an OOM at this cap, that is a regression to
// investigate in the code under test, not a cap to raise.
@BillingQuotaExecutionLimit(
    quantity = 1024,
    resource_name = BillingQuotaExecutionLimit.RESOURCE_MEMORY_MEGABYTES,
)
fun testCompilesLargeMonorepoEndToEnd() {
    // ...
}
```

The test runner translates the annotation into `-Xmx<quantity>m` on the forked JVM. Tests with no annotation run under the default of `BillingQuotaExecutionLimit.DEFAULT_MEMORY_MEGABYTES` (128 MB), which is intentionally tight so that ordinary tests cannot quietly consume an unbounded heap.

### When NOT to Use @BillingQuotaExecutionLimit

- **Do not use `@BillingQuotaExecutionLimit` to mask an `OutOfMemoryError`** — An OOM is a signal: either there is a leak, an unbounded structure, or the code under test really is allocating more than expected. Investigate the root cause before considering a larger cap.
- **Do not add `@BillingQuotaExecutionLimit` to every test** — Most tests should fit comfortably under the 128 MB default. An annotation on a test that does not need extra headroom is noise and weakens the convention that the annotation marks a deliberately-larger budget.
- **Do not raise a post-merge cap to silence an `OutOfMemoryError`** — Once a test is on `main`, its memory cap is part of the test's contract and should never need to be raised. A post-merge OOM is evidence of a regression in the code under test; fix the regression, not the cap. See [The Cap Should Never Need to Be Raised After Merge](#the-cap-should-never-need-to-be-raised-after-merge).

## See Also

- [Kotlin Build](../projects/KotlinBuild.md) — Build system documentation including test selection, test execution, and CI integration
- [Embedded Testing](EMBEDDED.md#testing) — Testing the domain logic implementation
- [ServiceServer Testing](SERVICE_SERVER.md#testing) — Testing `url://` protocol hosting and RPC routing
- [CLI Testing](CLI.md#testing) — Testing command-line output and argument parsing
- [WUI Testing](WUI.md#testing) — Testing web UI endpoints and HTML rendering
- [Website Testing](Website.md#testing) — Testing landing/marketing site pages and any embedded dynamic content
- [GUI Testing](GUI.md#testing) — Testing desktop GUI with Compose Multiplatform
- [Service Architecture Overview](STANDARD_LAYERED_ARCHITECTURE.md) — Full architecture documentation

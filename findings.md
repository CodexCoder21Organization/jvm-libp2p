# rev53both landing review
Started 2026-10-19 00:38 UTC; wall deadline 01:38 UTC.

Plan:
- IN PROGRESS: read campaign, prior findings, required architecture guidance, repository context and exact PR diff.
- TODO: adversarial pass over loop ownership, inline decision, worker failures/cancellation, callbacks, replay and queued close.
- TODO: inventory N1 proofs and run targeted fork tests plus product-only reversal of two-connection proof.
- TODO: determine zero-job startup_failure from workflows and GitHub metadata; check PR description.
- TODO: checkpoint findings/logs on wip/rev53both-evidence; audit processes; final result.

Constraints: read-only PR branch; no delegate, CI rerun/watch, merge, artifact publication, handoff mutation or service mutation. Evidence branch only.

## 2026-10-19 00:39 UTC — first audit checkpoint
OBSERVED: Fresh clone, configured identity, README read, no AGENTS.md in root. PR is OPEN/DRAFT at exact head 5611b7763c4a4580135e85655dfb2e2c8a83edb3, base develop 031e565a. Fork has no main base for this review; fetched/rebased detached head against its actual develop base (no-op) before targeted Gradle build. Read dial3 findings fully, dial1 capture findings, campaign handoff and requested testing sections. Required reference source copies retained for review.
OBSERVED: PR workflow has no diff against develop. Public GitHub run page contains explicit startup annotation: actions/checkout@v3, actions/setup-java@v3, gradle/gradle-build-action@v2, android-actions/setup-android@v3 are disallowed because all actions must come from repositories owned by the enterprise. Recent develop runs at 031e565a already have startup_failure, zero jobs. This is a pre-existing Actions policy/workflow mismatch, not code/test/runner-label failure introduced by this PR. Read-only REST run metadata and page retained; no CI rerun/watch.
OBSERVED: Adversarial source pass traced handlerAdded, channelActive/read, every enqueueCrypto admission, worker FIFO, split, success/failure, remove, unregistered and observer callbacks. No change to frame-replay order. Registry entries remain through future publication and are removed in finally. Loop mutation and pending-state reads occur on the loop; workers receive an immutable count snapshot. The read cannot cover later OS arrivals or tasks enqueued after admission; a newly arriving task can wait behind at most the current bounded handshake computation, and subsequent admissions inspect again. README describes choice at admission, not future-arrival exclusion.
INFER: No P1 mechanism identified yet. The existing fixed pool, per-channel queued closure retention after close, static calculation, and shared-dial cancellation are unchanged and explicitly later concerns. Targeted tests (all Noise plus poisoned-provider child) requested under the blocking local slot with a 1200s outer limit; first-attempt native results to follow. No whole suite requested and no test/product edit made.

OBSERVED lock stamps: requested at approximately 00:39:26 UTC and ACQUIRED=2026-10-19T00:39:26Z (under one second); the later WAIT print was 00:39:27Z, after launch, so it is not used to infer a negative wait.

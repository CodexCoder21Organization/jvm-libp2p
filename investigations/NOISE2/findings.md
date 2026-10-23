# NOISE2 findings
Started: 2026-10-23 UTC.

Plan
- IN PROGRESS: Read repository guidance, testing guidance, and prior review evidence; establish develop baseline.
- PENDING: Reproduce both count mismatches and identify their exact ordering and contract.
- PENDING: Add consistent public-path coverage, verify initial red results, then implement and verify any justified changes.
- PENDING: Record reversal results, at least 200 first attempts, the applicable complete suite, review, and PR against develop.

Constraints: no merge, queue, publication, deployment, timeout increases, iteration reductions, or assertion weakening. Stop and report if an assertion is incorrect.

OBSERVED 02:24 UTC: Fresh own clone; origin/develop has no origin/main counterpart. The requested preceding PR remains OPEN at https://github.com/CodexCoder21Organization/jvm-libp2p/pull/69. Branch is based on its full head 812a22c71175817223c80fe69f8c8ba446bbae44, which already contains current develop. No repository AGENTS.md and no scripts/test.bash. Repository-native Gradle tests apply; generic kompile commands do not exist here.
Reading: README.md; FORK.md; prior R69 findings and final; live TESTING.md including Test-Driven Development and Never Calibrate a Reproducer to a Machine; fix-flakey-test skill. Remaining general guidance will be checked as needed.
OBSERVED: NoiseXXSecureChannel.kt:535-536 selects workers when either another handshake or any task is pending on the loop. README explicitly documents queued-work offloading. Sequential harness :567-568 waits for connection closure and registry removal, not loop queue completion. Saturation harness :650-662 samples connection results and executor queue independently.
INFER: Sequential assertion may be stronger than its setup guarantees. Saturation queue count could include a task whose handshake already closed or a task scheduled more than once; neither is established yet. Need per-channel scheduling and public completion observations. No fix proposed.

OBSERVED 02:30 UTC: Initial native Gradle class run: 29/29 cases pass, zero errors, zero skips, no retry configuration. Exact head was https://github.com/CodexCoder21Organization/jvm-libp2p/commit/812a22c71175817223c80fe69f8c8ba446bbae44. Temporary per-channel scheduling diagnostics are being compiled. They report channel ID, role, frame number, loop pending count, task count, and selected executor; no assertion or behavior change.
INFER current mechanism candidates: sequential close completion can precede event-loop task completion, making a later handshake legitimately use workers; saturation queue size might count more than one task per connection. The exact reason for that count remains unverified. No fix proposed.

OBSERVED 02:33 UTC: Saturation mode run 14 reproduced the reported count assertion on its first attempt: 121 queued tasks versus 120 failed dials. Scheduling trace contains 121 distinct responder channel IDs, each selected once at frame 1, and 120 distinct initiator channel IDs. This rules out duplicate scheduling for one responder channel in that run. Additional connection origin is not yet established; next trace adds socket endpoints. Sequential repeated runs remain green so far. Local diagnostic batch stopped after the useful observation to add endpoint tracing; incomplete active runs are excluded, with no replacement of completed results.
The mechanism is not yet fully established: the queue receives work from one more responder connection than the test owns; the evidence that demonstrates this difference is 121 distinct responder IDs versus 120 distinct initiator IDs in saturated014.log. Whether that additional connection comes from another process or from local setup remains to be determined. No fix proposed.

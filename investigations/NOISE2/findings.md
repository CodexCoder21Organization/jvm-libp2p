# NOISE2 findings
Started: 2026-10-23 UTC.

Plan
- IN PROGRESS: Read repository guidance, testing guidance, and prior review evidence; establish develop baseline.
- PENDING: Reproduce both count mismatches and identify their exact ordering and contract.
- PENDING: Add consistent public-path coverage, verify initial red results, then implement and verify any justified changes.
- PENDING: Record reversal results, at least 200 first attempts, the applicable complete suite, review, and PR against develop.

Constraints: no merge, queue, publication, deployment, timeout increases, iteration reductions, or assertion weakening. Stop and report if an assertion is incorrect.

OBSERVED checkpoint: Fresh own clone; origin/develop has no origin/main counterpart. The requested preceding PR remains OPEN at https://github.com/CodexCoder21Organization/jvm-libp2p/pull/69. Branch is based on its full head 812a22c71175817223c80fe69f8c8ba446bbae44, which already contains current develop. No repository AGENTS.md and no scripts/test.bash. Repository-native Gradle tests apply; generic kompile commands do not exist here.
Reading: README.md; FORK.md; prior R69 findings and final; live TESTING.md including Test-Driven Development and Never Calibrate a Reproducer to a Machine; fix-flakey-test skill. Remaining general guidance will be checked as needed.
OBSERVED: NoiseXXSecureChannel.kt:535-536 selects workers when either another handshake or any task is pending on the loop. README explicitly documents queued-work offloading. Sequential harness :567-568 waits for connection closure and registry removal, not loop queue completion. Saturation harness :650-662 samples connection results and executor queue independently.
INFER: Sequential assertion may be stronger than its setup guarantees. Saturation queue count could include a task whose handshake already closed or a task scheduled more than once; neither is established yet. Need per-channel scheduling and public completion observations. No fix proposed.

OBSERVED checkpoint: Initial native Gradle class run: 29/29 cases pass, zero errors, zero skips, no retry configuration. Exact head was https://github.com/CodexCoder21Organization/jvm-libp2p/commit/812a22c71175817223c80fe69f8c8ba446bbae44. Temporary per-channel scheduling diagnostics are being compiled. They report channel ID, role, frame number, loop pending count, task count, and selected executor; no assertion or behavior change.
INFER current mechanism candidates: sequential close completion can precede event-loop task completion, making a later handshake legitimately use workers; saturation queue size might count more than one task per connection. The exact reason for that count remains unverified. No fix proposed.

OBSERVED checkpoint: Saturation mode run 14 reproduced the reported count assertion on its first attempt: 121 queued tasks versus 120 failed dials. Scheduling trace contains 121 distinct responder channel IDs, each selected once at frame 1, and 120 distinct initiator channel IDs. This rules out duplicate scheduling for one responder channel in that run. Additional connection origin is not yet established; next trace adds socket endpoints. Sequential repeated runs remain green so far. Local diagnostic batch stopped after the useful observation to add endpoint tracing; incomplete active runs are excluded, with no replacement of completed results.
The mechanism is not yet fully established: the queue receives work from one more responder connection than the test owns; the evidence that demonstrates this difference is 121 distinct responder IDs versus 120 distinct initiator IDs in saturated014.log. Whether that additional connection comes from another process or from local setup remains to be determined. No fix proposed.


STOP — existing sequential assertion is incorrect for the setup it controls

The mechanism is that the sequential harness assumes every admission sees an empty loop queue, while it waits only for connection close and registry removal; the evidence that demonstrates this is the unchanged assertion returning 39 inline plus 1 worker handshake in two independent child JVMs, each with pending=1, tasks=1, workers=true, observed=false at that responder's first admission. NoiseXXSecureChannel.kt:535-536 expressly selects workers when queued work exists, and README.md documents that contract. The scheduler therefore follows the documented rule in these observations. There is no evidence here that worker execution itself is a production behavior defect.

Exact assertion at NoiseHandshakeOffEventLoopTest.kt:572:
check(execution.inlineHandshakes == SEQUENTIAL_HANDSHAKES * 2 && execution.offloadedHandshakes == 0)
The assertion also requires inlineCryptoTasksOffEventLoop == 0 later in the method; no assertion was edited. The preceding setup at :567-568 waits on close and connection lists, without establishing an empty event-loop task queue. A single queued task is sufficient to make the all-inline assertion inconsistent with the documented scheduling contract. The origin and contents of that pending task were not identified; that is not required to establish that the test leaves its queue precondition uncontrolled.

OBSERVED trace evidence
- modes/sequential069.log: responder first admission pending=1, tasks=1, workers=true, observed=false; final counts inlineHandshakes=39, offloadedHandshakes=1, inlineCryptoTasks=78, inlineCryptoTasksOffEventLoop=0, offloadedCryptoTasks=2, offloadedCryptoTasksOnEventLoop=0.
- addresses/sequential016.log: the same first-admission state and the same final counters in a different child JVM, with local /127.0.0.1:39487 and remote /127.0.0.1:55566.
- Saturation modes/saturated014.log: queue=121 and failed dials=120. Trace contains 120 distinct initiator channels and 121 distinct responder channels; each responder has one frame-1 queue selection. These sets have no channel-ID overlap. The extra connection's origin is not established. No production or test classification is claimed for this second issue.
- Readable numeric traces and source-log SHA-256 values are retained in the evidence branch. Temporary production tracing has been removed. add_trace.py records how to recreate it; it changes reporting only, after the scheduling decision. No implementation fix was made.

Final first-attempt results (no retries or result replacement)
| Selection | Runs | Pass | Red |
| --- | --- | --- | --- |
| Complete target class, initial | 1 class / 29 cases | 29 cases | 0 |
| Sequential mode, first trace batch | 84 | 83 | 1 |
| Sequential mode, endpoint batch | 41 | 40 | 1 |
| Saturation mode, first trace batch | 27 | 26 | 1 |
| Saturation mode, endpoint batch | 12 | 12 | 0 |
These are 125 sequential mode runs and 39 saturation mode runs, not 200 complete class runs. The first trace batch had one active run of each mode stopped before completion to add endpoints; neither is included. The second batch finished its active runs and then exited on a stop request. All completed red results remain counted.

Bar and explicit closeout
1. DONE: fresh own checkout, baseline includes the entire requested preceding PR head, native Gradle setup, required testing sections and prior evidence read. Later preparation successfully rebased on current origin/develop. There is no origin/main in this repository.
2. DONE as a refutation of the all-inline premise: two observed first-attempt sequential mismatches follow the documented queued-work worker rule. This triggers the brief's explicit instruction to STOP and report if an assertion is wrong. No assertion change is proposed or made.
3. NOT MET: >=50 percent reproduction, deterministic regression, fix/green/reversal, >=200 complete class attempts, and fix PR. None is claimed. Further diagnosis stops under the explicit assertion rule, rather than presenting the natural low-frequency samples as a reliable reproducer.
4. NOT RUN: complete suite. No shared contract, fixture, interface, status model, or implementation was changed by this lane, and the explicit stop rule applies. This repository has no scripts/test.bash, so no generic kompile command was substituted for native Gradle.
5. NOT DONE: the saturation extra-connection origin and its production-versus-test classification. Current evidence rules out duplicate queue submissions for one responder channel in the observed run; it does not explain the extra channel. This remaining uncertainty is explicit.
6. DONE: findings and final deliverables, evidence checkpoint, temporary source restoration, tracked local command completion, and process scan. No PR, merge, queue request, publication, deployment, production change, timeout increase, iteration reduction, test removal, or assertion weakening.

Preparation notes: the first classpath-writing helper used the script directory instead of the project directory and returned FileNotFoundException; p.file corrected it. Two early rebase attempts reported uncommitted tracing changes; subsequent preparation stashed tracing, rebased successfully, and restored it before compilation. These were local preparation mistakes, not test results or ecosystem defects.

Evidence branch: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/NOISE2-handshake-order/investigations/NOISE2

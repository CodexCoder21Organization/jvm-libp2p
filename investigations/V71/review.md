# V71 correctness review

Exact reviewed product head: 587aad5f1c7fda72a561f0f7bb09dfd8f63b8939 against origin/develop. The reviewer made no edits and ran no builds or tests. No demonstrated PR correctness issue was found.

| Resource | Ownership and terminal behavior | Existing public coverage |
| --- | --- | --- |
| Netty conversion | Source owns its result; conversion owns a separate Java result. Success, original error, and cancellation transfer once. Caller mutation does not mutate source. | CompletedNettyFutureConversionTest, NettyFutureConversionStateTest, PendingNettyFutureConversionTest: full/stopped executors, registration ordering, pending outcomes, caller cancellation. |
| Listener | Direct completed path registers none. Pending and registration-window paths use one listener, as before. Netty owns notification/clearing. | Registration ordering tests cover completion on both sides of registration. |
| Child write | One parent result is retained until child-loop delivery or child close/shutdown. Close observes an already-completed result before disposing its buffer. | CompletedStreamWriteNotificationTest and existing child-loop/shutdown scenarios cover terminal outcomes, close, unflushed writes, and loop recovery. |
| Connection close | Channel state owns original/latest attempts. Ordinary calls share original; a new attempt is permitted after a completed error. Caller dependents do not mutate owned attempts. Callback depth is cleared in finally. | Existing re-entry and cross-thread close-ordering scenarios cover active callbacks. |

Direct terminal observation may complete on the calling thread. README.md:218-223 explicitly describes it; Stream.kt:30-39 promises when the result becomes known and does not promise a completion thread. Duplicate transfer is harmless because CompletableFuture accepts one terminal value. No new retained collection or pending result was found.

The active-notification/full-queue hypothesis was refuted against pinned Netty 4.2.10.Final (versions.gradle:34): DefaultPromise notification drains listeners added during a current callback before finishing. Official reference: https://netty.io/4.2/xref/io/netty/util/concurrent/DefaultPromise.html . A concurrent first lazy read was considered, but no public wrong result was demonstrated; its listener branch is also present on develop. That is an unproven timing observation, not a product finding.

Read: complete repository README.md; DocumentationRepository architecture/TESTING.md, including Test-Driven Development and Never Calibrate a Reproducer to a Machine; PHILOSOPHY.md. The pinned Netty DefaultPromise artifact was inspected without executing tests.

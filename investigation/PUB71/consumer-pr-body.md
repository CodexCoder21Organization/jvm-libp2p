The owner reported that https://droplets.wasmserver.com/ "is super unreliable, frequently rendering various errors/notices due to bugs in UrlResolver or UrlProtocol or something else" and asked for the underlying root cause to be fixed; the ContainerNursery route stops the WUI after 5 idle minutes, so most human visits are cold starts.

This PR's latest merge-queue check stopped in `testRejectedDrainLoggingDoesNotHoldOutboxMonitor` during server-host cleanup. The queue investigation found that an already-completed Netty close result could lose its listener notification when its executor queue was full; the converted Java result then remained pending even after the queue drained. The deterministic consumer assertion is `A completed physical close must remain observable after its event-loop queue has drained`.

Select `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-41` in the production declaration and direct test annotations. This release was built from merged commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/25efb1996bd79e59e6eef28d2d44d9a0405fdd78 and carries https://github.com/CodexCoder21Organization/jvm-libp2p/pull/71. Completed results are observed directly, preserving the upstream result when listener dispatch is unavailable. The new real-host scenario checks visibility after the full queue drains. The existing cleanup test body remains intact. The original logging-test adjustment keeps its transport check beyond the 256 KiB compact-copy boundary.

Validation:
- Published jar and POM downloaded SHA-256 values match the built files. Retrieval from a new Coursier cache resolves 47 jars and matches both snapshot-41 files.
- Completed-close baseline on snapshot-25: same assertion on attempts 1/2/3; snapshot-41: attempt 1 passes; return-to-snapshot-25 control: same assertion on attempts 1/2/3; restored snapshot-41: attempt 1 passes.
- Original cleanup scenario: 10/10 independent runs pass on attempt 1. Existing PR target and two related queue checks each pass on attempt 1.
- Complete suite: PENDING_RESULT.

Publication and consumer findings: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/PUB71-publication-evidence/investigation/PUB71/findings.md . Earlier queue evidence: https://github.com/CodexCoder21Organization/UrlProtocol/blob/wip/MQ945-queue-evidence/investigation/MQ945/findings.md . No production service was changed.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

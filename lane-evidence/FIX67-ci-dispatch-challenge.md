# Kotlin check suite remains queued without any dispatched run

While watching https://github.com/CodexCoder21Organization/jvm-libp2p/pull/67 at 43eab3dc7f2a03e8144b9fa99cf3811cb0fbda4d, build-watchman 0.0.21 reported suite 108095815110 queued with ZERO dispatched runs for 3151 seconds. The app is kotlin-build-ci-test. A sampled develop suite 107747761343 at e15e0f19 completed with one run, so the app has dispatched on this repository before. There is no current remote build log because no run started.

Impact: informational kotlin.build(remote) cannot be observed green from this head; this is a dispatch observation, not a code failure. The exact cause (routing, filtering, delivery, or receiver bookkeeping) is unconfirmed. The same head was first pushed as a WIP checkpoint and later to the existing PR branch; no attribution to that ordering is established.

No warm/rerequest/delete, restart, enqueue or merge was performed. The lane explicitly prohibits rerequesting. Existing GitHub Actions startup_failure is a separate known policy/workflow mismatch. Suggested durable follow-up: inspect the receiver dispatch record for this SHA/suite and repair that layer; when authorized, verify a new actual run exists instead of trusting counters alone. Evidence: FIX67-watchman.log and head check-suite/check-run snapshots.

The report-challenge CLI would automatically enqueue/merge a record PR, prohibited here. This WIP evidence record preserves the observation without those actions.

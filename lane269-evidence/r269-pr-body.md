During this session's mux work, `ChildReadPauseTcpTest.offLoopPauseDuringInFlightDeliveryQueuesLaterFramesUntilResume` failed 3/20 on unchanged `develop` for Yamux. The [lane246 evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane246-yamux-readpause-20261015) and [lane237 trace instrumentation](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane237-yamux-readpause-20261015) prompted this separate correction of its TCP read assumption.

The mechanism is that the test waits for every mux frame before resuming a child whose pause disables parent reads; TCP can leave later frames in subsequent reads, so the test waits on its own pause.

Settle the queued pause with an event-loop barrier, retain the paused ordered-prefix/end-marker/parent-read assertions, then wait for the full ordered payload list and remote end after resume. The existing five-second wait bounds stay unchanged. Add native and one-byte transport-read cases for both muxers; the fragmented cases also assert that later frames still need another transport read before resume. These use real local TCP hosts and the real codecs. The allocator is installed before the first socket read because Netty caches its receive handle.

Counts below are passed / failed, per muxer:

| Check | Mplex | Yamux |
| --- | --- | --- |
| Earlier traced develop (lane246) | 18 / 2 | 17 / 3 |
| Fresh unchanged develop | 20 / 0 | 20 / 0 |
| Old assertion, native reads in the forced-case batch | 10 / 0 | 9 / 1 |
| Old assertion, forced one-byte reads | 0 / 10 | 0 / 10 |
| Fixed assertion, native reads | 20 / 0 | 20 / 0 |
| Fixed assertion, forced one-byte reads | 20 / 0 | 20 / 0 |

All old-assertion failures above reached the same payload-stage assertion. All fixed cases require the exact three payloads in order followed by the remote-end marker. The saved plaintext-coalescing fixture covers a separate negotiation issue; this reproducer forces payload read boundaries instead.

Local verification: whole `ChildReadPauseTcpTest` class five times, **30/30 passed**; mux test package, **136/136 passed**, no skips. Final formatted-source verification: scoped `spotlessKotlinCheck` passed; the class passed **6/6**, the mux package passed **136/136**, and the standard `:libp2p:test --tests 'io.libp2p.mux.*'` task passed **136/136** after the final rebase. Separate self-review passes covered test completeness and code changes; no production code changes.

The [fail-first source](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/b77a4c97bf319af3554de915cd7e1fb0402f27da), [XML results and findings](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/evidence/lane269-readpause-20261015/lane269-evidence), and the repeat/verification Gradle init scripts there preserve the reproduction and counts.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

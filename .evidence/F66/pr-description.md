The owner reported that https://droplets.wasmserver.com/ "is super unreliable, frequently rendering various errors/notices due to bugs in UrlResolver or UrlProtocol or something else" and asked for the underlying root cause to be fixed; the ContainerNursery route stops the WUI after 5 idle minutes, so most human visits are cold starts.

The negotiated capacity-aware Yamux binding distinguishes a remote child-stream capacity result from an ordinary stream close. Review found a P1 in setup completion: the protocol future reported `StreamCapacityException`, while a synchronous protocol callback could make the pending public controller report success or `IllegalStateException`. Both promoted review probes were red on their first attempt.

Capacity close recorded its cause but completed the protocol future before settling the handler result and public controller. Completing that future ran callbacks synchronously, and ordinary forwarding accepted their different result. The child now retains the handler result and settles it before protocol notification. Owned protocol/controller futures consult the recorded cause for completion, exceptional completion, cancellation, and both async completion entry points. No lock is held while callbacks run. Previously completed or cancelled outcomes remain unchanged; cancellation return values continue to agree with cancellation state.

The two review probes now also cover handler-result callbacks, direct-executor async completion, and identical cause instances across all three results. A third scenario covers pending and previously cancelled protocol/controller futures. The original probes and their reversal were red on attempt 1; the async and cancellation checks also went red before their corrections.

Validation:

- 27 capacity cases (24 original, two promoted probes, one cancellation scenario), 242 existing mux/negotiation controls, and 56 lifecycle controls passed on attempt 1, without retry XML entries.
- One complete Gradle suite ran through the shared slot: 1,214 cases, 1,191 passed, 21 red, two existing skips, no retry XML entries.
- The exact unchanged-red-set bar was not met: all 20 original full-suite red identities remain, plus `NoiseHandshakeOffEventLoopTest` / `initiator times out silent responder while waiting for message 2()`. This additional result expected `ReadTimeoutException` and observed `ConnectionClosedException`. It passes one standalone head selection and eleven independent selections on the unchanged PR head. Its test source is identical; this case ends before the changed mux setup paths. Its cause remains unexplained, and no Noise code or assertions were changed. No second complete suite was run.

The independent code/test reviews and first-attempt XML evidence are on [the evidence branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/laneF66-evidence), with [findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/laneF66-evidence/.evidence/F66/findings.md) and [complete-suite comparison](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/laneF66-evidence/.evidence/F66/full-suite/summary.json).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

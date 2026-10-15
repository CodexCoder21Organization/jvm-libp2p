# Invariants before the owning-layer correction

| Resource | Creator/owner/closer | Terminal state and caller contract | Public test |
|---|---|---|---|
| Physical root | Transport creates; Host/network owns and closes | closeFuture completion is terminal, even when a connection callback closes reentrantly during mux publication | Close root from Host.addConnectionHandler, then open a stream using that exact Connection |
| Mux activation future | Mux handler creates and owns; activation succeeds it, root closure fails it | A root that closes before activation must fail every queued child open, even if its handler never receives channelUnregistered | Open child from connection callback before closing root; assert both stream/controller futures fail |
| Stream/controller promises | Host.newStream creates; caller observes, caller owns resulting child only | Both halves complete exceptionally on a closed root, preserving ConnectionClosedException; no timeout-only completion | Same scenarios check both returned futures and cause type |
| Shared mux implementation | Yamux and Mplex use AbstractMuxHandler | Both follow the same closure contract; a successful handshake is not a readiness promise after root close | Both public scenarios for each muxer |

Observed fail-first baseline: real TCP/Noise public-API close-during-publication probe fails 3/3 on published runtime with ROOT_CLOSED=true and both returned futures unfinished. Each controller get ends in TimeoutException instead of ConnectionClosedException. No timing calibration, mocks, internal mutation or artificial load.

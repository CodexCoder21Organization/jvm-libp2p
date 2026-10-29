# Close-path audit (snapshot-41 exact source)

- PlainNettyTransport.close, line 185: confirmed success-only chain skips private acceptor-group shutdown after failed listener or accepted/dialed channel close. Four-case test written, baseline queued for JVM slot.
- QuicTransport.close, line 150: same chain skips per-instance worker-group shutdown after failed UDP listener close. Added real QUIC public connection test, baseline to follow TCP test sequentially.
- PlainNettyTransport shared worker group: process-owned and daemon, explicitly must outlive every single transport; no missing per-instance shutdown.
- HostImpl.stop: invokes Network.close directly in allOf, no resource-release stage chained after success.
- NetworkImpl.close: starts transport and connection closes before allOf; its thenApply changes result type only, owns no release.
- NettyChannelClose: catches synchronous physical-close exceptions and records original completion; no deferred resource release after success-only stage.
- P2PChannelOverNetty.close and ConnectionOverNetty.close: hand off physical channel close and observation; no separately owned executor/timer cleanup in success-only stage.
- StreamOverNetty.closeWrite: protocol half-close, does not own executor groups; stream.close goes through physical channel API.
- ConnectionBuilder establishment chains and transport dial/listen readiness chains are startup paths, not close stages. Existing callback/error handlers close failed channels; no further thenCompose/thenApply cleanup stage found there.

Additional test surface: cancellation of returned transport close future while a listener close is pending can prevent a success-only thenCompose action from running; add a deterministic public-API regression before selecting the final completion shape.

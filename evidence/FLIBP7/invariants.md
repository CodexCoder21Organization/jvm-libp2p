# FLIBP7 ownership invariants

| Resource | Creation and owner | Required close/terminal state | Caller behavior and in-flight work | Public proof |
| --- | --- | --- | --- | --- |
| TCP/WS acceptor group/listeners | Each PlainNettyTransport owns acceptor group, listener channels | Group termination follows all listener/root outcomes, including failure | Caller cancellation/manual completion cannot cancel private cleanup; re-entry preserves original close outcome | Existing PR failure parameter cases and larger close-set case |
| TCP/WS shared workers | Process owns shared worker group | Per-transport close leaves shared workers usable | Other hosts continue serving | Existing PR cases and daemon/concurrent/timing neighbors |
| QUIC client UDP parents | Each dial binds its own UDP parent; the dialing QuicTransport owns it | Every parent must be closed, unregistered and have a successful physical close future before owner shutdown is reported | Child close/failure/removal cannot discard ownership of an open parent; caller future mutation cannot discard cleanup | Preserved four-dial regression, immediate public state assertions before finally; planned already-closed child neighbor |
| QUIC child connections | Dial creates outbound child on UDP parent; client owns outbound connection | Every captured child closes and its physical future settles, while original operation failure stays observable | One failed child close must not skip sibling/parent cleanup | Four-dial regression with one physical-release-then-failed close |
| QUIC listener/server group | Independent server transport owns one UDP listener and accepted children | Closing client leaves server group/listener alive; server close owns its own resources | Client failure cannot end another transport | Four-dial regression independent-server assertions |
| Returned close future | Transport creates private cleanup then caller observation | Mutation affects only caller observation | Cancellation/manual completion/re-entry must not stop underlying cleanup | Existing caller-cancel/caller-complete parameter cases |

No persisted transport state survives restart. Test fixtures release resources in finally; all product assertions happen before that cleanup. No existing default-branch tests may change.

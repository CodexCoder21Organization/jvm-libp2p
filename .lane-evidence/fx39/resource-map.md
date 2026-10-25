# Observed resources on the alias request path

This is an investigation map, not a proposed fix. The latency mechanism is still unproven.

| Resource | Creator / owner | Completion / close | Caller-visible contract | Potential overlap with startup |
| --- | --- | --- | --- | --- |
| RelayBackedRpcController request/response pipes and worker | Resolver creates the controller; PersistentRpcConnection owns it | Controller close closes all four pipe endpoints and interrupts the worker | Each accepted RPC has its own remaining budget and one response ID | Source worker runs independently, but asks resolver/registry/relay for a route |
| Direct/relay dial tasks | RelayService caller; request-owned dial clock and tracked active candidates | A winner commits one connection; losing/late connections close; service close cancels in-flight work | Send once on committed path; never silently replay an ambiguous result | Host connection setup and transport event loops also carry gossip |
| RelayServerMessageHandler dispatcher and message mutex | One per accepted relay child | Stream close schedules cleanup under same mutex | Preserve per-stream message/cleanup ordering; asynchronous forward must release stream processing | Gossip frames and relay frames share a child only where the route actually uses the registered stream |
| Pending forwarded request | RelayService creates relay-local forward ID and future | Provider reply or terminal path removes entry and returns source response | Restore source request ID; matching reply belongs to the original forward | Completion callback uses CompletableFuture async execution |
| Registered target listener callback admission | One bounded dispatcher per registration connection | Connection close clears pending work and interrupts admission worker | Control acknowledgements are matched before application callbacks; request dispatch follows callback admission | Startup peer/service announcements can occupy callback handoff capacity |
| Registered target handler slot | Listener admits before handing to per-task virtual worker | Response admission or terminal cleanup releases permit once | Handler must execute once for an accepted request | Separate handler executor; no direct gossip queue ownership |
| Resolver application-handler worker | Relay handler submits to resolver rpcReadExecutor | Completion future unblocks caller; resolver close cancels owned tasks | Service request and returned response retain one request ID | Cached worker pool separate from resolver gossip dispatcher |

For any fix that changes these ownership/order relationships, derive public-API tests from the rows affected before implementing it. No shared executor or lock has yet been measured as carrying the extra second.

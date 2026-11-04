# Ownership and lifecycle invariants before further changes

| Resource | Creator/owner | Who closes it and terminal state | Caller mutation/reentry/close in flight | Public test |
|---|---|---|---|---|
| TCP/WS acceptor group and listener | Transport instance | Transport.close; termination required even if a listener or connection close fails | Returned future mutation cannot cancel private cleanup; pending physical close must settle before shutdown | PlainNettyTransportFailedCloseTest listener, connection, listener-throw, listener-deferred, caller-cancel, caller-complete, success |
| TCP/WS shared worker group | Process/shared transports | Per-transport close must leave it running for other hosts | Closing one transport must not break another | FailedCloseTest shared worker assertion; existing daemon/concurrent shutdown tests |
| QUIC worker group and listener | QUIC transport instance | Transport.close must terminate all executor threads | All failure/caller-mutation cases preserve cleanup and original failure | QuicTransportFailedCloseTest all six parameter cases |
| Caller shutdown observation | Transport.close creates caller future | Caller owns its observation; underlying close is owned privately | Cancellation/manual completion cannot terminate underlying cleanup | Both caller-cancel/caller-complete cases |
| Injected failed-close fixture socket | Test creates real connection/listener | Fixture releases physical socket before failed promise is published; product owns group shutdown | Cleanup cannot submit handler removal after loop termination | Upstream failed-close tests assert socket closed, group terminated and each thread dead before finally |

No persisted state is involved in these transport lifetimes. Consumer fixture invariants will be recorded after reading its actual failing cases.

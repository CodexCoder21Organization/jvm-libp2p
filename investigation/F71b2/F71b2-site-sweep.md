OBSERVED: Registration sweep at requested head: 23 sites (20 listeners, 3 PromiseCombiners). Converter call paths are listed separately to avoid double-counting registrations.

| Sites | Count | Dispatch/ownership reason | Existing guard or round-2 guard |
|---|---:|---|---|
| NettyExt channel/generic converters | 2 | Completed results copied directly; post-attach terminal recheck; pending sources use normal executor | CompletedNettyFutureConversionTest; pending/state additions |
| PlainNettyTransport listener/channel removal | 2 | Cleanup observers registered before ordinary close; physical close occurs on owning loop; close owner is separate | ConcurrentListenClose, ParentCloseOwnership, close regression |
| QuicTransport listener/channel removal | 2 | Cleanup observers; caller bind/connect/shutdown results use corrected converters | converter guards; static trace |
| NettyChannelClose terminal branch | 1 | External terminal reads complete directly; reentrant same-loop listener ordering retained | CompletedConnectionCloseNotificationTest; ReentrantConnectionCloseNotificationTest (already on requested head) |
| StreamOverNetty write result | 1 | Off-loop completed failure previously relied on accepted listener registration | NEW CompletedStreamWriteNotificationTest rejection cases (Yamux/Mplex) |
| StreamOverNetty capacity reset | 1 | Operation runs on owning loop, catches rejected operation submission; registers result observer on same loop | existing capacity-reset tests; static trace |
| MuxHandler ready/setup close | 2 | Same-loop registration/completion; owned setup failures also forwarded independently of mutable stream observations | MuxCloseDuringPublicationTest; ProtocolSelectionCloseNotificationTest; controller-close tests |
| Mplex aggregate writes | 1 | Combiner created and finished on same parent/child loop | NEW stream parent-write cases also traverse this combiner |
| Yamux frame failure and aggregate writes | 3 | Frame observers/combiners registered and completed on same loop; failure releases retained remainder | YamuxPrefixFrameFailureTest; NEW stream parent-write cases |
| AbstractChildChannel parent close | 1 | Same-loop registration; already-closed parent has guarded submission with inline close on rejection | AbstractChildChannelCloseTest; MuxCloseDuringPublicationTest |
| AbstractMuxHandler active/pending close | 1 | Same-loop close; channelUnregistered is an additional terminal path | MuxCloseDuringPublicationTest |
| TotalTimeoutHandler close cleanup | 1 | Same-loop listener plus handlerRemoved cancellation | existing timeout/teardown tests; static trace |
| MuxChannel pending-write accounting | 1 | Listener installed before same-loop child write; releases budget on completion | BufferedHalfCloseFramesTest; new parent-write path |
| MuxChannel parent-write continuation | 1 | Listener ran but its redundant task could be rejected on full loop | NEW CompletedStreamWriteNotificationTest parent success/failure cases (Yamux/Mplex) |
| TLS completion/close | 2 | Registered before handler activation; Netty publishes completion on owning channel loop | TlsSecureChannelTest; static trace |
| Noise outbound completion | 1 | Prior review traces same-loop write/completion; no protocol source reread | requested readiness/failure/traffic/handshake classes |

OBSERVED: Connect/bind/shutdown/QUIC stream-open call paths use the corrected converters. TCP stream-open uses an owned Java future with rejected submission forwarded from thenRunAsync; it is not another Netty listener registration. Same-loop rows describe their normal owner and registration boundaries, not a guarantee for arbitrary off-loop mutation after executor termination.

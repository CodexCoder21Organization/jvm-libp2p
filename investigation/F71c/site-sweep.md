OBSERVED: Audited the 23 logical notification sites from round 2, plus the new retained-write termination observer: 24 sites total. Twenty existing guards/ownership boundaries remain; three original completed-result/delivery sites were corrected this round, and the new termination observer has an explicit lifecycle guard (four new guards total).

| Notification site | Count | Guard / ownership evidence |
|---|---:|---|
| Channel and generic converters | 2 | Copy terminal result and recheck registration; pending sources retain the documented normal listener-executor contract. |
| TCP close cleanup | 2 | Installed during channel ownership registration; collection removal does not gate the caller's independent close operation. |
| QUIC close cleanup | 2 | Collection cleanup; caller bind/connect/close results use the converters and close owner independently. |
| Physical connection close | 1 | NEW: explicit public close-callback scope preserves reentry order; unrelated terminal observations transfer directly, including depth-eight calls. |
| Stream write result | 1 | Corrected converter plus original failure precedence; exact rejection message now asserted for both stock muxers. |
| Capacity reset result | 1 | NEW: terminal converter replaces bare completed-future listener registration; both muxers red/green at depth eight. |
| Mux readiness and stream setup | 2 | Owned setup/controller failure paths and ordinary pending-source notifications; setup-close coverage retained. |
| Mplex frame aggregation | 1 | Pending combiner attached before writes finish on the parent context; child delivery has its separate retained-result guard. |
| Yamux frame failure and aggregation | 3 | Pending frame/combiner ownership on parent context; failed prefix releases remainder; child delivery guarded separately. |
| Child parent-close observation | 1 | Pending listener installed during registration; already-closed parent uses guarded submission with inline fallback. |
| Mux active/pending-close observation | 1 | Owned active/setup results; channelUnregistered supplies an additional terminal lifecycle path. |
| Negotiation timeout cleanup | 1 | Close listener plus handlerRemoved cancellation and bounded timeout owner. |
| Child write accounting | 1 | Attached before the write; payload/result disposal on the owner loop, or exclusive disposal after owner termination. |
| Parent write continuation | 1 | NEW: same-loop inline completion on rejection; retained delivery for another executor, identity guard against stale completion, cancellation on child close/shutdown. |
| Existing connection-setup completion/close observers | 3 | Pending callbacks installed before activation; unchanged lifecycle/failure owners, covered by the requested setup/handshake tests. No protocol source copied into this record. |
| Retained-write termination observer | 1 | NEW: installed before parent callback for owned autonomous loops; caller-driven loops skip unsupported lifecycle APIs; removed on settlement/close; shutdown hook closes on owner loop, final termination disposes flushed and unflushed entries without new submissions to that loop. |

OBSERVED: Connect/bind/shutdown/QUIC stream-open result paths use converters. TCP stream-open forwards rejected operation submission to its owned result. The public Java close-notification wrapper and shutdown hook belong to the physical-close and retained-write rows above; they are not extra Netty listener registrations.
INFER: No remaining already-terminal observation in these paths requires an accepted listener dispatch. This is not a new guarantee for every pending Netty source completed after its listener executor stops; pending conversion retains its stated contract.

# Closed root leaves mux activation unfinished

This evidence branch starts from origin/develop 3e88aeef2e016a46565be871b367646c10ef6f65. The consumer runtime is the published snapshot-27 from 62580dfc, SHA256 483e5fca32fe3870c7b93c055885b3dce03ceace40d1b1fbfb75ee9320d8106b. The relevant MuxHandler.handlerAdded and AbstractMuxHandler activation behavior is identical in both.

The deterministic public-API probe fails 3/3 on the published runtime: a real TCP/Noise connection closes from its public connection callback, then Host.newStream on that exact closed Connection returns unfinished stream/controller futures. Controller wait reaches TimeoutException. Consumer timeline and invariants are included; no correction is accepted yet.

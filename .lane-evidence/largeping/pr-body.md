`HostTestJava.largePing` closes its TLS connection in the native suite, blocking https://github.com/CodexCoder21Organization/jvm-libp2p/pull/99 and the resolver work behind https://github.com/CodexCoder21Organization/jvm-libp2p/pull/97. The requester asked for a test-first fix without changing message sizes, iteration counts or timeouts. The recorded failing job is https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42588879399/job/129065285546.

A previous child write can remain pending after its bytes reach the peer. The next ping reply then queues without changing the incoming buffer's reference count, so the protocol adapter mistakenly releases a buffer still owned by that queue. Later decoding or writing uses released storage and the connection closes. The adapter now releases its own callback reference through Netty's normal handler behavior, and the ping responder retains a separate reply reference. This also fixes the extra reference left behind when a callback explicitly retains a message.

`ProtocolMessageHandler.onMessage` receives a borrowed message. Callbacks forwarding or storing a counted message must retain their own reference; callbacks must leave the adapter's reference for the adapter to release. The KDoc and README state this rule and show a complete forwarding example. All repository implementations, fixtures and the chat example were checked. The existing EchoProtocol already retains; PingResponder required the corresponding change.

The deterministic test sends a real 200 KiB reply over TCP/TLS/Yamux, holds its write completion, accepts the next real ping, and then releases the completion. It checks all ten replies and that the parent remains usable, without relying on host speed. Separate public Stream callback scenarios cover retention, callback failure, close and nested delivery.

First-attempt evidence, with no retries or skips:

| Register | Original | Fixed |
| --- | --- | --- |
| Concurrent large pings, ten repetitions, four callers and twenty pings per caller | FIRST 9/10 | FIRST 10/10 |
| Forced pending-write ordering, ten repetitions and ten pings per repetition | FIRST 0/10 | FIRST 10/10 |
| Four callback ownership scenarios | FIRST 0/4 | FIRST 4/4 |

REVERSAL_AND_RESTORED_RESULTS

STATIC_RESULTS

FULL_REGISTER_RESULT

Independent test and code review passes read CODE_REVIEW.md from start to finish, PHILOSOPHY.md in full, relevant TESTING.md guidance, and every touched file in full. Reports and first-attempt XML are preserved on https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/fix-largeping-evidence/.lane-evidence/largeping.

Existing-test edits: none. Six new native Gradle scenario files are added. No payload, iteration count, timeout or resource limit is reduced or raised. The branch is based directly on develop and does not copy the CI changes from the blocking pull request.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

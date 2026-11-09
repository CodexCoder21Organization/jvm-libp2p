The native failures in the [push run](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42575203649) block the [download fallback change](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/98), which is needed for the [connection change](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/97) and [production resolver fix](https://github.com/CodexCoder21Organization/UrlResolver/pull/1694). The requester asked for a deterministic reproduction and a port-allocation fix without retries, longer timeouts or skipped tests.

The [passing run of the same commit](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42575209524) used a different hosted runner (1000190759 versus 1000190757, in different Azure regions); Gradle runs these tests sequentially. In the failed run, `largePing` bound successfully and completed eight replies before TLS reported `IllegalReferenceCountException`. Its normal-tail cleanup was not reached. Later RPC and gossip tests requested the same port 40002 and failed to bind. The separate TLS buffer error remains visible and is not claimed fixed here.

RPC, gossip and Java host fixtures now request port 0 and dial the assigned address. A real-socket regression holds former ports 40001 and 40002 while running these native classes. The build workflow runs this check before its unchanged full native build and preserves both XML report sets.

First-attempt evidence, with no retries:

- `RpcHandlerTest.test1`: original FIRST 0/1 with `java.net.BindException: Address already in use`; fixed FIRST 1/1; reversal FIRST 0/1 with the same error; restored FIRST 1/1.
- Original held-port fixture checks: Java `ping` FIRST 0/1, backward compatibility FIRST 0/2, gossip large message FIRST 0/1, subscriptions FIRST 0/3, all with the same bind error.
- Complete affected classes with former ports held: FIRST 12/12: RPC 1/1, Java host 5/5, backward compatibility 2/2, gossip large message 1/1, subscriptions 3/3. The local `largePing` pass does not establish a fix for its earlier TLS error.
- Spotless and detekt pass. Workflow contract checks pass 2/2.
- Full register: PR CI pending; no completed full-register passed/total count is available at submission.

Every existing-test edit is isolated in the `Proposed test change` commit:

- `RpcHandlerTest.kt`: wildcard port 40002 becomes 0; both RPC dials use the published TCP port on loopback. All existing result, connection and stream assertions remain.
- `TwoGossipHostTestBase.kt`: ports 40002/40001 become 0; connect uses the published server address. All three consuming test classes and their assertions are unchanged.
- `HostTestJava.java`: all four network scenarios request 0, require a positive assigned port, retain full IP/port/peer-ID equality and dial the assigned loopback port. Payload sizes, iteration counts, close checks and timeouts remain.

The inventory also lists independent transport fixtures using 4002 and 21000..21100; this focused change covers the failed 40002 fixtures rather than claiming a repository-wide port conversion. No production API or implementation changed.

[Findings](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/fix-libp2p-ports-evidence/.lane-evidence/findings.md) · [Independent reviews](https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/fix-libp2p-ports-evidence/.lane-evidence/independent-reviews.md) · [JUnit XML, command logs and runner evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/fix-libp2p-ports-evidence/.lane-evidence)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

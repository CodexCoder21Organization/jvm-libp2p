# Shutdown invariants before implementation
| Resource | Creator / owner | Close / terminal state | Public observation and adverse case |
|---|---|---|---|
| TCP/WS acceptor group | PlainNettyTransport, per transport | transport.close always initiates and awaits group termination, even after channel failure | accepted ConnectionOverNetty.nettyChannel.parent().eventLoop().parent(); fail listener close, fail child close, and succeed; assert group termination and join every executor thread |
| TCP/WS worker group | process shared singleton | individual transport must not stop it | retain independent live transport and check worker still accepts work after another transport fails close |
| QUIC worker group | QuicTransport, per transport | transport.close always initiates and awaits termination after listener or child failure | accepted ConnectionOverNetty exposes QuicChannel whose parent is UDP channel; inspect group through parent event loop; fail UDP close and assert group and threads terminate |
| Listener / parent channels | transport owns registered snapshot | attempt all snapshot channels, retain original failure | outbound Netty pipeline close failure, verify original exception identity after cleanup |
| Host/network close | Host.stop delegates to Network.close, snapshots transports and connections | await all started close futures | no success-only continuation owns further cleanup; audit and record |
| Caller close future | transport returns derived operation | caller mutation must not cancel independent group shutdown | cancel one returned future with close pending, release channel promise, group still terminates |

No reflection, no public visibility changes, no main-test edits. Test faults use real Netty outbound handlers, accessed via accepted public connection handles. Re-entry and repeated close retain existing channel owner semantics; test cleanup owns all captured resources.

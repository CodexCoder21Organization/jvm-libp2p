# Ownership invariants before product edits

| Resource | Creator / owner / closer | Terminal state and caller access | Concurrent or callback case | Public test |
|---|---|---|---|---|
| Bind value | Caller creates input; transport must copy list and component bytes; native listen and wrapper use one owned copy | Caller may mutate its own list or bytes after listen returns without changing registration | Copy before inserting map key; no caller-owned reads while holding lifecycle monitor | Mutable bind list and byte-value probes |
| Dial initiation | Wrapper owns admission-to-native-registration interval | After shutdown no raw channel may be created; admitted initiation must settle before native stop, or reject before creating a channel | Snapshot caller address before admission; serialize native start and asynchronous preparation continuation with shutdown admission | Held public address read probe; source review of the native preparation continuation |
| Registered roots | Native owns physical channels; protocol root owner drains before native close | First native failure retained; repeated stop does not repeat physical close | Late handshake after failed native stop cannot initiate another physical close | R992 late dial probe plus mutation c |
| Acceptor loop | PlainNettyTransport creates and exclusively owns private bossGroup | Terminal close must shut down owned acceptor loop even if channel close fails | Native allClosed.thenCompose currently skips loop cleanup on exceptional channel close | R992 failed-listener-close queued probe |

Mechanisms proposed for findings 1 and 2: retain a deep address snapshot for listener keys, and own each dial's synchronous native initiation under the shutdown monitor, rechecking asynchronous preparation continuations. The bind reproducer changes a caller list after real bind. The dial reproducer gates a real address read and asserts no raw channel appears after stop.

Finding 5 is owned by upstream PlainNettyTransport: bossGroup is private, so a confirmed failure requires an upstream fix and a consumable artifact. This job forbids publication; no downstream cleanup workaround will be substituted.

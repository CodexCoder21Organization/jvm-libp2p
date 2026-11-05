# Shutdown invariants before code changes
|Resource|Creator, owner, closer|Terminal state|Caller, callback, close overlap|
|---|---|---|---|
|Dial UDP parent|QUIC transport bind, owned until physical close|Physically closed; native closeFuture done; operation failure retained. Normal loop also deregisters; no deregistration assertion after terminated loop.|Close children first, parent second, group last. Native rejection must not strand physical future.|
|QUIC child|Transport dial/accept, transport owns close|Physical future done before parent shutdown|A visitor may re-enter close; no late publication. Concurrent registration must join close inventory or fail.|
|Private group|Transport, transport closes|Terminated after child/parent operations settle|Other transport groups and shared TCP worker survive.|
|Shutdown result|Transport, created once before callbacks|Original result stable|Callers receive independent observers; cancellation cannot change owner.|
|Failures|Transport close stages|All distinct channel/parent/group outcomes visible|Aggregate if suppression disabled; original cause retained.|
|Long lived dials|Transport while live|Closed child releases per-dial parent and retention|Live work proportional to live inventory, not lifetime dial count.|
No state is persisted; restart from record does not apply.

# Investigation findings

## Plan

- [x] Identify the deployed GitProjectHealthServiceServer libp2p artifact/version and retrieve the exact Negotiator source.
- [ ] Trace the exception path end to end, including logging and RPC outcome; record the confirmed mechanism.
- [ ] Add a deterministic, in-process real-host reproducer in the upstream jvm-libp2p repository and capture its red run.
- [ ] Fix the underlying protocol state handling, add adjacent coverage, and record a green run.
- [ ] Review the diff, rebase, run the relevant suite locally, push a PR, and record its URL without merging or publishing.

## Milestone 1 — artifact and source version

Status: complete.

Observed:

- GitProjectHealthServiceServer is at `0590bce` (the merge of [PR 86](https://github.com/CodexCoder21Organization/GitProjectHealthServiceServer/pull/86)). Its `build.kts` directly lists `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-4`, `foundation.url:resolver:0.0.600`, and `foundation.url:protocol:0.0.332`.
- `buildFatJar()` includes the resolved dependency jars in the fat jar. UrlProtocol `0.0.332`'s published POM declares libp2p snapshot 9. UrlResolver `0.0.600` also selects UrlProtocol `0.0.337` transitively.
- I extracted the 55 `MavenPrebuilt2` roots from the server's `build.kts` and resolved them with the repository-pinned Coursier `2.1.25-M26` jar. The selected runtime versions are `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-9` and `foundation.url:protocol:0.0.337`; the direct snapshot-4 entry does not win mediation.
- Snapshot 9's release source is commit [`5335545d58b2e97246b1ed3f7a053da179c3dbd8`](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/5335545d58b2e97246b1ed3f7a053da179c3dbd8), “Restore daemon event-loop threads + shared NIO worker group (snapshot-9).” Its `libp2p/src/main/kotlin/io/libp2p/multistream/Negotiator.kt` has SHA-256 `7a77dd84b58f156fb5b1867fbaa84f96153f9cf418df4d73649f51fec4512bf7`. The terminal selection path at lines 103–111 fires the selected-protocol event, then unconditionally removes the `GenericHandler` at line 106 and each framing handler at line 109.
- In that same snapshot source, `AbstractChildChannel.doClose()` marks the child closed and synchronously calls `pipeline().fireChannelUnregistered()`; Netty's closed-channel tail handler then destroys the child pipeline. This makes teardown during a re-entrant selected-protocol callback a concrete candidate. The real-host test will confirm whether it produces the reported exception and caller result.
- The read-only production log sample shows Netty's `DefaultChannelPipeline` tail warning immediately before the exact `NoSuchElementException` stack. Netty forwards an unhandled pipeline exception to its tail logger; the server's SLF4J simple binding writes that warning and stack to stderr, which ContainerNursery prefixes with `[container:err]`.

Inference still to verify: if synchronous child teardown runs during `fireUserEventTriggered`, the subsequent unconditional `pipeline.remove(RequesterHandler)` throws. Whether the corresponding RPC attempt is retried or lost depends on the UrlProtocol caller path and is still under investigation.

## Milestone 2 — mechanism and request outcome

Status: mechanism confirmed; caller-visible outcome is established for stream setup, with the exact production caller to be covered by the regression test.

Observed in snapshot 9 source:

- `Negotiator.GenericHandler.channelRead0` calls `ctx.fireUserEventTriggered(completeEvent)` at line 105 and only then removes itself at line 106. The success event is delivered synchronously through the child pipeline.
- `ProtocolSelect.userEventTriggered` handles the success event by recording the selected protocol and installing the selected protocol initializer (`ProtocolSelect.kt`, lines 73–90). The initializer calls the selected public `ProtocolHandler` during event dispatch.
- `AbstractChildChannel.doClose()` in the exact snapshot-9 source sets the state to `CLOSED`, then synchronously calls `pipeline().fireChannelUnregistered()` (`AbstractChildChannel.kt`, lines 70–93). Netty's closed-channel pipeline teardown removes every handler before the call returns.
- Therefore, when selected protocol initialization synchronously closes a stream that is already closing, event dispatch returns to `GenericHandler` with `RequesterHandler` already removed; the old unconditional `pipeline.remove(this)` at line 106 throws the reported `NoSuchElementException`. It is re-entrant teardown on the same event-loop call stack, not two messages in one read and not a second negotiation-completion callback. This is deterministic and matches the prior upstream diagnosis recorded in [jvm-libp2p PR 26](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/26), whose merged commit added the guard after the production snapshot-9 artifact was built.
- The exception is not caught by an application handler. It escapes `channelRead0` to Netty's channel-pipeline tail, which emits the `DefaultChannelPipeline` warning and stack trace. In the exact production log this warning precedes the exception and ContainerNursery adds `[container:err]` to stderr output.
- A stream already closing cannot carry that operation to completion. On a requester host, `ProtocolSelect.channelUnregistered` completes the pending controller future exceptionally with `ConnectionClosedException`; the multistream failure is therefore more than a harmless log. UrlProtocol's local policy retries a `sendServiceRpcRequest` once after a `CONNECTION_CLOSED` transport failure, while `FlakyTestIndex` retains failed/unread reports as pending or records a sync error for a connection-open failure and retries on a later sync. The regression test will assert the libp2p controller result and the exact log event on real hosts.

Candidate explanations:

- Two messages in one read: refuted as the source of this exception. On a successful first selection, the handler is removed; a subsequent framed message cannot invoke this same handler again. The test will use a single selected-protocol event.
- Stream closed/reset: confirmed in the narrower, synchronous callback form above. A general concurrent close can produce the same ordering only when its teardown runs before cleanup.
- Completion path removes twice: refuted for snapshot 9. The second removal is child-pipeline teardown while the success event callback is on the stack; there is no separate negotiator-completion callback doing a second explicit remove.

## Milestone 3 — reproducer red run

Status: pending. I will add a loopback TCP test using two in-process libp2p hosts and public `newStream`, with a selected protocol handler that synchronously closes the requester stream. It will capture the Netty pipeline log, require the exact `NoSuchElementException` before the fix, and check the caller's controller future. A symmetric responder-close test will cover the adjacent handler type.

## Milestone 4 — fix, green run, and PR

Status: pending.

## Milestone 3 — reproducer attempt

Status: test drafted, red/green verification incomplete under the brief's time limit.

- Added `NegotiatorSynchronousCloseTest.kt` with in-process TCP libp2p hosts and public `Host.newStream`, exercising requester-side and responder-side synchronous close during selected-protocol setup. It asserts the controller fails with `ConnectionClosedException` and that the exact `NoSuchElementException` is absent from captured logs.
- Red command: `./gradlew :libp2p:test --tests io.libp2p.multistream.NegotiatorSynchronousCloseTest --no-daemon`, after replacing only `Negotiator.kt` with exact snapshot-9 source. Gradle reached `:libp2p:compileTestKotlin` but emitted no test result before the 80-minute lane deadline. I sent Ctrl-C; exit code was 130. This is not a red assertion run and does not prove the test compiles.
- Restored the current guarded `Negotiator.kt` from the pre-run backup and deleted that backup. No Gradle process should remain. No fix was changed in this branch because the root fix already exists in the fork's merged [PR 26](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/26).
- The lane deadline prevented the green run, full relevant suite, independent review, and a new PR. Do not treat the drafted test as verified.

Documents read in full before adding tests/review: `workspace/DocumentationRepository/architecture/TESTING.md`, `workspace/DocumentationRepository/CODE_REVIEW.md`, and `workspace/DocumentationRepository/PHILOSOPHY.md`.

# Independent Yamux diagnosis/test review

2026-10-27 23:34 UTC. Read-only repository review, no builds or repository edits.

## Read
- Project README.md child stream read pausing and build sections.
- AbstractChildChannel.kt, MuxChannel.kt, AbstractMuxHandler.kt.
- YamuxPausedStreamKeepsConnectionReadingTest.kt and origin/proof/w2-yamux-pause diff/workflow.
- Current handoff /tmp/W2-232805-handoff.md, particularly controlled pressure failure.
- DocumentationRepository architecture/TESTING.md and PHILOSOPHY.md (local copies).
- Neighbor TCP pause, frame-bound, manual-read and EmbeddedChannel tests named below.
- Netty 4.2.10.Final source: Channel.java, AbstractChannel.java, DefaultChannelPipeline.java, AbstractChannelHandlerContext.java from https://github.com/netty/netty/tree/netty-4.2.10.Final/transport/src/main/java/io/netty/channel.

## Finding: fixture-only correction is not justified by the documented allowance

OBSERVED: README permits at most one payload whose delivery was already in progress when an off-loop pause setter returned. The controlled proof sends every pressure byte only after the pause setter plus event-loop settle returned. No pressure payload existed at pause completion. Only an automatic read invocation was in progress.

OBSERVED: Netty DefaultChannelPipeline.HeadContext.channelReadComplete calls downstream handlers, then readIfIsAutoRead checks the config and invokes channel.read(). If an off-loop pause changes the flag after that check, the automatic read enters MuxChannel.doBeginRead -> AbstractMuxHandler.childReadRequested with autoRead now false. That method creates readPending=true, which admits the first later payload. It remains armed even after a queued pause-reconciliation task and no-op settle have completed.

OBSERVED: AbstractChildChannel.setAutoRead's existing comment explicitly says delayed automatic read calls must not masquerade as manual demand. Commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/394fc9acdcafce0af3d5e5a30e41749b4a51ce23 removed a related call from DefaultChannelConfig's resume path for exactly this reason. The surviving automatic HeadContext path is the same class of demand-origin error.

INFER: This is a product defect against the present README contract, rather than merely an assertion that forgot an allowed in-delivery payload. Moving the pause into an event-loop task avoids the triggering ordering and can make zero-held-byte assertions stable, but it does not demonstrate a product repair. Do not call that result fixed without explicitly refuting the contract interpretation with stronger evidence.

## Finding: early no-op MuxChannel.read guard is incomplete

OBSERVED: Channel.read() is a Java default interface method in Netty4.2.10.Final (Channel.java244); overriding it is possible. Returning immediately while autoRead is true prevents a read already known to be automatic/no-op from being delayed by outbound handlers and later becoming manual demand.

INFER: The sequence HeadContext reads true -> other thread sets false -> MuxChannel.read sees false remains possible. An early guard therefore repairs the delayed-outbound-handler form but does not close the original head-check-to-method-entry window. Provenance is already erased at entry to the shared read() API. Do not adopt the limited repair as a complete fix solely because its narrower regression turns green.

OBSERVED: DefaultChannelPipeline's fireChannelReadComplete/fireChannelActive and channel() are final; HeadContext is a final inner class. A straightforward subclass cannot replace its automatic read decision. No clean existing hook for distinguishing automatic from explicit read was established in this review. Avoid brittle config-getter/call-stack tricks or reflection. A complete design must avoid automatic head reads for virtual channels or preserve automatic/manual provenance at that source, including activation and read-complete paths.

## Deterministic regression design for outbound deferral

Use a real primer over TCP. An application-installed ChannelOutboundHandlerAdapter records any read(ctx) reached while autoRead is true in a test-owned queue instead of blocking and forwarding. Await primer delivery and an event-loop barrier so primer read-complete has completed. Pause off-loop. On the event loop, forward all recorded contexts with ctx.read(), remove the gate and reset only primer observation counters. Then send the original pressure payloads and retain every original assertion and bound.

This reproducer does not require the automatic read handler to be invoked: if a correct implementation suppresses that no-op dispatch, the queue is empty and forwarding is a no-op. It distinguishes old delayed read from suppression without a gate timeout on the corrected implementation. It does NOT by itself prove the narrower HeadContext check-to-read-entry window fixed.

## Invariants and required coverage

| Resource/operation | Ownership and public invariant | Coverage needed |
|---|---|---|
| Automatic delivery | Mux owns delivery; off-loop pause allows only a payload already delivering at setter completion | Existing exact64 pressure assertion with primer/controlled delayed automatic read; no pressure bytes sent before pause completion |
| Explicit manual demand | Caller owns each read request; paused read admits one queued or future payload, repeated pending reads coalesce | PausedChildReadBeforeDataTest, PausedChildManualReadTcpTest, PausedChildConcurrentReadTcpTest |
| Re-entry | Callback may pause and request next data without recursion | PausedChildReadReentryTest and PausedChildResumeReadReentryTcpTest |
| Held payload storage | Paused child owns bounded held copies until delivery/close | Original many-small-frame case, YamuxPausedManySmallFramesTcpTest, backing storage/merged-storage cases |
| Child close | Close releases held bytes and leaves parent/siblings usable | Original closing pressure case, frame-bound callback-close variants |
| Shared parent | Yamux child pause never pauses parent/control traffic or siblings | Original sibling/new-negotiation case; control-traffic reply/reset scenarios |
| Resume and callback pause | Resume drains FIFO before next incoming frame; callback may pause/close | Frame-bound resume/re-pause/close tests with both normal merging and merge-declined variants |

## Neighbor gap search

OBSERVED: Same off-loop pause plus later event-loop barrier pattern appears in all3 YamuxPausedStreamKeepsConnectionReadingTest scenarios, YamuxPausedManySmallFramesTcpTest, both YamuxPausedQueueControlTrafficTcpTest scenarios, and YamuxResumeAtFrameBoundTcpTest/YamuxResumeAtFrameBoundCloseTcpTest/YamuxResumeAtFrameBoundRePauseTcpTest (each has normal and merge-declined mux variants). The frame-bound tests assert zero earlier delivery; a stale read allowance can also compromise their setup. These are coverage gaps for the product defect, not justification to weaken assertions.

OBSERVED: YamuxHandlerTest, YamuxPausedQueueBoundsTest, PausedYamuxBackingStorageTest and PausedYamuxMergedBackingStorageTest use EmbeddedChannel paths driven synchronously by callers. Their pause flag updates alone do not expose autonomous-loop timing. Manual-read re-entry and parent preservation already have substantial behavioral coverage; keep those unchanged as neighbors for any product repair.

## Result

Review is complete. Fixture-only pause ordering and early read() guard both have unresolved correctness issues described above. No product change approved by this pass. No tests were run or changed; parent owns red/green/reversal execution and final design.

## Historical contract check, 23:35 UTC

OBSERVED: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/b01e08e5 already documents the narrow in-delivery-payload allowance. At that source revision MuxChannel has no doBeginRead override and inherits AbstractChildChannel.doBeginRead's no-op, so a delayed automatic read cannot reserve a future payload. Manual-read behavior was added later in https://github.com/CodexCoder21Organization/jvm-libp2p/commit/0c3c86ad9ceb1c9d3834da68f239108e14b7f780; its component commit394fc9ac deliberately separates a delayed automatic-resume read from caller manual demand while keeping the prior allowance unchanged.

INFER: Expanding README to include any already-admitted pending read changes a previous behavior contract rather than merely describing a longstanding implementation. Such a design change could be made with owner direction, but it should not be presented as the existing contract proving the pressure assertion invalid.

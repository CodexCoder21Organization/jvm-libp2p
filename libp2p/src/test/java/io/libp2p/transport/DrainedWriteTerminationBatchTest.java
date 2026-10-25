package io.libp2p.transport;

import static org.junit.jupiter.api.Assertions.*;
import io.libp2p.etc.util.netty.mux.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.local.*;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.api.Test;

public class DrainedWriteTerminationBatchTest {
  @Test
  public void retiredBatchHookLeavesADrainedChildUsable() throws Exception {
    DefaultEventLoop loop = new DefaultEventLoop();
    Channel server = null, parent = null;
    AtomicReference<ByteBuf> initialPayload = new AtomicReference<>();
    AtomicReference<ByteBuf> laterPayload = new AtomicReference<>();
    CompletableFuture<Void> observed = new CompletableFuture<>();
    try {
      CompletingMux mux = new CompletingMux();
      LocalAddress address = new LocalAddress("drained-shutdown-batch");
      server = new ServerBootstrap().group(loop).channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
            protected void initChannel(LocalChannel ch) {}
          }).bind(address).sync().channel();
      parent = new Bootstrap().group(loop).channel(LocalChannel.class).handler(mux)
          .connect(address).sync().channel();
      MuxChannel<ByteBuf> child = mux.newStream(ch -> {
        ch.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
          public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            promise.setFailure(new IOException("The application close handler refused closure"));
          }
        });
        return Unit.INSTANCE;
      }).get(5, TimeUnit.SECONDS);
      IllegalStateException estimationFailure =
          new IllegalStateException("The message-size estimator refused its payload");
      AtomicBoolean failNextEstimate = new AtomicBoolean(true);
      MessageSizeEstimator.Handle defaultEstimator = child.config().getMessageSizeEstimator().newHandle();
      child.config().setMessageSizeEstimator(() -> msg -> {
        if (failNextEstimate.getAndSet(false)) throw estimationFailure;
        return defaultEstimator.size(msg);
      });
      ByteBuf rejectedPayload = Unpooled.buffer(1).writeByte(3);
      ChannelFuture rejected = loop.submit(() -> child.write(rejectedPayload)).get(5, TimeUnit.SECONDS);
      assertTrue(rejected.isDone(), "The estimator rejection must settle without buffering");
      assertSame(estimationFailure, rejected.cause(), "Preserve the original estimator failure");
      assertEquals(0, rejectedPayload.refCnt(), "Rejected writes must release their payloads");
      // Each cycle reuses the same child after the preceding drain.
      for (int i = 0; i < 64; i++) {
        ByteBuf bytes = Unpooled.buffer(1);
        if (i % 2 == 0) bytes.writeByte(i);
        ChannelFuture write = child.writeAndFlush(bytes);
        assertTrue(write.await(5, TimeUnit.SECONDS), "Drain/refill cycle must finish: " + i);
        assertTrue(write.isSuccess(), "Drain/refill cycle must succeed: " + i + "; " + write.cause());
        assertEquals(0, bytes.refCnt(), "Drain/refill cycle must release its payload: " + i);
      }
      loop.submit(() -> loop.addShutdownHook(child::flush)).get(5, TimeUnit.SECONDS);
      ByteBuf initial = Unpooled.buffer(1).writeByte(1);
      initialPayload.set(initial);
      ChannelFuture pending = child.write(initial);
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertFalse(pending.isDone(), "The initial entry must retain an unflushed batch hook");
      loop.submit(() -> loop.addShutdownHook(() -> {
        try {
          assertTrue(pending.isSuccess(), "The earlier batch hook must drain the initial entry");
          assertEquals(0, initial.refCnt(), "The initial payload must already be released");
          ByteBuf later = Unpooled.buffer(1).writeByte(2);
          laterPayload.set(later);
          ChannelFuture write = child.writeAndFlush(later);
          assertTrue(write.isSuccess(),
              "A retired shutdown-batch hook must leave a drained child's write side usable: "
                  + write.cause());
          assertEquals(0, later.refCnt(), "The later write must release its payload");
          observed.complete(null);
        } catch (Throwable failure) { observed.completeExceptionally(failure); }
      })).get(5, TimeUnit.SECONDS);
      parent.close().sync();
      server.close().sync();
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      assertTrue(loop.isTerminated(), "Observe only after owner termination");
      observed.get(5, TimeUnit.SECONDS);
    } finally {
      if (!loop.isShuttingDown()) {
        if (parent != null) parent.close().await(5, TimeUnit.SECONDS);
        if (server != null) server.close().await(5, TimeUnit.SECONDS);
      }
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      if (initialPayload.get() != null && initialPayload.get().refCnt() > 0) initialPayload.get().release();
      if (laterPayload.get() != null && laterPayload.get().refCnt() > 0) laterPayload.get().release();
    }
  }
  static final class Id extends MuxId {
    Id(ChannelId parent) {
      super(parent, 1);
    }

    public String asShortText() {
      return "unflushed-1";
    }

    public String asLongText() {
      return asShortText();
    }

    public boolean equals(Object other) {
      return other == this;
    }

    public int hashCode() {
      return System.identityHashCode(this);
    }
  }

  static final class CompletingMux extends AbstractMuxHandler<ByteBuf> {
    CompletingMux() { super(512); }
    protected Function1<MuxChannel<ByteBuf>, Unit> getInboundInitializer() { return ch -> Unit.INSTANCE; }
    public ChannelFuture onChildWrite(MuxChannel<ByteBuf> child, ByteBuf data) {
      ReferenceCountUtil.release(data);
      return getChannelHandlerContext().channel().newSucceededFuture();
    }
    public void releaseMessage(ByteBuf msg) { ReferenceCountUtil.release(msg); }
    public void channelRead(ChannelHandlerContext ctx, Object msg) { ReferenceCountUtil.release(msg); }
    protected void onLocalOpen(MuxChannel<ByteBuf> child) {}
    protected void onLocalClose(MuxChannel<ByteBuf> child) {}
    protected void onLocalDisconnect(MuxChannel<ByteBuf> child) {}
    protected void onChildClosed(MuxChannel<ByteBuf> child) {}
    protected MuxId generateNextId() { return new Id(getChannelHandlerContext().channel().id()); }
  }
}

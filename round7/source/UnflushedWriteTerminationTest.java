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
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class UnflushedWriteTerminationTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void acceptedUnflushedWriteKeepsItsTerminationOwner(boolean earlierDelivery) throws Exception {
    DefaultEventLoop loop = new DefaultEventLoop();
    Channel server = null, parent = null;
    MuxChannel<ByteBuf> child = null;
    ByteBuf payload = null;
    CompletingMux mux = new CompletingMux();
    try {
      LocalAddress address = new LocalAddress("unflushed-write-termination");
      server = new ServerBootstrap().group(loop).channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
            protected void initChannel(LocalChannel ch) {}
          }).bind(address).sync().channel();
      parent = new Bootstrap().group(loop).channel(LocalChannel.class).handler(mux)
          .connect(address).sync().channel();
      child = mux.newStream(ch -> {
        ch.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
          public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            promise.setFailure(new IOException("The application close handler refused closure"));
          }
        });
        return Unit.INSTANCE;
      }).get(5, TimeUnit.SECONDS);
      if (earlierDelivery) {
        ByteBuf earlier = Unpooled.buffer(1).writeByte(1);
        ChannelFuture delivered = child.writeAndFlush(earlier);
        mux.pending.get(5, TimeUnit.SECONDS).setSuccess();
        assertTrue(delivered.await(5, TimeUnit.SECONDS), "The earlier delivery must complete");
        assertTrue(delivered.isSuccess(), "The earlier delivery must succeed");
        loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals(0, earlier.refCnt(), "The earlier delivery must release its payload");
      }
      payload = Unpooled.buffer(1).writeByte(2);
      ChannelFuture accepted = child.write(payload);
      CompletableFuture<Boolean> notifiedOnOwner = new CompletableFuture<>();
      accepted.addListener(done -> notifiedOnOwner.complete(loop.inEventLoop()));
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertFalse(accepted.isDone(), "The unflushed write must be accepted and pending");
      assertEquals(1, payload.refCnt(), "The outbound buffer must own the accepted payload");
      parent.close().sync();
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(child.isOpen(), "The application handler must refuse child closure");
      assertFalse(accepted.isDone(), "Parent close alone must leave this write pending");
      server.close().sync();
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      assertTrue(loop.isTerminated(), "The owning loop must terminate before observation");
      System.out.println("earlierDelivery=" + earlierDelivery + " ownerTerminated="
          + loop.isTerminated() + " acceptedDone=" + accepted.isDone()
          + " payloadRefCnt=" + payload.refCnt());
      assertTrue(accepted.isDone(),
          "An accepted unflushed write after earlier delivery must settle before owner termination");
      assertInstanceOf(RejectedExecutionException.class, accepted.cause());
      assertEquals("The child event loop terminated before pending writes could complete: "
          + child.getId(), accepted.cause().getMessage());
      assertTrue(notifiedOnOwner.get(5, TimeUnit.SECONDS),
          "Termination must notify accepted writes on their owning loop");
      assertEquals(0, payload.refCnt(), "Termination must release the accepted payload exactly once");
    } finally {
      if (!loop.isShuttingDown()) {
        if (child != null) child.close().await(5, TimeUnit.SECONDS);
        if (parent != null) parent.close().await(5, TimeUnit.SECONDS);
        if (server != null) server.close().await(5, TimeUnit.SECONDS);
      }
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      // The baseline has no termination owner; reclaim its payload after recording the failure.
      if (payload != null && payload.refCnt() > 0) payload.release();
    }
  }

  static final class Id extends MuxId {
    Id(ChannelId parent) { super(parent, 1); }
    public String asShortText() { return "unflushed-1"; }
    public String asLongText() { return asShortText(); }
    public boolean equals(Object other) { return other == this; }
    public int hashCode() { return System.identityHashCode(this); }
  }

  static final class CompletingMux extends AbstractMuxHandler<ByteBuf> {
    final CompletableFuture<ChannelPromise> pending = new CompletableFuture<>();
    CompletingMux() { super(512); }
    protected Function1<MuxChannel<ByteBuf>, Unit> getInboundInitializer() {
      return ch -> Unit.INSTANCE;
    }
    public ChannelFuture onChildWrite(MuxChannel<ByteBuf> child, ByteBuf data) {
      ReferenceCountUtil.release(data);
      ChannelPromise result = new DefaultChannelPromise(getChannelHandlerContext().channel());
      pending.complete(result);
      return result;
    }
    public void releaseMessage(ByteBuf msg) { ReferenceCountUtil.release(msg); }
    public void channelRead(ChannelHandlerContext ctx, Object msg) { ReferenceCountUtil.release(msg); }
    protected void onLocalOpen(MuxChannel<ByteBuf> child) {}
    protected void onLocalClose(MuxChannel<ByteBuf> child) {}
    protected void onLocalDisconnect(MuxChannel<ByteBuf> child) {}
    protected void onChildClosed(MuxChannel<ByteBuf> child) {}
    protected MuxId generateNextId() {
      return new Id(getChannelHandlerContext().channel().id());
    }
  }
}

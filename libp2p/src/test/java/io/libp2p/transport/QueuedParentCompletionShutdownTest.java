package io.libp2p.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.libp2p.etc.util.netty.mux.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.local.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.io.IOException;
import java.util.concurrent.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class QueuedParentCompletionShutdownTest {
  @ParameterizedTest
  @ValueSource(strings = {"success", "failure", "cancel"})
  public void queuedParentCompletionDoesNotStartAnotherWriteDuringShutdown(String outcome)
      throws Exception {
    DefaultEventLoop loop = new DefaultEventLoop();
    DefaultEventExecutor external = new DefaultEventExecutor();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Channel server = null, parent = null;
    ByteBuf firstPayload = null, queuedPayload = null;
    try {
      CompletingMux mux = new CompletingMux(external);
      LocalAddress address = new LocalAddress("queued-completion-shutdown");
      server =
          new ServerBootstrap()
              .group(loop)
              .channel(LocalServerChannel.class)
              .childHandler(
                  new ChannelInitializer<LocalChannel>() {
                    protected void initChannel(LocalChannel ch) {}
                  })
              .bind(address)
              .sync()
              .channel();
      parent =
          new Bootstrap()
              .group(loop)
              .channel(LocalChannel.class)
              .handler(mux)
              .connect(address)
              .sync()
              .channel();
      MuxChannel<ByteBuf> child =
          mux.newStream(
                  ch -> {
                    ch.pipeline()
                        .addLast(
                            new ChannelOutboundHandlerAdapter() {
                              public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                                promise.setFailure(
                                    new IOException(
                                        "The application close handler refused closure"));
                              }
                            });
                    return Unit.INSTANCE;
                  })
              .get(5, TimeUnit.SECONDS);
      firstPayload = Unpooled.buffer(1).writeByte(1);
      ChannelFuture first = child.writeAndFlush(firstPayload);
      ChannelPromise source = mux.pending.get(5, TimeUnit.SECONDS);
      queuedPayload = Unpooled.buffer(1).writeByte(2);
      ChannelFuture queued = child.writeAndFlush(queuedPayload);
      CompletableFuture<Boolean> notifiedOnOwner = new CompletableFuture<>();
      queued.addListener(done -> notifiedOnOwner.complete(loop.inEventLoop()));
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertFalse(first.isDone(), "The first write must await the foreign parent promise");
      assertFalse(queued.isDone(), "The next accepted write must await the first parent result");
      loop.execute(
          () -> {
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS), "Release the owner task");
            } catch (InterruptedException failure) {
              throw new RuntimeException(failure);
            }
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS), "The owner must enter its controlled task");
      IOException expectedFailure = new IOException("The completed parent write failed");
      external
          .submit(
              () -> {
                if (outcome.equals("success")) source.setSuccess();
                else if (outcome.equals("failure")) source.setFailure(expectedFailure);
                else assertTrue(source.cancel(false), "The pending parent promise must cancel");
              })
          .get(5, TimeUnit.SECONDS);
      external.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertFalse(
          first.isDone(), "The completed parent result must still be queued on the held owner");
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS);
      assertTrue(loop.isShuttingDown(), "Shutdown must begin before the queued completion can run");
      release.countDown();
      assertTrue(loop.terminationFuture().await(5, TimeUnit.SECONDS), "The owner must terminate");
      assertTrue(loop.isTerminated(), "Observe only after owner termination");
      if (outcome.equals("success"))
        assertTrue(first.isSuccess(), "Preserve the original parent success");
      else
        assertSame(
            outcome.equals("failure") ? expectedFailure : source.cause(),
            first.cause(),
            "Preserve the identical original parent failure or cancellation");
      assertTrue(queued.isDone(), "The queued write must settle before owner termination");
      assertFalse(
          queued.isSuccess(),
          "A parent completion queued before shutdown must not send the next accepted write: "
              + outcome);
      assertInstanceOf(RejectedExecutionException.class, queued.cause());
      assertEquals(
          "The child event loop terminated before pending writes could complete: " + child.getId(),
          queued.cause().getMessage());
      assertTrue(notifiedOnOwner.get(5, TimeUnit.SECONDS), "Notify the queued write on its owner");
      assertEquals(
          0, firstPayload.refCnt(), "Release the first payload before observing termination");
      assertEquals(0, queuedPayload.refCnt(), "Release the queued payload exactly once");
    } finally {
      release.countDown();
      if (!loop.isShuttingDown()) {
        if (parent != null) parent.close().await(5, TimeUnit.SECONDS);
        if (server != null) server.close().await(5, TimeUnit.SECONDS);
      }
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      external.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      if (firstPayload != null && firstPayload.refCnt() > 0) firstPayload.release();
      if (queuedPayload != null && queuedPayload.refCnt() > 0) queuedPayload.release();
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
    final CompletableFuture<ChannelPromise> pending = new CompletableFuture<>();
    final DefaultEventExecutor external;

    CompletingMux(DefaultEventExecutor external) {
      super(512);
      this.external = external;
    }

    protected Function1<MuxChannel<ByteBuf>, Unit> getInboundInitializer() {
      return ch -> Unit.INSTANCE;
    }

    public ChannelFuture onChildWrite(MuxChannel<ByteBuf> child, ByteBuf data) {
      ReferenceCountUtil.release(data);
      ChannelPromise result =
          new DefaultChannelPromise(getChannelHandlerContext().channel(), external);
      if (!pending.complete(result)) result.setSuccess();
      return result;
    }

    public void releaseMessage(ByteBuf msg) {
      ReferenceCountUtil.release(msg);
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      ReferenceCountUtil.release(msg);
    }

    protected void onLocalOpen(MuxChannel<ByteBuf> child) {}

    protected void onLocalClose(MuxChannel<ByteBuf> child) {}

    protected void onLocalDisconnect(MuxChannel<ByteBuf> child) {}

    protected void onChildClosed(MuxChannel<ByteBuf> child) {}

    protected MuxId generateNextId() {
      return new Id(getChannelHandlerContext().channel().id());
    }
  }
}

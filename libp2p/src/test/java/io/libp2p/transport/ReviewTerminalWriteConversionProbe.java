package io.libp2p.transport;

import io.libp2p.etc.util.netty.mux.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.local.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.util.concurrent.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class ReviewTerminalWriteConversionProbe {
  static final class Id extends MuxId {
    Id(ChannelId parent) {
      super(parent, 1);
    }

    public String asShortText() {
      return "probe-1";
    }

    public String asLongText() {
      return "probe-1";
    }

    public boolean equals(Object other) {
      return other == this;
    }

    public int hashCode() {
      return 1;
    }
  }

  static final class Mux extends AbstractMuxHandler<ByteBuf> {
    final DefaultEventExecutor external;
    final CompletableFuture<ChannelPromise> write = new CompletableFuture<>();

    Mux(DefaultEventExecutor external) {
      super(512);
      this.external = external;
    }

    protected Function1<MuxChannel<ByteBuf>, Unit> getInboundInitializer() {
      return ch -> Unit.INSTANCE;
    }

    public ChannelFuture onChildWrite(MuxChannel<ByteBuf> child, ByteBuf data) {
      ChannelPromise promise =
          new DefaultChannelPromise(getChannelHandlerContext().channel(), external);
      ReferenceCountUtil.release(data);
      if (!write.complete(promise)) promise.setSuccess();
      return promise;
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

  public static void main(String[] args) throws Exception {
    String mode = args[0];
    String parentOutcome = args.length > 1 ? args[1] : "pending";
    boolean completedParent = !parentOutcome.equals("pending");
    boolean terminating = mode.equals("shutdown") || mode.equals("blocked-close");
    java.io.IOException parentFailure =
        new java.io.IOException("The separate-executor parent write failed");
    DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
    DefaultEventExecutor external = new DefaultEventExecutor();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Channel server = null, parent = null;
    MuxChannel<ByteBuf> child = null;
    io.libp2p.core.Host host = new io.libp2p.core.dsl.HostBuilder().build();
    try {
      LocalAddress address = new LocalAddress("off-loop-parent-write-probe");
      server =
          new ServerBootstrap()
              .group(group)
              .channel(LocalServerChannel.class)
              .childHandler(
                  new ChannelInitializer<LocalChannel>() {
                    protected void initChannel(LocalChannel ch) {}
                  })
              .bind(address)
              .sync()
              .channel();
      Mux mux = new Mux(external);
      parent =
          new Bootstrap()
              .group(group)
              .channel(LocalChannel.class)
              .handler(mux)
              .connect(address)
              .sync()
              .channel();
      child =
          mux.newStream(
                  ch -> {
                    if (mode.equals("blocked-close"))
                      ch.pipeline()
                          .addLast(
                              new ChannelOutboundHandlerAdapter() {
                                public void close(
                                    ChannelHandlerContext ctx, ChannelPromise closePromise) {
                                  closePromise.setFailure(
                                      new java.io.IOException(
                                          "The application close handler refused closure"));
                                }
                              });
                    return Unit.INSTANCE;
                  })
              .get(5, TimeUnit.SECONDS);
      ByteBuf payload = Unpooled.buffer(1).writeByte(7);
      ChannelFuture result = child.writeAndFlush(payload);
      ChannelPromise promise = mux.write.get(5, TimeUnit.SECONDS);
      io.libp2p.transport.implementation.ConnectionOverNetty connection =
          new io.libp2p.transport.implementation.ConnectionOverNetty(
              parent,
              (io.libp2p.transport.implementation.NettyTransport)
                  host.getNetwork().getTransports().get(0),
              true);
      io.libp2p.transport.implementation.StreamOverNetty stream =
          new io.libp2p.transport.implementation.StreamOverNetty(child, connection, true);
      ByteBuf publicPayload = Unpooled.buffer(1).writeByte(11);
      CompletableFuture<Unit> publicResult = stream.writeAndFlushWithFuture(publicPayload);
      EventLoop owner = child.eventLoop();
      CompletableFuture<Boolean> completionOnOwner = new CompletableFuture<>();
      publicResult.whenComplete((value, cause) -> completionOnOwner.complete(owner.inEventLoop()));
      ByteBuf unflushedPayload = null;
      ChannelFuture unflushedWrite = null;
      if (mode.equals("blocked-close")) {
        unflushedPayload = Unpooled.buffer(1).writeByte(9);
        unflushedWrite = child.write(unflushedPayload);
      }
      EventLoop loop = child.eventLoop();
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      loop.execute(
          () -> {
            entered.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Release missing");
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          });
      if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Loop did not enter hold");
      int accepted = 0;
      java.util.concurrent.atomic.AtomicInteger pending =
          new java.util.concurrent.atomic.AtomicInteger(1);
      CountDownLatch drained = new CountDownLatch(1);
      while (accepted < 65536) {
        try {
          pending.incrementAndGet();
          loop.execute(
              () -> {
                if (pending.decrementAndGet() == 0) drained.countDown();
              });
          accepted++;
        } catch (RejectedExecutionException expected) {
          pending.decrementAndGet();
          break;
        }
      }
      if (accepted == 65536 || loop.isShuttingDown())
        throw new AssertionError("Expected full live loop");
      if ((!mode.equals("close") && !terminating) || completedParent)
        external
            .submit(
                () -> {
                  if (loop.inEventLoop()) throw new AssertionError("Must be external");
                  if (mode.equals("failure") || parentOutcome.equals("failure"))
                    promise.setFailure(parentFailure);
                  else if (mode.equals("cancel") || parentOutcome.equals("cancel"))
                    promise.cancel(false);
                  else promise.setSuccess();
                })
            .get(5, TimeUnit.SECONDS);
      external.submit(() -> {}).get(5, TimeUnit.SECONDS);
      if (pending.decrementAndGet() == 0) drained.countDown();
      if (terminating) group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
      release.countDown();
      if (!drained.await(5, TimeUnit.SECONDS)) throw new AssertionError("Queue did not drain");
      if (terminating) {
        if (!group.terminationFuture().await(5, TimeUnit.SECONDS))
          throw new AssertionError("The owner loop must finish shutdown");
      } else {
        loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      }
      if (mode.equals("close")) {
        child.close().sync();
        external.submit(() -> promise.setSuccess()).get(5, TimeUnit.SECONDS);
        external.submit(() -> {}).get(5, TimeUnit.SECONDS);
        loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      }
      result.await(5, TimeUnit.SECONDS);
      if (!result.isDone())
        throw new AssertionError(
            "A separate-executor parent result must settle its child write after queue recovery");
      if (completedParent) {
        if (parentOutcome.equals("failure")) {
          if (result.cause() != parentFailure)
            throw new AssertionError(
                "The exact original parent failure must survive refused close", result.cause());
        } else if (parentOutcome.equals("cancel")) {
          if (result.cause() != promise.cause()
              || !(result.cause() instanceof CancellationException))
            throw new AssertionError(
                "The original parent cancellation must survive refused close", result.cause());
        } else if (!result.isSuccess()) {
          throw new AssertionError(
              "The original parent success must survive refused close", result.cause());
        }
      } else if (mode.equals("success")) {
        if (!result.isSuccess())
          throw new AssertionError(
              "The original successful parent result must reach the child", result.cause());
      } else if (mode.equals("failure")) {
        if (result.cause() != parentFailure)
          throw new AssertionError(
              "The exact original parent failure must reach the child", result.cause());
      } else if (mode.equals("cancel")) {
        if (result.cause() != promise.cause() || !(result.cause() instanceof CancellationException))
          throw new AssertionError(
              "Parent cancellation must retain its exact cancellation cause", result.cause());
      } else if (mode.equals("blocked-close")) {
        String expected =
            "The child event loop terminated before pending writes could complete: "
                + child.getId();
        if (!(result.cause() instanceof RejectedExecutionException)
            || !expected.equals(result.cause().getMessage()))
          throw new AssertionError(
              "Terminated-loop writes must report the exact rejection", result.cause());
        if (!unflushedWrite.await(5, TimeUnit.SECONDS) || !unflushedWrite.isDone())
          throw new AssertionError("Loop termination must settle accepted unflushed child writes");
        if (unflushedWrite.cause() != result.cause())
          throw new AssertionError(
              "All pending writes must retain the same termination rejection",
              unflushedWrite.cause());
        if (unflushedPayload.refCnt() != 0)
          throw new AssertionError(
              "Loop termination must release an accepted unflushed payload exactly once");
      } else {
        if (!(result.cause() instanceof java.nio.channels.ClosedChannelException)
            || result.cause().getMessage() != null)
          throw new AssertionError(
              "Closing must retain the original closed-channel failure with no message",
              result.cause());
        if (child.isOpen())
          throw new AssertionError("Close or shutdown must leave the child closed");
      }
      System.out.println(
          "rawDone="
              + result.isDone()
              + " rawFailure="
              + result.cause()
              + " publicPayloadRefCnt="
              + publicPayload.refCnt());
      if (!publicResult.isDone())
        throw new AssertionError("The public stream write must settle after loop termination");
      if (!completionOnOwner.join())
        throw new AssertionError(
            "Termination must settle the public stream write on its owning loop");
      if (!publicResult.isCompletedExceptionally())
        throw new AssertionError("The public stream write must report termination failure");
      Throwable expectedFailure = result.cause();
      if (completedParent) {
        if (!unflushedWrite.isDone())
          throw new AssertionError("Loop termination must settle accepted unflushed child writes");
        expectedFailure = unflushedWrite.cause();
        String expected =
            "The child event loop terminated before pending writes could complete: "
                + child.getId();
        if (!(expectedFailure instanceof RejectedExecutionException)
            || !expected.equals(expectedFailure.getMessage()))
          throw new AssertionError(
              "Queued writes must report the exact termination rejection", expectedFailure);
        if (unflushedPayload.refCnt() != 0)
          throw new AssertionError(
              "Loop termination must release an accepted unflushed payload exactly once");
      }
      try {
        publicResult.join();
        throw new AssertionError("Expected termination failure");
      } catch (CompletionException failure) {
        if (failure.getCause() != expectedFailure)
          throw new AssertionError(
              "The public stream write must retain the identical termination cause", failure);
      }
      if (publicPayload.refCnt() != 0)
        throw new AssertionError("The public stream payload must be released exactly once");
      if (payload.refCnt() != 0)
        throw new AssertionError("The completed child write must release its payload exactly once");
      if (!terminating) {
        ByteBuf laterPayload = Unpooled.buffer(1).writeByte(8);
        ChannelFuture second = child.writeAndFlush(laterPayload);
        if (!second.await(5, TimeUnit.SECONDS))
          throw new AssertionError(
              "A later child write must finish after recovered parent completion");
        if (mode.equals("close")) {
          if (!(second.cause() instanceof java.nio.channels.ClosedChannelException)
              || second.cause().getMessage() != null)
            throw new AssertionError(
                "A write after close must report the original closed-channel failure with no message",
                second.cause());
        } else if (!second.isSuccess())
          throw new AssertionError(
              "A later child write must progress after recovered parent completion",
              second.cause());
        if (laterPayload.refCnt() != 0)
          throw new AssertionError("The later child write must release its payload exactly once");
        System.out.println(
            "queueAccepted="
                + accepted
                + " parentDone="
                + promise.isDone()
                + " childDoneAfterRecovery="
                + result.isDone()
                + " payloadRefCnt="
                + payload.refCnt()
                + " laterWriteSuccess="
                + second.isSuccess());
      }
    } finally {
      release.countDown();
      if (child != null) child.close().await(5, TimeUnit.SECONDS);
      if (parent != null) parent.close().await(5, TimeUnit.SECONDS);
      if (server != null) server.close().await(5, TimeUnit.SECONDS);
      group.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      external.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      host.stop().get(10, TimeUnit.SECONDS);
    }
  }
}

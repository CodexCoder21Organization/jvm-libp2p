package io.libp2p.transport;

import io.libp2p.etc.util.netty.mux.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.local.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import java.util.concurrent.*;

public final class OffLoopParentWriteNotificationProbe {
  static final class Id extends MuxId {
    Id(ChannelId parent) { super(parent, 1); }
    public String asShortText() { return "probe-1"; }
    public String asLongText() { return "probe-1"; }
    public boolean equals(Object other) { return other == this; }
    public int hashCode() { return 1; }
  }
  static final class Mux extends AbstractMuxHandler<ByteBuf> {
    final DefaultEventExecutor external;
    final CompletableFuture<ChannelPromise> write = new CompletableFuture<>();
    Mux(DefaultEventExecutor external) { super(512); this.external = external; }
    protected Function1<MuxChannel<ByteBuf>,Unit> getInboundInitializer() { return ch -> Unit.INSTANCE; }
    public ChannelFuture onChildWrite(MuxChannel<ByteBuf> child, ByteBuf data) {
      ChannelPromise promise = new DefaultChannelPromise(getChannelHandlerContext().channel(), external);
      ReferenceCountUtil.release(data);
      if (!write.complete(promise)) promise.setSuccess();
      return promise;
    }
    public void releaseMessage(ByteBuf msg) { ReferenceCountUtil.release(msg); }
    public void channelRead(ChannelHandlerContext ctx,Object msg) { ReferenceCountUtil.release(msg); }
    protected void onLocalOpen(MuxChannel<ByteBuf> child) { }
    protected void onLocalClose(MuxChannel<ByteBuf> child) { }
    protected void onLocalDisconnect(MuxChannel<ByteBuf> child) { }
    protected void onChildClosed(MuxChannel<ByteBuf> child) { }
    protected MuxId generateNextId() { return new Id(getChannelHandlerContext().channel().id()); }
  }
  public static void main(String[] args) throws Exception {
    String mode=args[0];
    java.io.IOException parentFailure=new java.io.IOException("The separate-executor parent write failed");
    DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
    DefaultEventExecutor external = new DefaultEventExecutor();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Channel server=null, parent=null; MuxChannel<ByteBuf> child=null;
    try {
      LocalAddress address = new LocalAddress("off-loop-parent-write-probe");
      server = new ServerBootstrap().group(group).channel(LocalServerChannel.class)
        .childHandler(new ChannelInitializer<LocalChannel>() {
          protected void initChannel(LocalChannel ch) { }
        }).bind(address).sync().channel();
      Mux mux = new Mux(external);
      parent = new Bootstrap().group(group).channel(LocalChannel.class).handler(mux).connect(address).sync().channel();
      child = mux.newStream(ch -> Unit.INSTANCE).get(5,TimeUnit.SECONDS);
      ByteBuf payload=Unpooled.buffer(1).writeByte(7);
      ChannelFuture result=child.writeAndFlush(payload);
      ChannelPromise promise=mux.write.get(5,TimeUnit.SECONDS);
      EventLoop loop=child.eventLoop();
      loop.submit(() -> {}).get(5,TimeUnit.SECONDS);
      loop.execute(() -> { entered.countDown(); try {
        if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("Release missing");
      } catch (InterruptedException e) { throw new RuntimeException(e); } });
      if (!entered.await(5,TimeUnit.SECONDS)) throw new AssertionError("Loop did not enter hold");
      int accepted=0;
      java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(1);
      CountDownLatch drained = new CountDownLatch(1);
      while (accepted<65536) {
        try { pending.incrementAndGet(); loop.execute(() -> { if (pending.decrementAndGet()==0) drained.countDown(); }); accepted++; }
        catch (RejectedExecutionException expected) { pending.decrementAndGet(); break; }
      }
      if (accepted==65536 || loop.isShuttingDown()) throw new AssertionError("Expected full live loop");
      external.submit(() -> { if (loop.inEventLoop()) throw new AssertionError("Must be external"); if (mode.equals("failure")) promise.setFailure(parentFailure);
        else if (mode.equals("cancel")) promise.cancel(false);
        else promise.setSuccess(); }).get(5,TimeUnit.SECONDS);
      external.submit(() -> {}).get(5,TimeUnit.SECONDS);
      if (pending.decrementAndGet()==0) drained.countDown();
      release.countDown();
      if (!drained.await(5,TimeUnit.SECONDS)) throw new AssertionError("Queue did not drain");
      loop.submit(() -> {}).get(5,TimeUnit.SECONDS);
      result.await(5,TimeUnit.SECONDS);
      if (!result.isDone()) throw new AssertionError("A separate-executor parent result must settle its child write after queue recovery");
      if (mode.equals("success")) {
        if (!result.isSuccess()) throw new AssertionError("The original successful parent result must reach the child",result.cause());
      } else if (mode.equals("failure")) {
        if (result.cause()!=parentFailure) throw new AssertionError("The exact original parent failure must reach the child",result.cause());
      } else if (!(result.cause() instanceof CancellationException)) {
        throw new AssertionError("Parent cancellation must fail the child with its cancellation cause",result.cause());
      }
      if (payload.refCnt()!=0) throw new AssertionError("The completed child write must release its payload exactly once");
      ByteBuf laterPayload=Unpooled.buffer(1).writeByte(8);
      ChannelFuture second=child.writeAndFlush(laterPayload);
      if (!second.await(5,TimeUnit.SECONDS) || !second.isSuccess()) throw new AssertionError("A later child write must progress after recovered parent completion",second.cause());
      if (laterPayload.refCnt()!=0) throw new AssertionError("The later child write must release its payload exactly once");
      System.out.println("queueAccepted="+accepted+" parentDone="+promise.isDone()+" childDoneAfterRecovery="+result.isDone()+" payloadRefCnt="+payload.refCnt()+" laterWriteSuccess="+second.isSuccess());
    } finally {
      release.countDown();
      if (child!=null) child.close().await(5,TimeUnit.SECONDS);
      if (parent!=null) parent.close().await(5,TimeUnit.SECONDS);
      if (server!=null) server.close().await(5,TimeUnit.SECONDS);
      group.shutdownGracefully(0,5,TimeUnit.SECONDS).await(5,TimeUnit.SECONDS);
      external.shutdownGracefully(0,5,TimeUnit.SECONDS).await(5,TimeUnit.SECONDS);
    }
  }
}

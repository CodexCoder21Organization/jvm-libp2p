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

public final class OffLoopParentWriteProbe {
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
      write.complete(promise);
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
      external.submit(() -> { if (loop.inEventLoop()) throw new AssertionError("Must be external"); promise.setSuccess(); }).get(5,TimeUnit.SECONDS);
      external.submit(() -> {}).get(5,TimeUnit.SECONDS);
      if (pending.decrementAndGet()==0) drained.countDown();
      release.countDown();
      if (!drained.await(5,TimeUnit.SECONDS)) throw new AssertionError("Queue did not drain");
      loop.submit(() -> {}).get(5,TimeUnit.SECONDS);
      System.out.println("queueAccepted="+accepted+" parentSuccess="+promise.isSuccess()+" childDoneAfterRecovery="+result.isDone()+" payloadRefCnt="+payload.refCnt());
      // A further real write and flush still cannot progress past the lost continuation.
      ChannelFuture second=child.writeAndFlush(Unpooled.buffer(1).writeByte(8));
      loop.submit(() -> {}).get(5,TimeUnit.SECONDS);
      System.out.println("childDoneAfterAnotherFlush="+result.isDone()+" laterWriteDone="+second.isDone());
      if (result.isDone() || second.isDone()) throw new AssertionError("Expected to demonstrate both writes remaining pending");
      child.close().sync();
      System.out.println("childDoneOnlyAfterExplicitClose="+result.isDone()+" childSuccess="+result.isSuccess()+" payloadRefCntAfterClose="+payload.refCnt());
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

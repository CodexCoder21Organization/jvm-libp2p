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
import java.nio.file.*;
import java.util.concurrent.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.api.Test;

public class ForeignWriteFailureOwnershipTest {
  @Test
  public void foreignFailureRetiresItsOwnerAfterAFullQueueRecovers() throws Exception {
    Path output = Files.createTempFile("foreign-write-owner", ".log");
    Process process = null;
    try {
      process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xmx128m",
          "-Dio.netty.eventLoop.maxPendingTasks=16", "-cp", System.getProperty("java.class.path"),
          getClass().getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The foreign-write owner probe must finish");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      if (process != null) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      Files.deleteIfExists(output);
    }
  }

  public static void main(String[] args) throws Exception {
    TrackingLoop loop = new TrackingLoop();
    DefaultEventExecutor foreign = new DefaultEventExecutor();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CompletableFuture<ChannelPromise> heldPromise = new CompletableFuture<>();
    CompletableFuture<ByteBuf> heldPayload = new CompletableFuture<>();
    Channel server = null, parent = null;
    ByteBuf payload = null;
    try {
      CompletingMux mux = new CompletingMux();
      LocalAddress address = new LocalAddress("foreign-write-owner");
      server = new ServerBootstrap().group(loop).channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
            protected void initChannel(LocalChannel ch) {}
          }).bind(address).sync().channel();
      parent = new Bootstrap().group(loop).channel(LocalChannel.class).handler(mux)
          .connect(address).sync().channel();
      MuxChannel<ByteBuf> child = mux.newStream(ch -> {
        ch.pipeline().addFirst("foreign-write-handler", new ChannelOutboundHandlerAdapter() {
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            heldPayload.complete((ByteBuf) msg);
            heldPromise.complete(promise);
          }
        });
        return Unit.INSTANCE;
      }).get(5, TimeUnit.SECONDS);
      payload = Unpooled.buffer(1).writeByte(1);
      ChannelFuture write = child.write(payload);
      ChannelPromise held = heldPromise.get(5, TimeUnit.SECONDS);
      loop.execute(() -> {
        entered.countDown();
        try { assertTrue(release.await(10, TimeUnit.SECONDS), "Release the held owner"); }
        catch (InterruptedException failure) { throw new RuntimeException(failure); }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS), "The owner must enter its controlled task");
      int admitted = 0;
      while (admitted < 65536) {
        try { loop.execute(() -> {}); admitted++; }
        catch (RejectedExecutionException full) { break; }
      }
      assertTrue(admitted > 0 && admitted < 65536, "The live owner queue must fill");
      assertThrows(RejectedExecutionException.class, () -> loop.execute(() -> {}));
      IOException expected = new IOException("The foreign write handler refused its payload");
      assertDoesNotThrow(() -> foreign.submit(() -> {
        heldPayload.getNow(null).release();
        held.setFailure(expected);
      }).get(5, TimeUnit.SECONDS), "Owner retirement must not escape from a foreign write failure");
      assertTrue(write.isDone(), "A foreign failure must settle the caller's write despite a full owner queue");
      assertSame(expected, write.cause(), "Preserve the identical foreign handler failure");
      assertEquals(0, payload.refCnt(), "The refusing handler must release its payload exactly once");
      release.countDown();
      assertTrue(loop.removalOnOwner.get(5, TimeUnit.SECONDS), "Retire the accepted-write hook on its owner");
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      if (heldPayload.isDone() && heldPayload.getNow(null).refCnt() > 0) heldPayload.getNow(null).release();
      if (parent != null) parent.close().await(5, TimeUnit.SECONDS);
      if (server != null) server.close().await(5, TimeUnit.SECONDS);
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      foreign.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
    }
  }

  static final class TrackingLoop extends DefaultEventLoop {
    final CompletableFuture<Boolean> removalOnOwner = new CompletableFuture<>();
    @Override
    public void removeShutdownHook(Runnable hook) {
      super.removeShutdownHook(hook);
      removalOnOwner.complete(inEventLoop());
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

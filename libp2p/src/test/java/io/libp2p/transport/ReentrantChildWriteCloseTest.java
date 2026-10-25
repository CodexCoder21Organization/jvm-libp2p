package io.libp2p.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.libp2p.etc.util.netty.mux.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.local.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.*;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.api.Test;

public class ReentrantChildWriteCloseTest {
  @Test
  public void synchronousChildCloseDoesNotInstallCompletionOwnership() throws Exception {
    TrackingLoop loop = new TrackingLoop();
    Channel server = null, parent = null;
    List<MuxChannel<ByteBuf>> children = new ArrayList<>();
    ClosingMux mux = new ClosingMux();
    try {
      LocalAddress address = new LocalAddress("reentrant-child-write-close");
      server = new ServerBootstrap().group(loop).channel(LocalServerChannel.class)
          .childHandler(new ChannelInitializer<LocalChannel>() {
            protected void initChannel(LocalChannel ch) {}
          }).bind(address).sync().channel();
      parent = new Bootstrap().group(loop).channel(LocalChannel.class).handler(mux)
          .connect(address).sync().channel();
      for (int i = 0; i < 3; i++) {
        MuxChannel<ByteBuf> child = mux.newStream(ch -> Unit.INSTANCE).get(5, TimeUnit.SECONDS);
        children.add(child);
        ByteBuf payload = Unpooled.buffer(1).writeByte(i);
        ChannelFuture write = child.writeAndFlush(payload);
        assertTrue(write.await(5, TimeUnit.SECONDS), "Synchronous child close must settle its write");
        child.close().sync();
        assertFalse(child.isOpen(), "The callback must close its child");
        assertEquals(0, payload.refCnt(), "Closed child payloads must be released exactly once");
      }
      parent.close().sync();
      server.close().sync();
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertEquals(0, loop.submit(() -> loop.hooks.size()).get(5, TimeUnit.SECONDS),
          "Synchronous child close must leave zero completion shutdown hooks");
      assertEquals(3, mux.writes.size(), "Every child must enter the callback once");
      for (PendingWrite pending : mux.writes) {
        assertFalse(pending.isDone(), "The parent promise must remain pending during the assertion");
        assertEquals(0, pending.listenerRegistrations,
            "Pending parent promises must not retain completion callbacks for closed children");
      }
    } finally {
      for (MuxChannel<ByteBuf> child : children) child.close().await(5, TimeUnit.SECONDS);
      if (parent != null) parent.close().await(5, TimeUnit.SECONDS);
      if (server != null) server.close().await(5, TimeUnit.SECONDS);
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
    }
  }

  static final class TrackingLoop extends DefaultEventLoop {
    final Set<Runnable> hooks = new HashSet<>();

    @Override
    public void addShutdownHook(Runnable task) {
      assertTrue(inEventLoop(), "Hook installation must run on the child loop");
      super.addShutdownHook(task);
      hooks.add(task);
    }

    @Override
    public void removeShutdownHook(Runnable task) {
      assertTrue(inEventLoop(), "Hook removal must run on the child loop");
      super.removeShutdownHook(task);
      hooks.remove(task);
    }
  }

  static final class PendingWrite extends DefaultChannelPromise {
    int listenerRegistrations;

    PendingWrite(Channel parent) { super(parent); }

    @Override
    public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
      listenerRegistrations++;
      return super.addListener(listener);
    }
  }

  static final class Id extends MuxId {
    Id(ChannelId parent, long id) { super(parent, id); }
    public String asShortText() { return "reentrant-" + getId(); }
    public String asLongText() { return asShortText(); }
    public boolean equals(Object other) { return other == this; }
    public int hashCode() { return System.identityHashCode(this); }
  }

  static final class ClosingMux extends AbstractMuxHandler<ByteBuf> {
    final List<PendingWrite> writes = new ArrayList<>();
    long nextId;

    ClosingMux() { super(512); }
    protected Function1<MuxChannel<ByteBuf>, Unit> getInboundInitializer() {
      return child -> Unit.INSTANCE;
    }
    public ChannelFuture onChildWrite(MuxChannel<ByteBuf> child, ByteBuf data) {
      ReferenceCountUtil.release(data);
      child.close();
      assertFalse(child.isOpen(), "The child callback must close synchronously");
      PendingWrite pending = new PendingWrite(getChannelHandlerContext().channel());
      writes.add(pending);
      return pending;
    }
    public void releaseMessage(ByteBuf msg) { ReferenceCountUtil.release(msg); }
    public void channelRead(ChannelHandlerContext ctx, Object msg) { ReferenceCountUtil.release(msg); }
    protected void onLocalOpen(MuxChannel<ByteBuf> child) {}
    protected void onLocalClose(MuxChannel<ByteBuf> child) {}
    protected void onLocalDisconnect(MuxChannel<ByteBuf> child) {}
    protected void onChildClosed(MuxChannel<ByteBuf> child) {}
    protected MuxId generateNextId() {
      return new Id(getChannelHandlerContext().channel().id(), ++nextId);
    }
  }
}

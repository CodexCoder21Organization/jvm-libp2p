package io.libp2p.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.libp2p.etc.util.netty.mux.*;
import io.netty.bootstrap.*;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.local.*;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class ZeroLengthUnflushedWriteTerminationTest {
  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "false, true", "true, true"})
  public void zeroByteEntriesRetainTerminationOwnership(boolean refillInBatch, boolean cancelled)
      throws Exception {
    Path output = Files.createTempFile("zero-byte-termination", ".log");
    Process process = null;
    try {
      process =
          new ProcessBuilder(
                  System.getProperty("java.home") + "/bin/java",
                  "-Xmx128m",
                  "-Dio.netty.transport.outboundBufferEntrySizeOverhead=0",
                  "-cp",
                  System.getProperty("java.class.path"),
                  getClass().getName(),
                  Boolean.toString(refillInBatch),
                  Boolean.toString(cancelled))
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      assertTrue(
          process.waitFor(30, TimeUnit.SECONDS),
          () -> "Zero-byte termination child must exit within 30 seconds: " + readOutput(output));
      assertEquals(
          0,
          process.exitValue(),
          () -> "Zero-byte termination child failed: " + readOutput(output));
    } finally {
      if (process != null) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      Files.deleteIfExists(output);
    }
  }

  private static String readOutput(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  public static void main(String[] args) throws Exception {
    boolean refillInBatch = Boolean.parseBoolean(args[0]);
    boolean cancelled = Boolean.parseBoolean(args[1]);
    assertEquals("0", System.getProperty("io.netty.transport.outboundBufferEntrySizeOverhead"));
    DefaultEventLoop loop = new DefaultEventLoop();
    Channel server = null, parent = null;
    AtomicReference<ByteBuf> payload = new AtomicReference<>();
    AtomicReference<ChannelFuture> accepted = new AtomicReference<>();
    CompletableFuture<Boolean> notifiedOnOwner = new CompletableFuture<>();
    CompletableFuture<Void> batchCompleted = new CompletableFuture<>();
    try {
      CompletingMux mux = new CompletingMux();
      LocalAddress address = new LocalAddress("zero-byte-termination");
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
      Runnable acceptZero =
          () -> {
            ByteBuf bytes = Unpooled.buffer(1);
            payload.set(bytes);
            ChannelFuture write = child.write(bytes);
            accepted.set(write);
            write.addListener(done -> notifiedOnOwner.complete(loop.inEventLoop()));
            assertFalse(write.isDone(), "The zero-byte write must be accepted and pending");
            assertEquals(1, bytes.refCnt(), "The buffer must retain the zero-byte payload");
            if (cancelled) assertTrue(write.cancel(false), "Caller cancellation must succeed");
          };
      if (refillInBatch) {
        loop.submit(
                () ->
                    loop.addShutdownHook(
                        () -> {
                          try {
                            child.flush();
                            acceptZero.run();
                            batchCompleted.complete(null);
                          } catch (Throwable failure) {
                            batchCompleted.completeExceptionally(failure);
                          }
                        }))
            .get(5, TimeUnit.SECONDS);
        ByteBuf first = Unpooled.buffer(1).writeByte(1);
        ChannelFuture initial = child.write(first);
        loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertFalse(initial.isDone(), "The initial write must retain a shutdown-batch owner");
      } else {
        loop.submit(acceptZero).get(5, TimeUnit.SECONDS);
      }
      parent.close().sync();
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      assertTrue(child.isOpen(), "The child close handler must refuse closure");
      server.close().sync();
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      assertTrue(loop.isTerminated(), "Observe only after owner termination");
      if (refillInBatch) batchCompleted.get(5, TimeUnit.SECONDS);
      String state =
          "refillInBatch="
              + refillInBatch
              + " cancelled="
              + cancelled
              + " ownerTerminated="
              + loop.isTerminated()
              + " acceptedDone="
              + accepted.get().isDone()
              + " payloadRefCnt="
              + payload.get().refCnt();
      assertTrue(
          accepted.get().isDone(),
          (refillInBatch
                  ? "A zero-byte shutdown-batch refill must settle before owner termination: "
                  : "An accepted zero-byte unflushed write must settle before owner termination: ")
              + state);
      if (cancelled)
        assertTrue(accepted.get().isCancelled(), "Preserve caller cancellation: " + state);
      else {
        assertInstanceOf(RejectedExecutionException.class, accepted.get().cause(), state);
        assertEquals(
            "The child event loop terminated before pending writes could complete: "
                + child.getId(),
            accepted.get().cause().getMessage(),
            state);
      }
      assertTrue(notifiedOnOwner.get(5, TimeUnit.SECONDS), "Notify on owner: " + state);
      assertEquals(
          0,
          payload.get().refCnt(),
          "Release the retained zero-byte payload exactly once: " + state);
    } finally {
      if (!loop.isShuttingDown()) {
        if (parent != null) parent.close().await(5, TimeUnit.SECONDS);
        if (server != null) server.close().await(5, TimeUnit.SECONDS);
      }
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
      if (payload.get() != null && payload.get().refCnt() > 0) payload.get().release();
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
    CompletingMux() {
      super(512);
    }

    protected Function1<MuxChannel<ByteBuf>, Unit> getInboundInitializer() {
      return ch -> Unit.INSTANCE;
    }

    public ChannelFuture onChildWrite(MuxChannel<ByteBuf> child, ByteBuf data) {
      ReferenceCountUtil.release(data);
      return getChannelHandlerContext().channel().newSucceededFuture();
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

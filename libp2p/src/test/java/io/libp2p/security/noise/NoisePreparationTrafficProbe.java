package io.libp2p.security.noise;

import io.libp2p.core.*;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.Builder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;
import io.libp2p.security.plaintext.PlaintextInsecureChannel;
import io.libp2p.transport.implementation.P2PChannelOverNetty;
import io.netty.channel.EventLoop;
import java.security.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;

public class NoisePreparationTrafficProbe {
  private static final CountDownLatch entered = new CountDownLatch(1);
  private static final CountDownLatch release = new CountDownLatch(1);
  private static final AtomicBoolean firstDigest = new AtomicBoolean(true);
  private static final AtomicReference<Thread> preparationThread = new AtomicReference<>();
  private static final AtomicBoolean onLiveLoop = new AtomicBoolean();
  private static volatile EventLoop liveLoop;
  private static boolean failConstruction;
  private static final ProviderException CONSTRUCTION_FAILURE =
      new ProviderException("Noise construction failure requested by traffic cleanup test");

  private static final class Fixture {
    Host client;
    Host server;
    final AtomicReference<Host> noiseHost = new AtomicReference<>();
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    Provider provider;

    // The test driver rescues a broken probe only after observing its cleanup contract.
    void rescue() throws Exception {
      release.countDown();
      if (provider != null) Security.removeProvider(provider.getName());
      executor.shutdownNow();
      try {
        if (noiseHost.get() != null) noiseHost.get().stop().get(10, TimeUnit.SECONDS);
      } finally {
        try {
          if (client != null) client.stop().get(10, TimeUnit.SECONDS);
        } finally {
          if (server != null) server.stop().get(10, TimeUnit.SECONDS);
        }
      }
      if (!executor.awaitTermination(10, TimeUnit.SECONDS))
        throw new AssertionError("Rescued builder executor did not terminate");
    }
  }

  private static final class Digest extends MessageDigest {
    private final MessageDigest delegate;

    Digest() throws NoSuchAlgorithmException, NoSuchProviderException {
      super("SHA-256");
      delegate = MessageDigest.getInstance("SHA-256", "SUN");
      if (firstDigest.compareAndSet(true, false)) {
        preparationThread.set(Thread.currentThread());
        onLiveLoop.set(liveLoop.inEventLoop());
        new Exception("First prepared SHA-256 allocation").printStackTrace(System.out);
        entered.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS))
            throw new ProviderException("Probe release was not received within 10 seconds");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new ProviderException("Probe digest allocation was interrupted", e);
        }
      }
    }

    @Override
    protected int engineGetDigestLength() {
      if (failConstruction) throw CONSTRUCTION_FAILURE;
      return 32;
    }

    @Override
    protected void engineUpdate(byte x) {
      delegate.update(x);
    }

    @Override
    protected void engineUpdate(byte[] b, int o, int n) {
      delegate.update(b, o, n);
    }

    @Override
    protected byte[] engineDigest() {
      return delegate.digest();
    }

    @Override
    protected void engineReset() {
      delegate.reset();
    }
  }

  private static Host host(boolean plaintext, boolean listen) {
    Builder builder = new Builder();
    builder.identity(
        i -> {
          i.random(KeyType.ED25519);
          return Unit.INSTANCE;
        });
    builder.protocols(
        p -> {
          p.add(new Ping());
          return Unit.INSTANCE;
        });
    if (plaintext)
      builder.secureChannels(
          s -> {
            s.add((key, muxers) -> new PlaintextInsecureChannel(key));
            return Unit.INSTANCE;
          });
    if (listen)
      builder.network(
          n -> {
            n.listen("/ip4/127.0.0.1/tcp/0");
            return Unit.INSTANCE;
          });
    return builder.build(Builder.Defaults.Standard);
  }

  public static void main(String[] args) throws Exception {
    Fixture fixture = new Fixture();
    failConstruction = args.length > 0 && args[0].equals("construction-failure");
    if (!failConstruction) {
      runProbe(args, fixture);
      return;
    }
    Throwable caught = null;
    Throwable reported = null;
    try {
      runProbe(args, fixture);
    } catch (Throwable failure) {
      reported = failure;
      caught = failure;
      while (caught instanceof ExecutionException || caught instanceof CompletionException)
        caught = caught.getCause();
    }
    try {
      if (caught != CONSTRUCTION_FAILURE
          || !"Noise construction failure requested by traffic cleanup test"
              .equals(caught.getMessage()))
        throw new AssertionError(
            "Cleanup did not preserve the original construction failure", caught);
      rejectUnexpectedCleanupFailure(reported);
      if (Security.getProvider(fixture.provider.getName()) != null)
        throw new AssertionError("Provider remained registered after construction failure", caught);
      if (!fixture.executor.isTerminated())
        throw new AssertionError(
            "Builder executor remained active after construction failure", caught);
      for (Host h : List.of(fixture.client, fixture.server)) {
        if (!h.listenAddresses().isEmpty() || !h.getNetwork().getConnections().isEmpty())
          throw new AssertionError(
              "Plaintext host remained open after construction failure", caught);
        for (io.libp2p.core.transport.Transport transport : h.getNetwork().getTransports()) {
          if (transport.getActiveListeners() != 0 || transport.getActiveConnections() != 0)
            throw new AssertionError(
                "Plaintext transport retained sockets after construction failure", caught);
        }
        try {
          h.start().get(10, TimeUnit.SECONDS);
          throw new AssertionError(
              "Stopped plaintext fixture restarted after construction failure");
        } catch (ExecutionException closed) {
          if (!(closed.getCause() instanceof Libp2pException)
              || !"Transport is closed".equals(closed.getCause().getMessage())) throw closed;
        }
      }
      System.out.println("CONSTRUCTION_FAILURE_PRESERVED_AND_ALL_FIXTURES_CLOSED=true");
    } finally {
      fixture.rescue();
    }
  }

  private static void rejectUnexpectedCleanupFailure(Throwable reported) {
    for (Throwable secondary : reported.getSuppressed()) {
      Throwable cause = secondary;
      while (cause instanceof ExecutionException || cause instanceof CompletionException)
        cause = cause.getCause();
      if (cause != CONSTRUCTION_FAILURE)
        throw new AssertionError("Unexpected secondary traffic cleanup failure", secondary);
      rejectUnexpectedCleanupFailure(secondary);
    }
    if (reported.getCause() != null) rejectUnexpectedCleanupFailure(reported.getCause());
  }

  private static void runProbe(String[] args, Fixture fixture) throws Exception {
    Host client = null;
    Host server = null;
    AtomicReference<Host> noiseHost = fixture.noiseHost;
    ExecutorService executor = fixture.executor;
    Future<Host> building = null;
    CompletableFuture<Void> starting = null;
    Provider provider =
        new Provider(
            "NoisePreparationGatedSHA256", "1.0", "Real SHA-256 with bounded initialization gate") {
          {
            putService(
                new Service(this, "MessageDigest", "SHA-256", Digest.class.getName(), null, null) {
                  @Override
                  public Object newInstance(Object parameter) throws NoSuchAlgorithmException {
                    try {
                      return new Digest();
                    } catch (NoSuchProviderException e) {
                      throw new NoSuchAlgorithmException(
                          "SUN SHA-256 is required by this isolated probe", e);
                    }
                  }
                });
          }
        };
    fixture.provider = provider;
    Throwable primaryFailure = null;
    try {
      server = fixture.server = host(true, true);
      client = fixture.client = host(true, false);
      server.start().get(10, TimeUnit.SECONDS);
      client.start().get(10, TimeUnit.SECONDS);
      StreamPromise<PingController> stream =
          client.newStream(
              List.of("/ipfs/ping/1.0.0"),
              server.getPeerId(),
              server.listenAddresses().toArray(new Multiaddr[0]));
      PingController ping = stream.getController().get(10, TimeUnit.SECONDS);
      ping.ping().get(10, TimeUnit.SECONDS);
      liveLoop =
          ((P2PChannelOverNetty) client.getNetwork().getConnections().get(0))
              .getNettyChannel()
              .eventLoop();
      Security.insertProviderAt(provider, 1);
      Callable<Host> createNoise =
          () -> {
            Host h = host(false, true);
            noiseHost.set(h);
            return h;
          };
      boolean closing = args.length > 0 && args[0].equals("close");
      boolean inline = closing || (args.length > 0 && args[0].equals("inline"));
      building = inline ? liveLoop.submit(createNoise) : executor.submit(createNoise);
      if (!entered.await(10, TimeUnit.SECONDS))
        throw new AssertionError("Noise preparation did not request the scoped digest");
      try {
        CompletableFuture<Void> liveMarker = new CompletableFuture<>();
        liveLoop.execute(() -> liveMarker.complete(null));
        liveMarker.get(1, TimeUnit.SECONDS);
        for (int i = 0; i < 3; i++) ping.ping().get(1, TimeUnit.SECONDS);
        if (onLiveLoop.get())
          throw new AssertionError("Noise preparation ran on the live I/O worker");
        if (inline) {
          Host pending = building.get(1, TimeUnit.SECONDS);
          starting =
              liveLoop
                  .submit((Callable<CompletableFuture<Void>>) pending::start)
                  .get(1, TimeUnit.SECONDS);
          ping.ping().get(1, TimeUnit.SECONDS);
          if (starting.isDone())
            throw new AssertionError("Noise start completed before preparation release");
          if (!pending.listenAddresses().isEmpty())
            throw new AssertionError("Noise listener published before preparation release");
          if (closing) pending.stop().get(10, TimeUnit.SECONDS);
        } else if (building.isDone()) {
          throw new AssertionError("Noise builder returned before preparation release");
        }
      } catch (TimeoutException failure) {
        throw new AssertionError(
            "Live plaintext Ping or event-loop callback stalled during Noise preparation", failure);
      } finally {
        release.countDown();
      }
      Host prepared = building.get(10, TimeUnit.SECONDS);
      if (starting == null) starting = prepared.start();
      if (closing) {
        try {
          starting.get(10, TimeUnit.SECONDS);
          throw new AssertionError("Closed pending host became ready");
        } catch (ExecutionException failure) {
          Throwable cause = failure.getCause();
          while (cause instanceof CompletionException) cause = cause.getCause();
          if (!(cause instanceof Libp2pException)
              || !"Transport is closed".equals(cause.getMessage())) throw failure;
        }
        if (!prepared.listenAddresses().isEmpty()
            || !prepared.getNetwork().getConnections().isEmpty()) {
          throw new AssertionError("Closed pending host published a listener or connection");
        }
      } else {
        starting.get(10, TimeUnit.SECONDS);
      }
      ping.ping().get(10, TimeUnit.SECONDS);
      System.out.println(
          "PLAINTEXT_PING_COMPLETED_WHILE_NOISE_PREPARATION_HELD=" + !onLiveLoop.get());
    } catch (Exception | Error failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      release.countDown();
      Throwable cleanupFailure = null;
      try {
        if (building != null) building.get(10, TimeUnit.SECONDS);
      } catch (Exception | Error failure) {
        cleanupFailure = failure;
      }
      try {
        Security.removeProvider(provider.getName());
      } catch (Exception | Error failure) {
        cleanupFailure = retainFailure(cleanupFailure, failure);
      }
      try {
        executor.shutdownNow();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS))
          throw new AssertionError("Builder executor did not terminate during traffic cleanup");
      } catch (Exception | Error failure) {
        cleanupFailure = retainFailure(cleanupFailure, failure);
      }
      try {
        if (noiseHost.get() != null) noiseHost.get().stop().get(10, TimeUnit.SECONDS);
      } catch (Exception | Error failure) {
        cleanupFailure = retainFailure(cleanupFailure, failure);
      }
      try {
        if (client != null) client.stop().get(10, TimeUnit.SECONDS);
      } catch (Exception | Error failure) {
        cleanupFailure = retainFailure(cleanupFailure, failure);
      }
      try {
        if (server != null) server.stop().get(10, TimeUnit.SECONDS);
      } catch (Exception | Error failure) {
        cleanupFailure = retainFailure(cleanupFailure, failure);
      }
      if (cleanupFailure != null) {
        if (primaryFailure != null) retainFailure(primaryFailure, cleanupFailure);
        else if (cleanupFailure instanceof Exception) throw (Exception) cleanupFailure;
        else throw (Error) cleanupFailure;
      }
    }
  }

  private static Throwable retainFailure(Throwable primary, Throwable later) {
    if (primary == null) return later;
    // A cleanup step can report the original failure again. Avoid adding an
    // exception to itself while keeping independent cleanup failures visible.
    if (primary != later) primary.addSuppressed(later);
    return primary;
  }
}

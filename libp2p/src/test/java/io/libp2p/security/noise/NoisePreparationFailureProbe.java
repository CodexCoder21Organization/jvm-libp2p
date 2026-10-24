package io.libp2p.security.noise;

import com.southernstorm.noise.protocol.Destroyable;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;

public class NoisePreparationFailureProbe {
  private static final String MESSAGE =
      "Noise SHA-256 first-use failure requested by regression test";
  private static final ProviderException FAILURE = new ProviderException(MESSAGE);
  private static int failOnAllocation = 1;
  private static final AtomicInteger allocations = new AtomicInteger();
  private static final AtomicInteger destroyed = new AtomicInteger();
  private static EventLoop liveLoop;
  private static final AtomicBoolean onLiveLoop = new AtomicBoolean();

  private static final class Digest extends MessageDigest implements Destroyable {
    private final int allocation = allocations.incrementAndGet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MessageDigest delegate;

    Digest() throws Exception {
      super("SHA-256");
      delegate = MessageDigest.getInstance("SHA-256", "SUN");
    }

    @Override
    protected int engineGetDigestLength() {
      if (allocation == failOnAllocation) {
        onLiveLoop.set(liveLoop.inEventLoop());
        throw FAILURE;
      }
      return delegate.getDigestLength();
    }

    @Override
    public void destroy() {
      if (!closed.compareAndSet(false, true))
        throw new AssertionError("Temporary digest was destroyed more than once");
      delegate.reset();
      destroyed.incrementAndGet();
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

  private static Host build() {
    Builder b = new Builder();
    b.identity(
        i -> {
          i.random(KeyType.ED25519);
          return Unit.INSTANCE;
        });
    b.protocols(
        p -> {
          p.add(new Ping());
          return Unit.INSTANCE;
        });
    b.network(
        n -> {
          n.listen("/ip4/127.0.0.1/tcp/0");
          return Unit.INSTANCE;
        });
    return b.build(Builder.Defaults.Standard);
  }

  private static Host plain() {
    Builder b = new Builder();
    b.identity(
        i -> {
          i.random(KeyType.ED25519);
          return Unit.INSTANCE;
        });
    b.secureChannels(
        s -> {
          s.add((key, muxers) -> new PlaintextInsecureChannel(key));
          return Unit.INSTANCE;
        });
    b.protocols(
        p -> {
          p.add(new Ping());
          return Unit.INSTANCE;
        });
    b.network(
        n -> {
          n.listen("/ip4/127.0.0.1/tcp/0");
          return Unit.INSTANCE;
        });
    return b.build(Builder.Defaults.Standard);
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("external-second")) failOnAllocation = 2;
    Host client = null;
    Host server = null;
    Host pending = null;
    Host recovered = null;
    Provider provider =
        new Provider("NoisePreparationFailingDigest", "1.0", "Real SHA-256 preparation error") {
          {
            putService(
                new Service(this, "MessageDigest", "SHA-256", Digest.class.getName(), null, null) {
                  @Override
                  public Object newInstance(Object parameter) throws NoSuchAlgorithmException {
                    try {
                      return new Digest();
                    } catch (Exception e) {
                      throw new NoSuchAlgorithmException("Could not create SHA-256 delegate", e);
                    }
                  }
                });
          }
        };
    try {
      server = plain();
      client = plain();
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
      NoiseProbePhasesKt.noiseProbePhase("READY");
      Security.insertProviderAt(provider, 1);
      Throwable caught = null;
      try {
        pending =
            args[0].equals("inline")
                ? liveLoop.submit(NoisePreparationFailureProbe::build).get(10, TimeUnit.SECONDS)
                : build();
        pending.start().get(10, TimeUnit.SECONDS);
      } catch (Throwable error) {
        caught = error;
        while (caught instanceof ExecutionException || caught instanceof CompletionException)
          caught = caught.getCause();
      }
      if (caught != FAILURE || !MESSAGE.equals(caught.getMessage())) {
        throw new AssertionError(
            "Builder/start caller did not receive the original preparation cause and full message",
            caught);
      }
      if (pending != null
          && (!pending.listenAddresses().isEmpty()
              || !pending.getNetwork().getConnections().isEmpty())) {
        throw new AssertionError("Failed preparation published a listener or connection");
      }
      if (!args[0].equals("inline") && pending != null)
        throw new AssertionError("Failed preparation published a host to the builder caller");
      CompletableFuture<Void> marker = new CompletableFuture<>();
      liveLoop.execute(() -> marker.complete(null));
      marker.get(1, TimeUnit.SECONDS);
      ping.ping().get(1, TimeUnit.SECONDS);
      if (failOnAllocation == 2 && destroyed.get() != 1) {
        throw new AssertionError(
            "First temporary role digest was not destroyed before the next role failed: "
                + destroyed.get());
      }
      if (onLiveLoop.get())
        throw new AssertionError("Failing preparation occupied the live I/O worker");
      Security.removeProvider(provider.getName());
      recovered = build();
      recovered.start().get(10, TimeUnit.SECONDS);
      NoiseProbePhasesKt.noiseProbePhase("BODY_COMPLETE");
      System.out.println(
          "ORIGINAL_CAUSE_AND_FULL_MESSAGE=true NO_READY_HOST=true LIVE_WORKER_RESPONSIVE=true RECOVERY=true");
    } finally {
      Security.removeProvider(provider.getName());
      try {
        if (pending != null) pending.stop().get(10, TimeUnit.SECONDS);
      } finally {
        try {
          if (recovered != null) recovered.stop().get(10, TimeUnit.SECONDS);
        } finally {
          try {
            if (client != null) client.stop().get(10, TimeUnit.SECONDS);
          } finally {
            if (server != null) server.stop().get(10, TimeUnit.SECONDS);
            NoiseProbePhasesKt.noiseProbePhase("CLEANUP_COMPLETE");
          }
        }
      }
    }
  }
}

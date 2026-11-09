package io.libp2p.transport;

import io.libp2p.core.Host;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.Builder;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;
import io.netty.channel.DefaultEventLoop;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;

public class ClosedPreparationRetentionProbe {
  static final CountDownLatch entered = new CountDownLatch(1);
  static final CountDownLatch release = new CountDownLatch(1);
  static final AtomicBoolean first = new AtomicBoolean(true);

  static class Digest extends MessageDigest {
    final MessageDigest delegate;

    Digest() throws Exception {
      super("SHA-256");
      delegate = MessageDigest.getInstance("SHA-256", "SUN");
      if (first.compareAndSet(true, false)) {
        System.out.println("FIRST_PROVIDER_CALLER=" + Thread.currentThread().getName());
        entered.countDown();
        if (!release.await(30, TimeUnit.SECONDS))
          throw new AssertionError("Preparation release missing");
      }
    }

    protected int engineGetDigestLength() {
      return delegate.getDigestLength();
    }

    protected void engineUpdate(byte b) {
      delegate.update(b);
    }

    protected void engineUpdate(byte[] b, int o, int n) {
      delegate.update(b, o, n);
    }

    protected byte[] engineDigest() {
      return delegate.digest();
    }

    protected void engineReset() {
      delegate.reset();
    }
  }

  static Host build() {
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
    return b.build(Builder.Defaults.Standard);
  }

  public static void main(String[] ignored) throws Exception {
    Provider provider =
        new Provider("ClosedPreparationSHA256", "1.0", "Owned real SHA-256 allocation gate") {
          {
            putService(
                new Service(this, "MessageDigest", "SHA-256", Digest.class.getName(), null, null) {
                  public Object newInstance(Object parameter) throws NoSuchAlgorithmException {
                    try {
                      return new Digest();
                    } catch (Exception e) {
                      throw new NoSuchAlgorithmException("Digest preparation failed", e);
                    }
                  }
                });
          }
        };
    DefaultEventLoop loop = new DefaultEventLoop();
    loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
    Security.insertProviderAt(provider, 1);
    try {
      Host firstHost =
          loop.submit((Callable<Host>) ClosedPreparationRetentionProbe::build)
              .get(10, TimeUnit.SECONDS);
      if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("Provider not entered");
      firstHost.stop().get(10, TimeUnit.SECONDS);
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
      int baseline = ManagementFactory.getThreadMXBean().getThreadCount();
      System.out.println("CLOSED_HOSTS=1 THREADS=" + baseline);
      int previous = baseline;
      // Each wave measures completed stopped-host history, so these lifecycles are sequential.
      for (int wave = 1; wave <= 2; wave++) {
        for (int n = 0; n < 16; n++) {
          Host next =
              loop.submit((Callable<Host>) ClosedPreparationRetentionProbe::build)
                  .get(10, TimeUnit.SECONDS);
          next.stop().get(10, TimeUnit.SECONDS);
          if (!next.listenAddresses().isEmpty() || !next.getNetwork().getConnections().isEmpty())
            throw new AssertionError("Closed host has network resources");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int count;
        do {
          count = ManagementFactory.getThreadMXBean().getThreadCount();
          if (count <= baseline + 2) break;
          java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        } while (System.nanoTime() < deadline);
        System.out.println(
            "CLOSED_HOSTS="
                + (1 + wave * 16)
                + " THREADS="
                + count
                + " WAVE_GROWTH="
                + (count - previous));
        previous = count;
      }
      if (previous > baseline + 2)
        throw new AssertionError(
            "Closed-host history retained "
                + (previous - baseline)
                + " additional live threads while only shared preparation is pending");
      Host survivor =
          loop.submit((Callable<Host>) ClosedPreparationRetentionProbe::build)
              .get(10, TimeUnit.SECONDS);
      try {
        var starting = survivor.start();
        if (starting.isDone())
          throw new AssertionError("Survivor became ready before shared preparation finished");
        release.countDown();
        starting.get(10, TimeUnit.SECONDS);
        Builder peerBuilder = new Builder();
        peerBuilder.identity(
            i -> {
              i.random(KeyType.ED25519);
              return Unit.INSTANCE;
            });
        peerBuilder.protocols(
            p -> {
              p.add(new Ping());
              return Unit.INSTANCE;
            });
        peerBuilder.network(
            n -> {
              n.listen("/ip4/127.0.0.1/tcp/0");
              return Unit.INSTANCE;
            });
        Host peer = peerBuilder.build(Builder.Defaults.Standard);
        try {
          peer.start().get(10, TimeUnit.SECONDS);
          var stream =
              survivor.<PingController>newStream(
                  Collections.singletonList("/ipfs/ping/1.0.0"),
                  peer.getPeerId(),
                  peer.listenAddresses().get(0));
          stream.getController().get(10, TimeUnit.SECONDS).ping().get(10, TimeUnit.SECONDS);
          stream.getStream().get(10, TimeUnit.SECONDS).close().get(10, TimeUnit.SECONDS);
          System.out.println("SURVIVING_HOST_PING=true");
        } finally {
          peer.stop().get(10, TimeUnit.SECONDS);
        }
      } finally {
        survivor.stop().get(10, TimeUnit.SECONDS);
      }
    } finally {
      release.countDown();
      Security.removeProvider(provider.getName());
      loop.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
    }
  }
}

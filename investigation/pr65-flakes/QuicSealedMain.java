package io.libp2p.transport.quic;

import io.libp2p.core.Connection;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.transport.implementation.ConnectionOverNetty;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicException;
import io.netty.handler.codec.quic.QuicTransportError;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;

public class QuicSealedMain {
  public static void main(String[] args) throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(16);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Future<?>> jobs = new ArrayList<>();
    AtomicInteger passed = new AtomicInteger();
    try {
      for (int caller = 0; caller < 16; caller++) {
        final int callerId = caller;
        jobs.add(callers.submit(() -> {
          for (int iteration = 0; iteration < 32; iteration++) {
            String phase = "construct";
            QuicTransport server = QuicTransport.ECDSA(
                KeyKt.generateKeyPair(KeyType.ED25519).getFirst(), Collections.emptyList());
            QuicTransport client = QuicTransport.ECDSA(
                KeyKt.generateKeyPair(KeyType.ED25519).getFirst(), Collections.emptyList());
            CompletableFuture<Connection> inbound = new CompletableFuture<>();
            List<Throwable> propagated = new CopyOnWriteArrayList<>();
            try {
              phase = "listen";
              server.listen(new Multiaddr("/ip4/127.0.0.1/udp/0/quic-v1"), inbound::complete, null)
                  .get(5, TimeUnit.SECONDS);
              Multiaddr address = server.listenAddresses().get(0);
              phase = "dial";
              Connection connection = client.dial(address, established -> {}, null)
                  .get(5, TimeUnit.SECONDS);
              phase = "inbound callback";
              Connection received = inbound.get(5, TimeUnit.SECONDS);
              QuicChannel outbound = (QuicChannel) ((ConnectionOverNetty) connection).getNettyChannel();
              QuicChannel incoming = (QuicChannel) ((ConnectionOverNetty) received).getNettyChannel();
              Channel datagram = outbound.parent();
              Assertions.assertNotNull(outbound.pipeline().get(QuicConnectionExceptionHandler.class));
              Assertions.assertNotNull(incoming.pipeline().get(QuicConnectionExceptionHandler.class));
              outbound.pipeline().addLast("test-exception-capturer", new ChannelInboundHandlerAdapter() {
                @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                  propagated.add(cause);
                }
              });
              phase = "connection error";
              outbound.eventLoop().submit(() -> outbound.pipeline().fireExceptionCaught(
                  new QuicException("invalid QUIC state", QuicTransportError.PROTOCOL_VIOLATION)))
                  .get(5, TimeUnit.SECONDS);
              Assertions.assertTrue(outbound.closeFuture().await(5, TimeUnit.SECONDS),
                  "connection-level QUIC exception must close the affected channel");
              Assertions.assertTrue(datagram.closeFuture().await(5, TimeUnit.SECONDS),
                  "closing an outbound QUIC channel must close its dedicated datagram channel");
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
              while (client.getActiveConnections() > 0 && System.nanoTime() < deadline) Thread.sleep(20);
              Assertions.assertEquals(0, client.getActiveConnections());
              Assertions.assertTrue(propagated.isEmpty());
              passed.incrementAndGet();
              System.out.println("CASE sealedQUIC caller=" + callerId + " iteration=" + iteration + " PASS");
            } catch (Throwable failure) {
              System.out.println("CASE sealedQUIC caller=" + callerId + " iteration=" + iteration + " FAIL phase=" + phase);
              failure.printStackTrace();
              failures.add(failure);
            } finally {
              try { client.close().get(5, TimeUnit.SECONDS); }
              catch (Throwable failure) { failures.add(failure); failure.printStackTrace(); }
              try { server.close().get(5, TimeUnit.SECONDS); }
              catch (Throwable failure) { failures.add(failure); failure.printStackTrace(); }
            }
          }
        }));
      }
      for (Future<?> job : jobs) job.get(360, TimeUnit.SECONDS);
      System.out.println("SEALED_QUIC_SUMMARY calls=512 passed=" + passed.get() + " failures=" + failures.size());
      Assertions.assertTrue(failures.isEmpty(), "Every QUIC call and cleanup must succeed; failures=" + failures.size());
    } finally {
      callers.shutdownNow();
      Assertions.assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}

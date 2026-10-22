package io.libp2p.core;

import static org.junit.jupiter.api.Assertions.*;

import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.dsl.BuilderJ;
import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.mux.StreamMuxerProtocol;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.circuit.CircuitHopProtocol;
import io.libp2p.protocol.circuit.CircuitStopProtocol;
import io.libp2p.protocol.circuit.RelayTransport;
import io.libp2p.security.noise.NoiseXXSecureChannel;
import io.libp2p.transport.tcp.TcpTransport;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RelayConnectionRetryTest {
  @Test
  void inheritedRetryClosesRealRelayConnectionAfterFailedStreamClose() throws Exception {
    List<Host> hosts = new ArrayList<>();
    List<ScheduledThreadPoolExecutor> runners = new ArrayList<>();
    try {
      Host relay = retryRelayHost(List.of(), null, hosts, runners);
      relay.start().get(5, TimeUnit.SECONDS);
      Multiaddr relayAddress = relay.listenAddresses().get(0);
      List<RelayTransport.CandidateRelay> candidates =
          List.of(new RelayTransport.CandidateRelay(relay.getPeerId(), relay.listenAddresses()));
      Host client = retryRelayHost(candidates, null, hosts, runners);
      Host server = retryRelayHost(candidates, relayAddress, hosts, runners);
      CompletableFuture.allOf(client.start(), server.start()).get(5, TimeUnit.SECONDS);
      Multiaddr target =
          relayAddress.concatenated(
              new Multiaddr("/p2p-circuit/p2p/" + server.getPeerId().toBase58()));
      Connection connection =
          client.getNetwork().connect(server.getPeerId(), target).get(5, TimeUnit.SECONDS);
      assertTrue(connection.transport() instanceof RelayTransport);
      connection
          .muxerSession()
          .createStream(new Ping())
          .getController()
          .get(5, TimeUnit.SECONDS)
          .ping()
          .get(5, TimeUnit.SECONDS);
      AtomicInteger requests = new AtomicInteger();
      IOException failure =
          new IOException("Cannot close relay connection: stream close attempt 1 failed.");
      connection.pushHandler(
          "fail-first-relay-close",
          new ChannelOutboundHandlerAdapter() {
            @Override
            public void close(ChannelHandlerContext context, ChannelPromise promise) {
              if (requests.incrementAndGet() == 1) promise.setFailure(failure);
              else context.close(promise);
            }
          });
      ExecutionException reported =
          assertThrows(ExecutionException.class, () -> connection.close().get(5, TimeUnit.SECONDS));
      Throwable cause = reported;
      while (cause.getCause() != null) cause = cause.getCause();
      assertSame(failure, cause);
      assertEquals(
          "Cannot close relay connection: stream close attempt 1 failed.", cause.getMessage());
      assertFalse(connection.closeFuture().isDone());
      connection.retryClose().get(5, TimeUnit.SECONDS);
      connection.closeFuture().get(5, TimeUnit.SECONDS);
      assertEquals(
          2, requests.get(), "The inherited retry must submit a new stream close after failure.");
      connection.retryClose().get(5, TimeUnit.SECONDS);
      assertEquals(
          2, requests.get(), "A closed relay stream must stay closed on subsequent retry.");
      assertTrue(
          client.getNetwork().getConnections().stream().anyMatch(c -> !c.closeFuture().isDone()),
          "Closing the relayed connection must leave its shared connection to the relay open.");
    } finally {
      try {
        CompletableFuture.allOf(hosts.stream().map(Host::stop).toArray(CompletableFuture[]::new))
            .get(10, TimeUnit.SECONDS);
      } finally {
        runners.forEach(ScheduledThreadPoolExecutor::shutdownNow);
        for (ScheduledThreadPoolExecutor runner : runners)
          assertTrue(
              runner.awaitTermination(5, TimeUnit.SECONDS), "Relay maintenance runner must stop.");
      }
    }
  }

  private static Host retryRelayHost(
      List<RelayTransport.CandidateRelay> candidates,
      Multiaddr relayAddress,
      List<Host> hosts,
      List<ScheduledThreadPoolExecutor> runners) {
    HostBuilder builder =
        new HostBuilder()
            .builderModifier(b -> enableRetryRelay(b, candidates, runners))
            .transport(TcpTransport::new)
            .secureChannel(NoiseXXSecureChannel::new)
            .muxer(StreamMuxerProtocol::getYamux)
            .listen("/ip4/127.0.0.1/tcp/0")
            .protocol(new Ping());
    if (relayAddress != null) builder.listen(relayAddress + "/p2p-circuit");
    Host host = builder.build();
    hosts.add(host);
    host.getNetwork().getTransports().stream()
        .filter(t -> t instanceof RelayTransport)
        .map(t -> (RelayTransport) t)
        .forEach(t -> t.setHost(host));
    return host;
  }

  private static void enableRetryRelay(
      BuilderJ builder,
      List<RelayTransport.CandidateRelay> candidates,
      List<ScheduledThreadPoolExecutor> runners) {
    PrivKey key = builder.getIdentity().random().getFactory().invoke();
    builder.getIdentity().setFactory(() -> key);
    PeerId id = PeerId.fromPubKey(key.publicKey());
    CircuitStopProtocol.Binding stop = new CircuitStopProtocol.Binding(new CircuitStopProtocol());
    CircuitHopProtocol.Binding hop =
        new CircuitHopProtocol.Binding(CircuitHopProtocol.RelayManager.limitTo(key, id, 5), stop);
    builder.getProtocols().add(hop);
    builder.getProtocols().add(stop);
    ScheduledThreadPoolExecutor runner = new ScheduledThreadPoolExecutor(1);
    runners.add(runner);
    builder.getTransports().add(u -> new RelayTransport(hop, stop, u, h -> candidates, runner));
  }
}

package io.libp2p.core;

import io.libp2p.core.dsl.HostBuilder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.mux.StreamMuxerProtocol;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;
import io.libp2p.security.tls.TlsSecureChannel;
import io.libp2p.transport.tcp.TcpTransport;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;

class LargePingBufferTest {
  @RepeatedTest(50)
  void tlsYamuxLargePing() throws Exception {
    int pingSize = 200 * 1024;
    Host client =
        new HostBuilder()
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .build();
    Host server =
        new HostBuilder()
            .transport(TcpTransport::new)
            .secureChannel(TlsSecureChannel::ECDSA)
            .muxer(StreamMuxerProtocol::getYamux)
            .protocol(new Ping(pingSize))
            .listen("/ip4/127.0.0.1/tcp/0")
            .build();
    try {
      client.start().get(5, TimeUnit.SECONDS);
      server.start().get(5, TimeUnit.SECONDS);
      Assertions.assertEquals(0, client.listenAddresses().size());
      Assertions.assertEquals(1, server.listenAddresses().size());
      Multiaddr address = server.listenAddresses().get(0);
      StreamPromise<PingController> ping =
          client
              .getNetwork()
              .connect(server.getPeerId(), address)
              .thenApply(connection -> connection.muxerSession().createStream(new Ping(pingSize)))
              .get(5, TimeUnit.SECONDS);
      Stream stream = ping.getStream().get(5, TimeUnit.SECONDS);
      PingController controller = ping.getController().get(5, TimeUnit.SECONDS);
      // Each response must complete before the next request, as in HostTestJava.largePing.
      for (int index = 0; index < 10; index++) {
        Assertions.assertTrue(controller.ping().get(5, TimeUnit.SECONDS) >= 0);
      }
      stream.close().get(5, TimeUnit.SECONDS);
      Assertions.assertThrows(
          ExecutionException.class, () -> controller.ping().get(5, TimeUnit.SECONDS));
    } finally {
      try {
        client.stop().get(5, TimeUnit.SECONDS);
      } finally {
        server.stop().get(5, TimeUnit.SECONDS);
      }
    }
  }
}

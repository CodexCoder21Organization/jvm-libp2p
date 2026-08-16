package io.libp2p.network;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.libp2p.core.ConnectionHandler;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NetworkImplJavaCompatibilityTest {

  @Test
  void twoArgumentJvmConstructorRemainsAvailable() throws Exception {
    ConnectionHandler connectionHandler = connection -> {};
    NetworkImpl network = new NetworkImpl(Collections.emptyList(), connectionHandler);

    try {
      assertSame(connectionHandler, network.getConnectionHandler());
    } finally {
      network.close().get(10, TimeUnit.SECONDS);
    }
  }
}

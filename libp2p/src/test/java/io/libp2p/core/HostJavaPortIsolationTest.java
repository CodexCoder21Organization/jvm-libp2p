package io.libp2p.core;

import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class HostJavaPortIsolationTest {
  @Test
  void javaHostFixtureWorksWhileLegacyEndpointIsOwned() throws Exception {
    try (ServerSocket occupied = new ServerSocket(40002)) {
      new HostTestJava().ping();
    }
  }
}

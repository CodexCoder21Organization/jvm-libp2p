package io.libp2p.core;

import io.libp2p.tools.LegacyPortGuard;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class HostJavaPortIsolationTest {
  @Test
  void javaHostFixtureWorksWithPreheldLegacyEndpoint() throws Exception {
    try (ServerSocket owner = LegacyPortGuard.holdIfFree(40002)) {
      javaHostFixtureWorksWhileLegacyEndpointIsOwned();
    }
  }

  @Test
  void javaHostFixtureWorksWhileLegacyEndpointIsOwned() throws Exception {
    try (ServerSocket occupied = LegacyPortGuard.holdIfFree(40002)) {
      new HostTestJava().largeBlob();
    }
  }
}

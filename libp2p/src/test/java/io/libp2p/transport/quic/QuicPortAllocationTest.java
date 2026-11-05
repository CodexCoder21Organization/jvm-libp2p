package io.libp2p.transport.quic;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class QuicPortAllocationTest {
  @Test
  void quicFixtureRequestsAnOsAssignedPort() {
    Assertions.assertEquals(0, QuicServerTestJava.getPort());
  }
}

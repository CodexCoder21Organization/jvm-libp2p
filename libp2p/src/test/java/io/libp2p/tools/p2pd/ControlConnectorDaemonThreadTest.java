package io.libp2p.tools.p2pd;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.unix.DomainSocketAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlConnectorDaemonThreadTest {
  @TempDir Path directory;

  @Test
  void sharedTcpControlWorkersAreDaemonAfterListenerClose() throws Exception {
    checkWorker(new TCPControlConnector(), new InetSocketAddress("127.0.0.1", 0), "TCP");
  }

  @Test
  void sharedUnixControlWorkersAreDaemonAfterListenerClose() throws Exception {
    if (Epoll.isAvailable()) {
      Path socket = directory.resolve("control.sock");
      try {
        checkWorker(
            new UnixSocketControlConnector(), new DomainSocketAddress(socket.toFile()), "Unix");
      } finally {
        Files.deleteIfExists(socket);
      }
    } else {
      Assertions.assertNotNull(Epoll.unavailabilityCause());
    }
  }

  private void checkWorker(ControlConnector connector, SocketAddress address, String kind)
      throws Exception {
    Channel channel = connector.listen(address, handler -> {}).sync().channel();
    EventLoop worker = channel.eventLoop();
    boolean daemon;
    try {
      daemon = worker.submit(() -> Thread.currentThread().isDaemon()).get(5, TimeUnit.SECONDS);
    } finally {
      channel.close().sync();
    }
    Assertions.assertTrue(daemon, kind + " shared control worker must be a daemon thread");
    // These fixture groups have process lifetime, so closing a listener leaves its worker alive.
    Assertions.assertTrue(
        worker.submit(() -> Thread.currentThread().isDaemon()).get(5, TimeUnit.SECONDS),
        "The process-lifetime worker must remain daemon after its listener closes");
  }
}

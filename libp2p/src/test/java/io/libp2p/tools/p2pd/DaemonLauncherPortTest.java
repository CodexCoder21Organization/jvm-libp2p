package io.libp2p.tools.p2pd;

import com.google.protobuf.ByteString;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import p2pd.pb.P2Pd;

class DaemonLauncherPortTest {
  @TempDir Path directory;

  @Test
  void commandEndpointIsAllocatedByTheChildAndUsedByTheHost() throws Exception {
    Path launcher = directory.resolve("daemon");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classes = Path.of(CommandPeer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    String response = Base64.getEncoder().encodeToString(P2Pd.Response.newBuilder()
        .setType(P2Pd.Response.Type.OK)
        .setIdentify(P2Pd.IdentifyResponse.newBuilder().setId(ByteString.copyFrom(new byte[32])))
        .build().toByteArray());
    Files.writeString(launcher, "#!/bin/sh\nexec '" + java.replace("'", "'\\''") + "' -cp '"
        + classes.replace("'", "'\\''") + "' '" + CommandPeer.class.getName() + "' '" + response + "' \"$@\"\n",
        StandardCharsets.UTF_8);
    Assertions.assertTrue(launcher.toFile().setExecutable(true));
    try (ServerSocket occupied = new ServerSocket(11111)) {
      DaemonLauncher.Daemon daemon = new DaemonLauncher(launcher.toString()).launch(0);
      try {
        byte[] peerId = CompletableFuture.supplyAsync(() -> daemon.host.getMyId().getIdBytes())
            .get(5, TimeUnit.SECONDS);
        Assertions.assertArrayEquals(new byte[32], peerId);
      } finally {
        daemon.host.close();
        daemon.kill();
      }
    }
  }

  // Real child process with an OS-assigned command listener and one wire response.
  // The helper uses only JDK classes so it runs with the test classes directory alone.
  public static class CommandPeer {
    public static void main(String[] args) throws Exception {
      String endpoint = null;
      for (int i = 1; i < args.length - 1; i++) {
        if (args[i].equals("-listen")) endpoint = args[++i];
      }
      if (endpoint == null) throw new IllegalArgumentException("Missing -listen endpoint");
      int port = Integer.parseInt(endpoint.substring(endpoint.lastIndexOf('/') + 1));
      try (ServerSocket server = new ServerSocket(port)) {
        System.out.println("Control socket: /ip4/127.0.0.1/tcp/" + server.getLocalPort());
        System.out.flush();
        try (Socket client = server.accept()) {
          int length = 0;
          int shift = 0;
          int value;
          do {
            value = client.getInputStream().read();
            if (value < 0) throw new java.io.EOFException("Command request ended before its length");
            length |= (value & 127) << shift;
            shift += 7;
          } while ((value & 128) != 0);
          if (client.getInputStream().readNBytes(length).length != length) {
            throw new java.io.EOFException("Command request ended before its payload");
          }
          byte[] response = Base64.getDecoder().decode(args[0]);
          client.getOutputStream().write(response.length);
          client.getOutputStream().write(response);
          client.getOutputStream().flush();
        }
      }
    }
  }
}

package io.libp2p.transport.quic;

import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.crypto.*;
import io.libp2p.core.dsl.*;
import io.libp2p.core.multiformats.*;
import io.libp2p.protocol.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import kotlin.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

public class QuicKuboTestJava {
  @Test
  void pingKubo(@TempDir Path directory) throws Exception {
    String executablePath = System.getProperty("libp2p.test.kubo.executable");
    Assertions.assertNotNull(executablePath, "Gradle must supply libp2p.test.kubo.executable");
    Path executable = Path.of(executablePath);
    Assertions.assertTrue(
        Files.isExecutable(executable), "Kubo test binary is not executable: " + executable);
    Path repository = directory.resolve("node");
    runKubo(executable, repository, "init", "--profile=test");
    runKubo(executable, repository, "config", "Addresses.API", "/ip4/127.0.0.1/tcp/0");
    runKubo(
        executable,
        repository,
        "config",
        "--json",
        "Addresses.Swarm",
        "[\"/ip4/127.0.0.1/udp/0/quic-v1\"]");
    runKubo(executable, repository, "config", "--json", "Bootstrap", "[]");
    runKubo(executable, repository, "config", "--json", "Discovery.MDNS.Enabled", "false");
    runKubo(executable, repository, "config", "--json", "Swarm.DisableNatPortMap", "true");

    Path log = directory.resolve("daemon.log");
    Host clientHost = null;
    Process daemon = null;
    try (WatchService changes = FileSystems.getDefault().newWatchService()) {
      directory.register(changes, StandardWatchEventKinds.ENTRY_MODIFY);
      daemon =
          new ProcessBuilder(
                  executable.toString(),
                  "--repo-dir",
                  repository.toString(),
                  "daemon",
                  "--routing=none")
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.readString(log).contains("Daemon is ready")) {
        Assertions.assertTrue(
            daemon.isAlive(), () -> "Kubo exited before readiness: " + readLog(log));
        long remaining = deadline - System.nanoTime();
        Assertions.assertTrue(remaining > 0, () -> "Kubo did not become ready: " + readLog(log));
        WatchKey change = changes.poll(remaining, TimeUnit.NANOSECONDS);
        Assertions.assertNotNull(change, () -> "Kubo did not become ready: " + readLog(log));
        change.reset();
      }

      String api = Files.readString(repository.resolve("api")).trim();
      int apiPort = Integer.parseInt(api.substring(api.lastIndexOf('/') + 1));
      JSONObject identity = getKuboIdentity(apiPort);
      PeerId peerId = PeerId.fromBase58(identity.getString("ID"));
      JSONArray addresses = identity.getJSONArray("Addresses");
      String quicAddress = null;
      for (int index = 0; index < addresses.length(); index++) {
        String address = addresses.getString(index);
        if (address.startsWith("/ip4/127.0.0.1/udp/") && address.contains("/quic-v1")) {
          quicAddress = address;
          break;
        }
      }
      Assertions.assertNotNull(
          quicAddress, "Owned Kubo did not advertise its loopback QUIC address: " + identity);

      clientHost =
          new HostBuilder().keyType(KeyType.ED25519).secureTransport(QuicTransport::ECDSA).build();
      clientHost.start().get(5, TimeUnit.SECONDS);
      Assertions.assertEquals(0, clientHost.listenAddresses().size());
      StreamPromise<PingController> ping =
          clientHost
              .getNetwork()
              .connect(peerId, new Multiaddr(quicAddress))
              .thenApply(it -> it.muxerSession().createStream(new Ping()))
              .get(5, TimeUnit.SECONDS);
      Stream pingStream = ping.getStream().get(5, TimeUnit.SECONDS);
      PingController pingCtr = ping.getController().get(5, TimeUnit.SECONDS);
      for (int i = 0; i < 1000; i++) {
        long latency = pingCtr.ping().get(1, TimeUnit.SECONDS);
        System.out.println("Ping is " + latency);
      }
      pingStream.close().get(5, TimeUnit.SECONDS);
      Assertions.assertThrows(
          ExecutionException.class, () -> pingCtr.ping().get(5, TimeUnit.SECONDS));
    } finally {
      try {
        if (clientHost != null) clientHost.stop().get(5, TimeUnit.SECONDS);
      } finally {
        if (daemon != null) {
          daemon.destroy();
          if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
            daemon.destroyForcibly();
            Assertions.assertTrue(daemon.waitFor(5, TimeUnit.SECONDS), "Owned Kubo did not exit");
          }
          System.out.println(readLog(log));
        }
      }
    }
  }

  private static void runKubo(Path executable, Path repository, String... arguments)
      throws Exception {
    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.add("--repo-dir");
    command.add(repository.toString());
    command.addAll(Arrays.asList(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try {
      Assertions.assertTrue(
          process.waitFor(5, TimeUnit.SECONDS), "Kubo command did not exit: " + command);
      String output =
          new String(
              process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      Assertions.assertEquals(
          0, process.exitValue(), "Kubo command failed: " + command + "\n" + output);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        Assertions.assertTrue(
            process.waitFor(5, TimeUnit.SECONDS), "Kubo command did not stop: " + command);
      }
    }
  }

  private static String readLog(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new UncheckedIOException("Could not read owned Kubo log " + path, failure);
    }
  }

  private static JSONObject getKuboIdentity(int port) throws IOException, URISyntaxException {
    HttpURLConnection conn =
        (HttpURLConnection)
            new URI("http://127.0.0.1:" + port + "/api/v0/id").toURL().openConnection();
    conn.setConnectTimeout(1_000);
    conn.setReadTimeout(1_000);
    conn.setDoInput(true);
    conn.setDoOutput(true);
    try {
      try (DataOutputStream dout = new DataOutputStream(conn.getOutputStream())) {
        dout.write(new byte[0]);
      }
      try (InputStream input = conn.getInputStream()) {
        return new JSONObject(
            new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
    } finally {
      conn.disconnect();
    }
  }

  @Test
  void keyPairGeneration() {
    Pair<PrivKey, PubKey> pair = KeyKt.generateKeyPair(KeyType.SECP256K1);
    PeerId peerId = PeerId.fromPubKey(pair.component2());
    System.out.println("PeerId: " + peerId.toHex());
  }
}

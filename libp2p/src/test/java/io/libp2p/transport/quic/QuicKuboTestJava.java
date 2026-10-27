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
    Path configPath = repository.resolve("config");
    JSONObject config = new JSONObject(Files.readString(configPath));
    config
        .getJSONObject("Addresses")
        .put("API", "/ip4/127.0.0.1/tcp/0")
        .put("Gateway", "/ip4/127.0.0.1/tcp/0")
        .put("Swarm", new JSONArray().put("/ip4/127.0.0.1/udp/0/quic-v1"));
    config.put("Bootstrap", new JSONArray());
    config.getJSONObject("Discovery").getJSONObject("MDNS").put("Enabled", false);
    config.getJSONObject("Swarm").put("DisableNatPortMap", true);
    Files.writeString(configPath, config.toString());

    Path log = directory.resolve("daemon.log");
    Host clientHost = null;
    Process daemon = null;
    DaemonOutput output = null;
    try {
      daemon =
          new ProcessBuilder(
                  executable.toString(),
                  "--repo-dir",
                  repository.toString(),
                  "daemon",
                  "--routing=none")
              .redirectErrorStream(true)
              .start();
      output = new DaemonOutput(daemon, log);
      output.awaitReadiness();

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
          try {
            try {
              if (daemon.isAlive()) runKubo(executable, repository, "shutdown");
            } finally {
              if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                daemon.destroyForcibly();
                Assertions.assertTrue(
                    daemon.waitFor(5, TimeUnit.SECONDS), "Owned Kubo did not exit");
              }
            }
          } finally {
            if (output != null) output.close();
          }
          Path stoppedLog = directory.resolve("stopped-daemon.log");
          Files.move(log, stoppedLog);
          System.out.println(readLog(stoppedLog));
        }
      }
    }
  }

  private static class DaemonOutput implements AutoCloseable {
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final ExecutorService reader =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "owned-kubo-output");
              thread.setDaemon(true);
              return thread;
            });
    private final Future<?> drained;
    private final Path log;
    private final InputStream input;

    DaemonOutput(Process daemon, Path log) {
      this.log = log;
      this.input = daemon.getInputStream();
      drained =
          reader.submit(
              () -> {
                try (BufferedReader input =
                        new BufferedReader(
                            new InputStreamReader(
                                this.input, java.nio.charset.StandardCharsets.UTF_8));
                    BufferedWriter output =
                        Files.newBufferedWriter(
                            log, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                  String line;
                  while ((line = input.readLine()) != null) {
                    output.write(line);
                    output.newLine();
                    output.flush();
                    if (line.contains("Daemon is ready")) ready.complete(null);
                  }
                  ready.completeExceptionally(
                      new IOException("Kubo exited before readiness: " + readLog(log)));
                } catch (IOException failure) {
                  ready.completeExceptionally(failure);
                  throw new UncheckedIOException("Could not read owned Kubo output", failure);
                }
              });
    }

    void awaitReadiness() throws Exception {
      awaitReadiness(() -> null);
    }

    void awaitReadiness(Callable<Void> beforeWaiting) throws Exception {
      beforeWaiting.call();
      try {
        ready.get(5, TimeUnit.SECONDS);
      } catch (TimeoutException failure) {
        Assertions.fail("Kubo did not become ready: " + readLog(log), failure);
      }
    }

    @Override
    public void close() throws Exception {
      try {
        drained.get(5, TimeUnit.SECONDS);
      } finally {
        try {
          input.close();
        } finally {
          reader.shutdownNow();
          Assertions.assertTrue(
              reader.awaitTermination(5, TimeUnit.SECONDS), "Kubo output reader did not stop");
        }
      }
    }
  }

  @Test
  void readinessAlreadyBufferedWithoutFileNotification(@TempDir Path directory) throws Exception {
    Path logs = Files.createDirectory(directory.resolve("logs"));
    Path log = logs.resolve("daemon.log");
    Files.write(log, new byte["Daemon is ready\n".length()]);
    Path emitted = directory.resolve("emitted");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                ReadinessEmitter.class.getName(),
                emitted.toString(),
                log.toString())
            .redirectErrorStream(true)
            .start();
    DaemonOutput output = null;
    try {
      output = new DaemonOutput(process, log);
      output.awaitReadiness(
          () -> {
            process.getOutputStream().write(1);
            process.getOutputStream().flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(emitted)) {
              Assertions.assertTrue(
                  System.nanoTime() < deadline, "Readiness child did not emit output");
              Thread.yield();
            }
            Assertions.assertTrue(
                Files.readString(log).contains("Daemon is ready"),
                "Mapped readiness must already be readable before waiting for its notification");
            return null;
          });
      Assertions.assertTrue(process.isAlive(), "Readiness must not require process exit");
    } finally {
      try {
        process.getOutputStream().close();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          Assertions.assertTrue(
              process.waitFor(5, TimeUnit.SECONDS), "Readiness child did not exit");
        }
      } finally {
        if (output != null) output.close();
      }
    }
  }

  static class ReadinessEmitter {
    public static void main(String[] args) throws Exception {
      System.in.read();
      byte[] ready = "Daemon is ready\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      // Mapped writes make file data visible without relying on directory notifications.
      // Keep the mapping's channel open until the parent releases the child.
      try (java.nio.channels.FileChannel log =
          java.nio.channels.FileChannel.open(
              Path.of(args[1]), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        log.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, ready.length).put(ready);
        System.out.write(ready);
        System.out.flush();
        Files.createFile(Path.of(args[0]));
        System.in.read();
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
    Path output = Files.createTempFile(repository.getParent(), "kubo-command-", ".log");
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      Assertions.assertTrue(
          process.waitFor(5, TimeUnit.SECONDS),
          () -> "Kubo command did not exit: " + command + "\n" + readLog(output));
      Assertions.assertEquals(
          0, process.exitValue(), "Kubo command failed: " + command + "\n" + readLog(output));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        Assertions.assertTrue(
            process.waitFor(5, TimeUnit.SECONDS), "Kubo command did not stop: " + command);
      }
      Files.deleteIfExists(output);
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

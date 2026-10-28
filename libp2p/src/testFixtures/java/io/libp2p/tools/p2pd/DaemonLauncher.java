package io.libp2p.tools.p2pd;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class DaemonLauncher {

  public static class Daemon {
    public final P2PDHost host;
    private final Process process;

    public Daemon(P2PDHost host, Process process) {
      this.host = host;
      this.process = process;
    }

    public void kill() {
      host.close();
      process.destroyForcibly();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("p2pd process did not exit after being destroyed");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while stopping p2pd", failure);
      }
    }
  }

  private final String daemonPath;

  public DaemonLauncher(String daemonPath) {
    this.daemonPath = daemonPath;
  }

  public Daemon launch(int nodePort, String... commandLineArgs) {
    ArrayList<String> args = new ArrayList<>();
    args.add(daemonPath);
    args.add("-hostAddrs");
    args.add("/ip4/127.0.0.1/tcp/" + nodePort);
    args.addAll(Arrays.asList(commandLineArgs));
    // p2pd reports its concrete control endpoint on stdout when quiet mode is off.
    args.add("-q=false");
    args.add("-listen");
    args.add("/ip4/127.0.0.1/tcp/0");
    try {
      Process process =
          new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      CompletableFuture<InetSocketAddress> endpoint = new CompletableFuture<>();
      Thread output =
          new Thread(
              () -> {
                try (BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    String prefix = "Control socket: /ip4/127.0.0.1/tcp/";
                    if (line.startsWith(prefix)) {
                      int port = Integer.parseInt(line.substring(prefix.length()).trim());
                      if (port <= 0 || port > 65535) {
                        throw new IOException("p2pd reported an invalid control endpoint: " + line);
                      }
                      endpoint.complete(new InetSocketAddress("127.0.0.1", port));
                    }
                  }
                  endpoint.completeExceptionally(
                      new IOException("p2pd exited before reporting its control endpoint"));
                } catch (Exception failure) {
                  // If startup already completed, process shutdown can close this reader.
                  endpoint.completeExceptionally(failure);
                }
              },
              "p2pd-output");
      output.setDaemon(true);
      output.start();
      try {
        return new Daemon(new P2PDHost(endpoint.get(5, TimeUnit.SECONDS)), process);
      } catch (Exception failure) {
        process.destroyForcibly();
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "p2pd did not report an allocated control endpoint for " + args, failure);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

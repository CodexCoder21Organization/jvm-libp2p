package io.libp2p.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class ReviewChildLoopAffinityTest {
  @ParameterizedTest
  @ValueSource(strings = {"success", "failure", "cancel"})
  public void parentCompletionWaitsForChildOwner(String mode) throws Exception {
    Path output = Files.createTempFile("review-affinity-", ".log");
    Process process =
        new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-Xmx128m",
                "-Dio.netty.eventLoop.maxPendingTasks=16",
                "-cp",
                System.getProperty("java.class.path"),
                "io.libp2p.transport.ReviewChildLoopAffinityProbe",
                mode)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(
          process.waitFor(45, TimeUnit.SECONDS), "The isolated child ownership probe must finish");
      String text = Files.readString(output);
      System.out.print(text);
      assertEquals(0, process.exitValue(), text);
    } finally {
      process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      Files.deleteIfExists(output);
    }
  }
}

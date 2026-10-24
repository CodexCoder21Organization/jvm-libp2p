package io.libp2p.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class ReviewTerminalWriteConversionTest {
  @ParameterizedTest
  @ValueSource(strings = {"pending", "success", "failure", "cancel"})
  public void publicWriteSettlesAfterOwnerTermination(String parentOutcome) throws Exception {
    Path output = Files.createTempFile("review-public-write-", ".log");
    Process process =
        new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-Xmx128m",
                "-Dio.netty.eventLoop.maxPendingTasks=16",
                "-cp",
                System.getProperty("java.class.path"),
                "io.libp2p.transport.ReviewTerminalWriteConversionProbe",
                "blocked-close",
                parentOutcome)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(
          process.waitFor(45, TimeUnit.SECONDS), "The isolated public write probe must finish");
      String text = Files.readString(output);
      System.out.print(text);
      assertEquals(0, process.exitValue(), text);
    } finally {
      process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      Files.deleteIfExists(output);
    }
  }
}

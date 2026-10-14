package io.libp2p.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MDnsDiscoveryExplicitAddressTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"reverse-ipv4", "reverse-ipv6", "forward-ipv4", "forward-start"})
  void explicitAddressDoesNotConsultSystemDns(String mode) throws Exception {
    // A separate JVM keeps the standard resolver provider local to this scenario.
    // The provider detects DNS work directly, so this verdict does not depend on DNS latency.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertTrue(compiler != null, "The mDNS DNS regression test requires a JDK");
    String classpath = System.getProperty("mdnsTestClasspath");
    assertTrue(classpath != null, "Gradle must supply the mDNS test runtime classpath");
    for (String name : new String[] {"ControlledDns.java", "MdnsNoDnsMain.java"}) {
      try (InputStream source =
          getClass().getResourceAsStream("/mdns-explicit-address/" + name)) {
        assertTrue(source != null, "Missing mDNS test source " + name);
        Files.copy(source, directory.resolve(name));
      }
    }
    assertEquals(
        0,
        compiler.run(
            null,
            null,
            null,
            "--release",
            "18",
            "-cp",
            classpath,
            "-d",
            directory.toString(),
            directory.resolve("ControlledDns.java").toString(),
            directory.resolve("MdnsNoDnsMain.java").toString()),
        "The mDNS resolver-provider regression test requires JDK 18 or later");
    Path service = directory.resolve("META-INF/services/java.net.spi.InetAddressResolverProvider");
    Files.createDirectories(service.getParent());
    Files.writeString(service, "mdnsprobe.ControlledDns\n", StandardCharsets.UTF_8);
    Path output = directory.resolve("child.log");
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-XX:+UseSerialGC",
                "-Dmdnsprobe.mode=" + mode,
                "-cp",
                directory + java.io.File.pathSeparator + classpath,
                "mdnsprobe.MdnsNoDnsMain")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "mDNS child did not finish: " + mode);
      String log = Files.readString(output);
      assertEquals(0, child.exitValue(), log);
      assertTrue(log.contains("MDNS_NO_DNS_OK " + mode + " forward=0 reverse=0"), log);
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS), "mDNS child did not exit: " + mode);
    }
  }
}

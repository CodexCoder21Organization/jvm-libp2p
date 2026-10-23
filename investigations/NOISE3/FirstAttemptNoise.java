import java.nio.file.*;
import java.io.*;
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;

public class FirstAttemptNoise {
  public static void main(String[] args) throws Exception {
    String name = "io.libp2p.security.noise.NoiseHandshakeOffEventLoopTest";
    LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
    if (args[0].equals("class")) builder.selectors(selectClass(name));
    else builder.selectors(selectMethod(name, args[0]));
    SummaryGeneratingListener listener = new SummaryGeneratingListener();
    Launcher launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(builder.build());
    TestExecutionSummary result = listener.getSummary();
    try (PrintWriter out = new PrintWriter(args[1])) {
      out.printf("<testsuite tests=\"%d\" failures=\"%d\" skipped=\"%d\" aborted=\"%d\" attempts=\"1\"/>%n",
        result.getTestsFoundCount(), result.getTestsFailedCount(), result.getTestsSkippedCount(), result.getTestsAbortedCount());
    }
    result.printFailuresTo(new PrintWriter(System.err, true));
    System.exit(result.getTestsFoundCount() > 0 && result.getTestsFailedCount() == 0 && result.getTestsSkippedCount() == 0 && result.getTestsAbortedCount() == 0 ? 0 : 1);
  }
}

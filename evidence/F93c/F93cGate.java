import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

/** Repeat the public JUnit register sequentially; a failing attempt stops the gate. */
public final class F93cGate {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int repeats = Integer.parseInt(args[1]);
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            selectors.add(DiscoverySelectors.selectClass(args[i]));
        }
        Launcher launcher = LauncherFactory.create();
        for (int run = 1; run <= repeats; run++) {
            Path directory = root.resolve(String.format("run-%02d", run));
            Files.createDirectories(directory);
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            try (PrintWriter diagnostic = new PrintWriter(Files.newBufferedWriter(directory.resolve("diagnostic.log")))) {
                launcher.execute(
                    LauncherDiscoveryRequestBuilder.request().selectors(selectors).build(),
                    summary, new LegacyXmlReportGeneratingListener(directory, diagnostic)
                );
                long started = summary.getSummary().getTestsStartedCount();
                long passed = summary.getSummary().getTestsSucceededCount();
                long failed = summary.getSummary().getTestsFailedCount();
                long skipped = summary.getSummary().getTestsSkippedCount();
                long aborted = summary.getSummary().getTestsAbortedCount();
                long containerFailures = summary.getSummary().getContainersFailedCount();
                String counts = String.format(
                    "{\"run\":%d,\"attempts\":%d,\"passed\":%d,\"failed\":%d,\"skipped\":%d,\"aborted\":%d,\"containerFailures\":%d}%n",
                    run, started, passed, failed, skipped, aborted, containerFailures
                );
                Files.writeString(directory.resolve("counts.json"), counts);
                System.out.print(counts);
                System.out.flush();
                if (started == 0 || passed != started || failed != 0 || skipped != 0 || aborted != 0 || containerFailures != 0) {
                    summary.getSummary().printFailuresTo(diagnostic);
                    System.exit(1);
                }
            }
        }
    }
}

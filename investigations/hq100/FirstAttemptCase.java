import java.io.PrintWriter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

public class FirstAttemptCase {
    public static void main(String[] args) throws Exception {
        String name = args[0];
        LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request();
        if (args[1].equals("class")) request.selectors(selectClass(name));
        else request.selectors(selectMethod(name, args[1]));
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request.build());
        TestExecutionSummary result = listener.getSummary();
        try (PrintWriter out = new PrintWriter(args[2])) {
            out.printf("<testsuite tests=\"%d\" failures=\"%d\" skipped=\"%d\" aborted=\"%d\" attempts=\"1\"/>%n",
                result.getTestsFoundCount(), result.getTestsFailedCount(), result.getTestsSkippedCount(), result.getTestsAbortedCount());
        }
        result.printFailuresTo(new PrintWriter(System.err, true));
        for (TestExecutionSummary.Failure failure : result.getFailures()) failure.getException().printStackTrace(System.err);
        System.exit(result.getTestsFoundCount() > 0 && result.getTestsFailedCount() == 0 &&
            result.getTestsSkippedCount() == 0 && result.getTestsAbortedCount() == 0 &&
            result.getContainersFailedCount() == 0 ? 0 : 1);
    }
}

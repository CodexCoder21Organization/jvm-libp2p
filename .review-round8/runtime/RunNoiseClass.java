import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.engine.TestExecutionResult;
import java.io.PrintWriter;
public class RunNoiseClass {
  public static void main(String[] args) {
    var summary = new SummaryGeneratingListener();
    var launcher = LauncherFactory.create();
    launcher.registerTestExecutionListeners(summary, new TestExecutionListener() {
      public void executionFinished(TestIdentifier id, TestExecutionResult r) {
        if (id.isTest()) {
          System.out.println("CASE=" + id.getDisplayName() + " RESULT=" + r.getStatus());
          r.getThrowable().ifPresent(t -> t.printStackTrace(System.out));
        }
      }
    });
    launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(args[0])).build());
    summary.getSummary().printTo(new PrintWriter(System.out, true));
    summary.getSummary().printFailuresTo(new PrintWriter(System.out, true));
    System.out.println("RESULT_JSON={\"tests\":"+summary.getSummary().getTestsFoundCount()+",\"failures\":"+summary.getSummary().getTestsFailedCount()+",\"ms\":"+(summary.getSummary().getTimeFinished()-summary.getSummary().getTimeStarted())+"}");
    System.exit(summary.getSummary().getTestsFailedCount() == 0 && summary.getSummary().getTestsFoundCount()>0 ? 0 : 1);
  }
}

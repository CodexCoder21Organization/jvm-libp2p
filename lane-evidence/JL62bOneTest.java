import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs one existing JUnit invocation without changing its test or production source. */
public final class JL62bOneTest {
    public static void main(String[] args) throws Exception {
        String className = args[0];
        String methodName = args[1];
        int parameterIndex = Integer.parseInt(args[2]);
        Path report = Path.of(args[3]);
        UniqueId id = UniqueId.forEngine("junit-jupiter").append("class", className);
        id = parameterIndex == 0
            ? id.append("method", methodName + "()")
            : id.append("test-template", methodName + "(int)")
                .append("test-template-invocation", "#" + parameterIndex);
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectUniqueId(id)).build();
        var summaryListener = new SummaryGeneratingListener();
        List<String> started = new ArrayList<>();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(summaryListener, new TestExecutionListener() {
            @Override public void executionStarted(TestIdentifier identifier) {
                if (identifier.isTest()) started.add(identifier.getUniqueId());
            }
        });
        long start = System.nanoTime();
        launcher.execute(request);
        var summary = summaryListener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));
        summary.printFailuresTo(new PrintWriter(System.out, true));
        boolean exactlyOne = summary.getTestsFoundCount() == 1
            && summary.getTestsStartedCount() == 1 && started.size() == 1
            && started.get(0).equals(id.toString())
            && summary.getTestsSkippedCount() == 0 && summary.getTestsAbortedCount() == 0
            && summary.getTestsSucceededCount() + summary.getTestsFailedCount() == 1;
        try (var writer = Files.newBufferedWriter(report)) {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
            xml.writeStartDocument(); xml.writeStartElement("testsuite");
            xml.writeAttribute("tests", Long.toString(summary.getTestsFoundCount()));
            xml.writeAttribute("started", Long.toString(summary.getTestsStartedCount()));
            xml.writeAttribute("failures", Long.toString(summary.getTestsFailedCount()));
            xml.writeAttribute("skipped", Long.toString(summary.getTestsSkippedCount()));
            xml.writeAttribute("aborted", Long.toString(summary.getTestsAbortedCount()));
            xml.writeAttribute("exactlyOne", Boolean.toString(exactlyOne));
            xml.writeAttribute("selector", id.toString());
            xml.writeStartElement("testcase");
            xml.writeAttribute("classname", className);
            xml.writeAttribute("name", parameterIndex == 0 ? methodName + "()" : "parameter " + parameterIndex);
            xml.writeAttribute("time", Double.toString((System.nanoTime() - start) / 1e9));
            for (var failure : summary.getFailures()) {
                xml.writeStartElement("failure");
                xml.writeAttribute("type", failure.getException().getClass().getName());
                xml.writeAttribute("message", String.valueOf(failure.getException().getMessage()));
                StringWriter stack = new StringWriter();
                failure.getException().printStackTrace(new PrintWriter(stack));
                xml.writeCharacters(stack.toString()); xml.writeEndElement();
            }
            xml.writeEndElement(); xml.writeEndElement(); xml.writeEndDocument(); xml.close();
        }
        System.exit(!exactlyOne ? 2 : summary.getTestsFailedCount() == 0 ? 0 : 1);
    }
}

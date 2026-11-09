import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import community.kotlin.clocks.simple.SystemClock;
import kompile.ExecutionResult;
import kompile.ExecutionResults;
import kompile.ExecutionStatus;
import kompile.LocalBuildWorkspace;

public class SerialLocal {
    public static void main(String[] args) {
        File root = new File(args[0]);
        File cache = new File(args[1]);
        File log = null;
        List<String> selectors = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--test")) selectors.add(args[++i]);
            else if (args[i].equals("--log")) log = new File(args[++i]);
            else throw new IllegalArgumentException("Unrecognized option: " + args[i]);
        }
        final File resultLog = log;
        ExecutionResults results;
        try (LocalBuildWorkspace workspace = new LocalBuildWorkspace(Arrays.asList(root), cache)) {
            results = workspace.execute(null, null, selectors, 1, resultLog, new SystemClock(), 3);
        }
        int executed = 0;
        int passed = 0;
        for (ExecutionResult result : results.getResults()) {
            if (result.getStatus() == ExecutionStatus.FILTERED_OUT) continue;
            executed++;
            if (result.getStatus() == ExecutionStatus.SUCCESS) passed++;
            System.out.println(result.getFunction() + ": " + result.getStatus());
        }
        System.out.println("FINAL " + passed + "/" + executed + "; first attempts are recorded in " + resultLog);
        if (executed != selectors.size() || passed != executed) System.exit(1);
    }
}

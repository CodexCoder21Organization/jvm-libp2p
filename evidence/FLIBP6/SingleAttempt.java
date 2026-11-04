import java.io.File;
import java.util.Arrays;
import kompile.LocalBuildWorkspace;
import kompile.ExecutionResults;
import kompile.ExecutionStatus;
import community.kotlin.clocks.simple.SystemClock;

public final class SingleAttempt {
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || !args[0].equals("--local")) {
            throw new IllegalArgumentException("Usage: SingleAttempt --local <workspace> <xml> --prebuild | [--repeat=N] <test>...");
        }
        File root = new File(args[1]).getCanonicalFile();
        File cache = new File(System.getProperty("user.home"), ".aibuildcaches/FLIBP6-single-attempt-20261104");
        boolean prebuild = args[3].equals("--prebuild");
        int repetitions = 1;
        int parallelism = 1;
        int firstTest = 3;
        java.util.ArrayList<File> roots = new java.util.ArrayList<>();
        roots.add(root);
        while (!prebuild && args[firstTest].startsWith("--")) {
            String option = args[firstTest++];
            if (option.startsWith("--repeat=")) repetitions = Integer.parseInt(option.substring(9));
            else if (option.startsWith("--parallel=")) parallelism = Integer.parseInt(option.substring(11));
            else if (option.startsWith("--workspace=")) roots.add(new File(option.substring(12)).getCanonicalFile());
            else throw new IllegalArgumentException("Unknown option: " + option);
        }
        boolean success = true;
        long launcherStartedNanos = System.nanoTime();
        long launcherStartedWall = System.currentTimeMillis();
        System.out.println("FLIBP6 stage=beforeWorkspace wallMillis=" + launcherStartedWall);
        try (LocalBuildWorkspace workspace = new LocalBuildWorkspace(roots, cache)) {
            System.out.println("FLIBP6 stage=workspaceReady nativeElapsedMs=" + (System.nanoTime() - launcherStartedNanos) / 1_000_000L + " wallElapsedMs=" + (System.currentTimeMillis() - launcherStartedWall));
            for (int run = 1; run <= repetitions; run++) {
            File report = repetitions == 1 ? new File(args[2]) :
                new File(args[2].replace(".xml", String.format("-%02d.xml", run)));
            java.nio.file.Files.writeString(new File(args[2]).toPath().getParent().resolve("current-run.txt"), Integer.toString(run));
            long executionStartedNanos = System.nanoTime();
            long executionStartedWall = System.currentTimeMillis();
            System.out.println("FLIBP6 stage=beforeExecute run=" + run + " wallMillis=" + executionStartedWall);
            ExecutionResults results = workspace.execute(prebuild ? "foundation.url.protocol.buildMaven()" : null, null,
                prebuild ? java.util.Collections.emptyList() : Arrays.asList(Arrays.copyOfRange(args, firstTest, args.length)), parallelism,
                report, new SystemClock(), 1);
            System.out.println("FLIBP6 stage=afterExecute run=" + run + " nativeElapsedMs=" + (System.nanoTime() - executionStartedNanos) / 1_000_000L + " wallElapsedMs=" + (System.currentTimeMillis() - executionStartedWall));
            long passed = results.getResults(ExecutionStatus.SUCCESS).size();
            long failed = results.getResults(ExecutionStatus.FAILURE).size();
            System.out.println("FLIBP6 run=" + run + " maxTestAttempts=1 passed=" + passed + " failed=" + failed);
            success &= (prebuild || passed > 0) && results.getResults().stream().allMatch(r -> r.getStatus() == ExecutionStatus.SUCCESS);
            }
        }
        if (!success) System.exit(1);
    }
}

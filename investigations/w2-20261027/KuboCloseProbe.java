import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class KuboCloseProbe {
    private static native long openProcess(long pid);
    private static native int processSignal(long handle);
    private static native void closeProcess(long handle);

    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[2]).toAbsolutePath().toString());
        Path executable = Path.of(args[0]).toAbsolutePath();
        Path template = Path.of(args[1]).toAbsolutePath();
        ExecutorService callers = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 128; i++) {
                final int attempt = i;
                attempts.add(callers.submit(() -> runAttempt(executable, template, attempt)));
            }
            int failed = 0;
            for (Future<Boolean> attempt : attempts) {
                try { if (!attempt.get(10, TimeUnit.MINUTES)) failed++; }
                catch (ExecutionException failure) { failed++; failure.printStackTrace(System.out); }
            }
            System.out.println("SUMMARY attempts=128 failed=" + failed);
            if (failed != 0) throw new AssertionError(failed + " immediate deletions failed after Java reported exit");
        } finally {
            callers.shutdownNow();
            if (!callers.awaitTermination(10, TimeUnit.SECONDS)) throw new AssertionError("Probe callers did not stop");
        }
    }

    private static boolean runAttempt(Path executable, Path template, int attempt) throws Exception {
        Path directory = Files.createTempDirectory("kubo-close-probe-" + attempt + "-");
        Path repository = Files.createDirectory(directory.resolve("node"));
        try (java.util.stream.Stream<Path> files = Files.walk(template.getParent())) {
            for (Path source : files.toList()) {
                Path target = repository.resolve(template.getParent().relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target);
            }
        }
        Path log = directory.resolve("daemon.log");
        Process daemon = null;
        long handle = 0;
        try (WatchService changes = FileSystems.getDefault().newWatchService()) {
            directory.register(changes, StandardWatchEventKinds.ENTRY_MODIFY);
            daemon = new ProcessBuilder(executable.toString(), "--repo-dir", repository.toString(), "daemon", "--routing=none")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.readString(log).contains("Daemon is ready")) {
                if (!daemon.isAlive()) throw new AssertionError("Kubo exited before readiness: " + Files.readString(log));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("Kubo readiness deadline: " + Files.readString(log));
                WatchKey event = changes.poll(remaining, TimeUnit.NANOSECONDS);
                if (event == null) throw new AssertionError("Kubo readiness event deadline: " + Files.readString(log));
                event.pollEvents();
                if (!event.reset()) throw new AssertionError("Owned readiness watcher became invalid");
            }
            handle = openProcess(daemon.pid());
            if (handle == 0) throw new AssertionError("Native process handle could not open for " + daemon.pid());
            daemon.destroy();
            if (!daemon.waitFor(5, TimeUnit.SECONDS)) {
                daemon.destroyForcibly();
                if (!daemon.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Kubo did not exit");
            }
            int signalBeforeDelete = processSignal(handle);
            IOException deletion = null;
            try { Files.delete(log); } catch (IOException failure) { deletion = failure; }
            int signalAfterDelete = processSignal(handle);
            System.out.println("ATTEMPT " + attempt + " pid=" + daemon.pid() + " exit=" + daemon.exitValue()
                    + " signalBefore=" + signalBeforeDelete + " signalAfter=" + signalAfterDelete + " deletion=" + deletion);
            if (deletion != null) deletion.printStackTrace(System.out);
            return deletion == null;
        } finally {
            if (daemon != null && daemon.isAlive()) {
                daemon.destroyForcibly();
                if (!daemon.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Probe Kubo did not stop");
            }
            if (handle != 0) closeProcess(handle);
        }
    }
}

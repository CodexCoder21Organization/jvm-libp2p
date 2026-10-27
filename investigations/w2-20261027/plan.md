# Verification probes for the remaining native Gradle work

This branch is evidence only, never a merge candidate. The owner asked to finish handoff hf-2026-10-22-restore-required-verification-for-jvm-libp2p-explicit-close-retry.

## Plan before changes
1. Review the existing CI candidate without changing it.
2. Isolate Windows Kubo destroy/timed-wait/immediate-delete through Java ProcessBuilder and an independently opened native process handle. Record every attempt without retries.
3. Run current Dokka documentation tasks with unchanged memory limits, recording classloader statistics and heap evidence if memory is exhausted.
4. Fix only a demonstrated mechanism; repeat the same reproducer and reversal before changing a candidate PR.

## Ownership invariants for the process probe
| Resource | Creator and owner | Close/terminal condition | Observation | Hostile operation |
|---|---|---|---|---|
| Kubo child | One probe attempt | destroy plus existing five-second timed wait; all children stopped in finally | native process signal and Java exit result | immediate log deletion after wait |
| Native process handle | Same attempt | CloseHandle in finally | WaitForSingleObject(handle, 0) | compare signal with Java result without sleeping |
| Log/repository | Same attempt, unique temporary directory | immediate delete after child exit | deletion succeeds or full exception | concurrent independent attempts own separate paths |
| Documentation classloader | Dokka task | generation should release task-owned runtime state | VM.classloader_stats and JMX memory/class counts | run all real documentation tasks in one bounded build |

No production action, test skip, assertion reduction, timeout/resource increase or consumer workaround is part of these probes.

First Windows probe 40577066631 stopped before its target path: copying config alone omitted Kubo repository version metadata. The correction copies the real initialized repository. This is a probe setup error, not a library failure. Dokka from the same run remains in progress; later dispatch selects Windows alone so that independent probe is not repeated.

OBSERVED: Windows run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40577357438 produced 128/128 immediate deletion failures. Every failed attempt had native process WAIT_TIMEOUT (258) both before and after deletion even though Java waitFor returned true and exitValue returned 1. This distinguishes an unfinished child process from an unobserved unrelated file holder.
OBSERVED: OpenJDK already fixed the relevant fast-path mechanism in https://github.com/openjdk/jdk/commit/b77bd5fd6a6f7ddbed90300fba790da4fb683275 (2024-06-11): timed wait/isAlive use WaitForSingleObject instead of GetExitCodeProcess. Java 17 and 21 update source still have the old check. Next experiment uses the exact same probe on JDK 24, then reverses to 17. No consumer cleanup workaround is added.

JDK24 comparison https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40577802615 stopped before close with a real Kubo readiness deadline. This does not count as a green control or establish a JDK24 start regression. The next controlled experiment runs the unchanged 128 close attempts on17, then24, then17 on one runner, retaining every startup failure and full stack instead of aborting the campaign on its first failed future. No timeout/count changes.
Dokka-only run40577066631 succeeded with original512MiB cap. JMX used Metaspace rose from189476264 before first task to525173912 after last; this shows near-limit accumulation, not a proven retained root or a fix. Compilation plus documentation will be profiled next if needed.

Compiled library/test classes plus documentation also pass on unmodified source at40579031561. This refutes compilation alone as the missing trigger. Next public Gradle workload selects actual assemble/static-analysis/documentation tasks (no test exclusions or assertion changes) with the original default worker count, since the failed full build also loads those plugins. It captures a heap snapshot before tools:schedulers documentation, including unreachable objects and without forcing GC. Need a retained-root path before any implementation correction.

The native process experiment had a distinct readiness-loop defect: reset was called while queued event batches were never consumed. The copied fixture had the same API use. Consume each real WatchKey event batch before reset, retaining128 attempts/four callers/five-second bound. This correction is isolated from the JDK17/24/17 close-result discriminant, and a new comparison is run against the corrected observer. No larger timeout is used.

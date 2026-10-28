# Independent review: documentation guard

Reviewed checkout `/code/ws/W2/workspace/jvm-libp2p-022124` at `37f07a3bdb2476d691358653134559f47bab45f3`. It was clean at review start. I did not edit the checkout, run tests, launch a JVM, or access CI.

## Materials read

- Repository `README.md`; no repository or ancestor `AGENTS.md` was present.
- Full copies of `TESTING.md`, `PHILOSOPHY.md`, `CODE_REVIEW.md`, and `OOM.md` under `/code/ws/W2/notes/011701/`.
- `scripts/verify-documentation.py`, `scripts/documentation-profile.gradle`, `.github/workflows/documentation.yml`, and the relevant Gradle settings/build configuration.
- Existing red evidence and diagnosis were not rerun. For the Detekt task-graph comparison, I read the saved original-red log and diagnostic summary.

## Review result

No blocking implementation defect found in the documentation guard’s stated contract.

The workflow sets up JDK 17 for Android SDK tools, installs API 30/build-tools 30.0.3, then selects JDK 11 for Gradle (`.github/workflows/documentation.yml:20-47`). The verifier makes one cold Gradle invocation with `clean`, `assemble`, `spotlessCheck`, `testClasses`, both documentation aliases plus `dokkaJar`, Android `assembleDebug`, and the stdlib check (`scripts/verify-documentation.py:243-263`). A zero Gradle exit and `BUILD SUCCESSFUL` are required; the full captured log is scanned for OOM, service-stop, and Kotlin metadata diagnostics (`scripts/verify-documentation.py:272-296`).

The verifier checks generated HTML for each of its seven configured source modules, including the Host HTML API page; it checks each Javadoc jar and the public `Host.html` methods; it also inspects the compiled `Host.class` for bytecode major 55 and Kotlin metadata 1.6.0 (`scripts/verify-documentation.py:391-469`). The init task resolves stdlib modules on `:libp2p:runtimeClasspath` and Android debug/release runtime classpaths and requires version 1.6.21 (`scripts/documentation-profile.gradle:162-213`, `scripts/verify-documentation.py:363-389`).

The Gradle root’s cap is measured from its live Metaspace MXBean. Worker command lines are captured with `jcmd`, parsed, deduplicated by PID across task profiles, and summed with the root cap against 512 MiB (`scripts/documentation-profile.gradle:87-127`, `scripts/verify-documentation.py:321-361`). A zero-worker profile is intentionally valid if Dokka uses an in-process or classloader-isolated implementation and the root remains within the cap; it should not be rejected solely for having no process worker.

## Concurrency and worker inventory

I found no demonstrated race in the `tasks.jsonl` writes: Gradle’s shared `TaskExecutionListener` / `TaskExecutionGraph.afterTask` notification path serializes listener notifications, even when project tasks run in parallel ([Gradle issue #36700](https://github.com/gradle/gradle/issues/36700)). Gradle’s Worker API documents process-isolated workers as build-scoped long-lived daemons that can be reused; combined with the current `WorkerIsolation.Process` configuration (`build.gradle:42-46`), this makes the post-task descendant snapshots intentionally conservative. The union by PID also retains workers observed on any Dokka task, including workers present while other Dokka tasks are active.

I found no evidence that `--parallel` makes this workload silently omit concurrent Dokka workers. `ProcessHandle.Info.commandLine()` can be unavailable in some environments and a worker could theoretically exit before a snapshot, but neither is a demonstrated miss for this Linux/JDK 11 build configuration. The missing-command-line case is currently filtered out by `orElse('')`; if that becomes observable in the proof logs, it should fail closed. I do not classify that hypothetical lifecycle case as a current review finding.

## Detekt scope note

The dedicated documentation verifier does not request `detekt` or `check`. The saved original-red invocation did run Detekt tasks in the same build as Dokka, and `:libp2p:detekt` had a known failure. Therefore this verifier alone does not recreate the original full task-execution history/class graph.

This is not a confirmed repository-wide coverage loss: the existing `.github/workflows/build.yml:44-45` still invokes `./gradlew -s build dokkaJar` for non-ignored source/build changes, while it explicitly ignores Markdown-only changes. The separate native proof including Detekt is useful evidence but is not a recurring guard. Treat the omission as an actionable gap only if `documentation.yml` is intended to independently preserve the original OOM route; in that case, include Detekt with explicit handling for the known three baseline findings. Otherwise, the existing native build workflow continues to own that route, and the documentation guard remains scoped to the additional HTML/archive/runtime contracts.

## Item-by-item disposition

- Cold JDK 11 and Android environment: covered by the current workflow configuration.
- Clean, assemble, formatting, test classes, documentation aliases/Javadoc archive content: covered by the verifier command and output assertions.
- Complete normal Gradle result and no captured OOM/shutdown/metadata diagnostics: covered by the exit/summary/log checks; no concrete false-pass found.
- Actual root plus observed Dokka process-worker Metaspace caps: covered by live root measurement and worker PID cap accounting; no concurrent inventory miss demonstrated.
- Host JVM bytecode/Kotlin metadata: covered by `javap -v` assertions.
- Kotlin stdlib 1.6.21 on libp2p and Android debug/release runtime classpaths: covered by resolved component checks.
- Exact original Detekt-inclusive OOM task history: not part of this documentation verifier; still exercised by the existing native `build` workflow for non-ignored changes. Decide whether standalone documentation-workflow coverage is required.

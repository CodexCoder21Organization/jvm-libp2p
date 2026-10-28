# Implementation and DSL parity review: jvm-libp2p

Reviewed checkout `/code/ws/W2/workspace/jvm-libp2p-022124` at `5beb1a8138a0a2947896167ca7620d9bad46c6a6`, comparing the three Groovy build scripts with their `origin/develop` Kotlin DSL counterparts. This was a read-only source review; I did not run Gradle, tests, a JVM, or CI.

## Finding

No concrete implementation or DSL-parity defect found in the reviewed changes.

## Scope decisions

- **Root build configuration (`build.gradle`, replacing `build.gradle.kts`) — reviewed, no unintended dependency/publication/task change found.** Kotlin, Java, test, fixture, formatting, and Detekt declarations match the prior script. The standard-library dependency is now pinned to `1.6.21`, with Gradle's automatic stdlib injection disabled in `gradle.properties`; Kotlin language level stays `1.6`, and JVM source/target/compiler targets stay `11`. Dokka 2 is applied with separate HTML/Javadoc generator publications, output paths `build/dokka` and `build/dokkaJavadoc`, legacy task aliases, and a `javadoc`-classified jar sourced from the Javadoc output directory. Maven repository, group/version selection, variant mapping, sources jar, and publication artifact declarations remain present.
- **`libp2p/build.gradle` — reviewed, no declaration loss found.** Both test-fixture variants are still skipped from the Java component; API/implementation/test-fixture/JMH dependencies and all platform-specific native coordinates are retained. The protobuf protoc coordinate, generated-source IDE path, and generated-file cleanup hook are retained.
- **`interop-test-client/build.gradle` — reviewed, no behavior change found.** Application main class and project/Jedis/logging dependencies are unchanged. The deleted Kotlin compiler `findMainClass` import was unused by the build script.
- **Android chatter and Gradle properties — reviewed, no target regression found.** Java and Kotlin JVM targets remain 11. Android now explicitly sets Kotlin language/API `1.6` and declares stdlib `1.6.21`, consistent with the global opt-out of automatic stdlib injection. Dokka V2 plugin mode is enabled.
- **Review standards — read:** repository README; no `AGENTS.md` found in the checkout or its parent chain; `/code/ws/W2/notes/011701/TESTING.md`, `PHILOSOPHY.md`, `CODE_REVIEW.md`, and `OOM.md`.

## Compatibility caveat (not a demonstrated defect)

The wrapper is Gradle `8.14.3`, while Kotlin's published compatibility table lists Kotlin Gradle Plugin `1.9.20–1.9.25` through Gradle `8.1.1`: [Kotlin Gradle project configuration](https://kotlinlang.org/docs/gradle-configure-project.html). This is an unsupported version pairing on paper, not a defect established by this source review. The exact candidate proof run should determine whether the exercised build paths work with the repository's pinned wrapper; I did not run or inspect that workflow.

## Brief checklist

- Root script dependency, compiler flags, tests, formatting, Detekt, Dokka aliases/outputs/archive, and Maven publication: **reviewed; no concrete unintended difference found**.
- `libp2p` dependency, variant-publication, protobuf, clean, and IDE declarations: **reviewed; retained**.
- Interop application/dependency declarations: **reviewed; retained**.
- Android Kotlin/API/stdlib and JVM 11 settings: **reviewed; aligned with stated intent**.
- Validation: **not run**, as required by the brief; parent owns the separate candidate proof outcome.

## Addendum: Dokka worker/plugin boundary review — 2026-10-28

Reviewed exact head `3476b30f0774bba7cf929443bb2816f78132d40d` against baseline `37f07a3bdb2476d691358653134559f47bab45f3`, limited to the `build.gradle` shared runtime/generator block and the added worker budget. This was source-only; I did not run a build, test, JVM, or workflow. The earlier project standards listed above remain binding.

### Loader partition and task contract

No concrete defect found in the plugin-versus-engine partition itself. The new `sharedRuntime` is the sorted union of the HTML/Javadoc runtime files excluding every JAR that has Dokka's plugin service descriptor (`build.gradle:57-62, 302-316`). Each generator receives its own marker-bearing plugin JARs through `generator.pluginsClasspath`; common base and format plugins therefore stay together in Dokka's plugin classpath, while the generator/compiler runtime remains shared.

This matches Dokka 2.0.0's source contract: `DokkaGenerateTask.runtimeClasspath` is the worker classpath and is annotated `@Classpath`; `DokkaGeneratorParametersSpec.pluginsClasspath` is separately an `@Classpath` input; `DokkaParametersBuilder` passes the plugin-marked files into `DokkaConfiguration.pluginsClasspath`. See [DokkaGenerateTask.kt](https://github.com/Kotlin/dokka/blob/v2.0.0/dokka-runners/dokka-gradle-plugin/src/main/kotlin/tasks/DokkaGenerateTask.kt), [DokkaGeneratorParametersSpec.kt](https://github.com/Kotlin/dokka/blob/v2.0.0/dokka-runners/dokka-gradle-plugin/src/main/kotlin/engine/parameters/DokkaGeneratorParametersSpec.kt), and [DokkaParametersBuilder.kt](https://github.com/Kotlin/dokka/blob/v2.0.0/dokka-runners/dokka-gradle-plugin/src/main/kotlin/engine/parameters/builders/DokkaParametersBuilder.kt).

The partition retains the full set of non-plugin runtime files and each selected generator's plugin JARs. The shared runtime is sorted, and the existing output directories, aliases, `javadoc` archive input/classifier, and publication wiring are unchanged by this diff. The new shared build service has `maxParallelUsages=1` and is attached to all `DokkaGenerateTask`s (`build.gradle:44-48, 238-240`), so Dokka tasks cannot overlap. The current process isolation settings still cap the worker's Metaspace at 144 MiB; the shared runtime and common isolation options allow Gradle's worker pool to reuse that serialized worker. No process finalization hook or new internal-API opt-in was added.

### Findings

1. **Dokka dependency resolution happens for every Gradle invocation.** `gradle.projectsEvaluated` executes after all project scripts have run and before Gradle finalizes the requested task graph. In that callback, `build.gradle:303-310` calls `.get().runtimeClasspath.files` for the root HTML/Javadoc tasks and then reads `.runtimeClasspath.files` for every generator, even if the requested task is only `help`, `test`, or another task that does not generate docs. Resolving those file collections can download Dokka worker artifacts during configuration. On a cold cache, an offline help/test invocation can therefore fail because a Dokka worker dependency is missing, despite no Dokka task being requested. Gradle documents both lifecycle timing and eager file-resolution behavior in [Build Lifecycle](https://docs.gradle.org/current/userguide/build_lifecycle.html) and [Avoid eager APIs on file collections](https://docs.gradle.org/current/userguide/best_practices_tasks.html#avoid-eager-apis-on-file-collections).

   This callback was already present at baseline `37f07a3`; the new loader partition retains it rather than introducing it. It is still a behavior issue in the reviewed head. A cold-cache offline `help` control would establish the user-visible failure. The task-graph-aware lazy boundary discussed with the parent is a possible correction; Gradle marks task-graph listeners as configuration-cache-incompatible, so a provider-based lazy input would be preferable if configuration-cache support is required.

2. **Plugin-classpath order is not explicitly normalized.** `sharedRuntime` is sorted at `build.gradle:305-307`, but `generator.plugins` at line 310 is built by filtering `runtimeClasspath.files` without sorting, then is passed to Dokka's order-sensitive `@Classpath` input. Gradle exposes `FileCollection.getFiles()` as a `Set` without a stable iteration-order guarantee; it also documents that order matters for `@Classpath` inputs. The current resolved configurations may happen to iterate consistently, but this script does not establish that invariant. Sort the filtered plugin JARs by a stable key before calling `pluginsClasspath.from(...)` to make task input and loader order explicit. This line was also present at baseline `37f07a3`; it is not a behavioral change from the latest commit, but it remains in the final boundary implementation.

### Brief checklist

- Plugin JARs including common base stay together in each generation's Dokka plugin classpath: **yes, by the v2.0.0 marker/classpath API**.
- Engine/compiler dependencies remain on the common worker runtime: **yes; non-plugin runtime union is retained and sorted**.
- Task classpath determinism: **shared engine runtime is sorted; plugin JAR order is not explicitly guaranteed by the source and should be normalized**.
- Outputs/archive/dependency availability: **paths, aliases, classifier, publication are unchanged; the classpath partition retains non-plugin files and per-task plugin files**.
- Ordinary no-doc invocations: **eager Dokka runtime resolution remains a concrete cold/offline behavior risk; no control was run in this review**.

## Final addendum: lazy Dokka boundary review — 2026-10-28

Reviewed exact head `d15fee624fa9597356bd539f6bef4aab6be4ab48` against the preceding boundary implementation `3476b30f0774bba7cf929443bb2816f78132d40d`. Scope was the renderer predicate, lazy graph boundary, plugin ordering, new Gradle laziness verifier, and its workflow/README wiring. No Gradle task, test, JVM, or workflow was run in this review. The parent supplied a test-first red result from source `fc2` at [the documented GitHub Actions run](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40624762637); I relied on that evidence without opening the run or artifact. The exact final-source run remains owned by the parent.

### Implementation review

No concrete defect found in this final adjustment.

- **Renderer boundary:** `sharedRuntime` keeps common plugin JARs other than `dokka-base-*`, while each generator's sorted plugin classpath receives the Dokka marker JARs from that generator's original runtime classpath (`build.gradle:302-318`). This keeps common compiler/analysis plugins in the shared engine loader and moves the base renderer, with its resource lookup, to each format's plugin loader. This is narrower than the failed all-plugin split; no repeat of that rejected diagnosis was made.
- **Lazy resolution and task semantics:** `taskGraph.whenReady` first checks for a `DokkaGenerateTask` and returns before reading any runtime classpath when the requested graph has no Dokka generator (`build.gradle:302-305`). On documentation graphs, it changes only the generator's runtime and nested plugin classpath inputs before task execution; it does not add/remove task dependencies. The no-doc path therefore avoids the prior eager runtime resolution on ordinary invocations.
- **Classpath order:** both the shared runtime and each generator's plugin JAR list now sort by absolute path (`build.gradle:307-313`), making input ordering explicit for this fixed workspace.
- **Lazy-resolution control:** `scripts/verify-documentation-laziness.py` installs an init-script `beforeResolve` guard on Dokka-named configurations, then invokes real Gradle `help` with `--offline`. It requires a zero exit code and writes a JUnit result plus a full Gradle log. Its timeout kills the started process group and reaps the wrapper process; the temporary init script is removed by `TemporaryDirectory`. This directly covers the previous configuration-time resolution failure: the parent reports that the baseline control failed with the expected before-resolve message and `build.gradle:303` stack frame.
- **CI and documentation wiring:** the workflow invokes the laziness check after the full documentation contract has populated the plugin cache, and `if: always()` uploads both checks' reports. The README documents the verifier and its expected post-build use.
- **Output/dependency behavior:** the HTML/Javadoc output paths, aliases, Javadoc archive, and publication configuration are unchanged. The shared non-plugin runtime plus each selected generator's marker plugins retains the expected runtime/plugin dependency sets.

### Final checklist

- Dokka base resources and format plugins remain in the per-format plugin loader while common analysis/compiler plugins remain in shared runtime: **reviewed; matches the intended boundary**.
- Ordinary no-doc invocations avoid Dokka runtime resolution: **reviewed; early graph return precedes every `.files` access**.
- Task dependency graph: **no added or removed task dependency identified; the callback changes only classpath inputs**.
- Plugin/runtime ordering: **both classpath lists are explicitly sorted**.
- Verifier cleanup and CI reporting: **reviewed; bounded process-group cleanup, JUnit/log output, and always-upload wiring are present**.
- Validation: **not run in this review; parent owns the separate exact-source positive/negative result**.

**Status of the prior boundary-review findings at this head:** the eager-resolution issue is addressed by the early graph guard, and the unsorted plugin subset is addressed by the path sort. No concrete outstanding issue was found in this final narrow review.

## Final resource-loader addendum — 2026-10-28

Reviewed exact source `f6717ac782ab521ed39cdca7ab0eb024352b7fd7` against `d15fee624fa9597356bd539f6bef4aab6be4ab48`, limited to the Gradle resource boundary. The failed `d15fee` combined proof and its `base.ftl` symptom were supplied by the parent; I did not inspect the run or broaden the diagnosis.

### Review result

No concrete defect found in this resource-boundary change.

- **Renderer and compiler classpaths:** `sharedRuntime` now retains every file common to HTML and Javadoc runtimes, including common renderer and compiler/analysis plugins; non-plugin dependencies from either format also remain in the shared union (`build.gradle:318-321`). Format-specific plugin marker JARs remain assigned to each generator's plugin classpath. The `dokka-base` renderer therefore stays with the shared engine, preserving the common renderer/analysis ABI. This matches the parent’s narrowed fix and avoids the rejected attempt to move every plugin into the child loader.
- **Resource copy ownership:** the root `dokkaJavadocResources` `Sync` task copies `static_res/**` from the Javadoc runtime's plugin-marker JARs into the root build directory `build/dokka-runtime-resources` (`build.gradle:65-72`). The shared Dokka runtime then includes that directory. The Javadoc runtime files are selected in the graph-ready callback; the provider passed to `Sync.from` reads the mutable file collection when its source is queried. Gradle documents `ProviderFactory.provider(Callable)` as live and invokes the callable on each query ([ProviderFactory API](https://docs.gradle.org/current/dsl/org.gradle.api.provider.ProviderFactory.html)); its lifecycle docs place `taskGraph.whenReady` after graph population and before task execution ([Build Lifecycle](https://docs.gradle.org/current/userguide/build_lifecycle.html)). Therefore a query that occurs while calculating task dependencies can see the collection empty, but the later source/input query sees the graph-ready assignment. This exposes Javadoc renderer templates to the shared renderer loader without moving renderer/compiler classes out of it.
- **Task graph and dependency ownership:** each Dokka generator declares `dependsOn dokkaJavadocResources` while tasks are configured (`build.gradle:248-251`), before `taskGraph.whenReady`. Thus the Sync task is in any selected Dokka generation graph before the callback populates its inputs. Its source is the external Javadoc plugin JARs, not a Dokka task output; Dokka tasks depend on the Sync output, so the direction is one-way and no cycle is introduced. The no-Dokka early return still happens before reading runtime files (`build.gradle:313-317`), and the laziness verifier is unchanged.
- **Publication and dependency behavior:** the Sync output is added only to Dokka generator runtime classpaths. No JAR or publication task adds the resource directory as a direct archive source; generated Javadoc continues through the existing `dokkaJar` path and may naturally reflect resources used during rendering. No publication declaration changes. The original common runtime/plugin dependencies are retained; the added resource directory supplements the classpath rather than replacing a dependency.

### Checklist

- Shared renderer/compiler/analysis classes stay in the engine runtime: **yes**.
- Javadoc `static_res/**` is copied to an engine-visible resource directory: **yes**.
- Sync scheduling precedes generator execution without a cycle: **yes by the declared dependency and external-JAR inputs**.
- No-doc graph returns before classpath resolution; no artifact publication path changed: **yes**.
- Concrete findings: **none**. Source-only review; no tests, JVMs, builds, or CI were run or inspected.

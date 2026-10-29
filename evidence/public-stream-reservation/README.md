# Public connection reservation: native verification

UrlResolver 0.0.1337 can select a physically open pooled connection whose retirement has already been approved. Its next stream then fails with “closed or excluded from selection” before a request is sent. This library addition supplies the atomic public reservation needed by the [manager compatibility effort](https://github.com/CodexCoder21Organization/AiCliSupervisorManagerServiceServer/pull/50) and its [handoff](https://www.handoff.wasmserver.com/handoffs/hf-2026-10-26-fix-published-client-compatibility-and-bounded-bytecode-replies-for-the-manager-server).

The fork's default branch is `develop`; it has no `main` branch. The work starts at `90f45d6acc840d55c32adb9c5693c4a7b3dd9a3c`, after the snapshot-41 source-version bump in [25efb1996bd79e59e6eef28d2d44d9a0405fdd78](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/25efb1996bd79e59e6eef28d2d44d9a0405fdd78). There was no version change or publication for this work.

## Native results

These are native Gradle/JUnit XML files copied without modification. The JSON summaries derive their counts from the XML. No retry plugin is configured: each test case has one execution, and every count below is its first execution. The kompile runner and its automatic attempt histories do not apply to this Gradle repository.

| Invocation | Source | Passed | Failed | Errors | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| [Red](red/summary.json) | `beb658144dc3c5d81c6c9ec8baaa581a0782ddd6` | 0 | 3 | 0 | 0 |
| [Green](green/summary.json) | `c5d690fd1d79abbfd175b8a77a17e489ba2e84f2` | 50 | 0 | 0 | 0 |

The red source stages the new public method with the consumer's old `!closeFuture().isDone` rule. This allows assertion failures rather than a missing-method compiler error. All three scenarios fail at the intended assertions: approved retirement is accepted, pending physical close is accepted, and a healthy “reservation” fails to prevent later retirement.

The final implementation delegates to the existing internal atomic reservation after the existing eligibility check. The existing internal connect call remains explicitly directed at the original internal function. No internal selection behavior changed.

Green includes the same three new cases and all 47 existing cases in `io.libp2p.network`. The new cases use real isolated loopback TCP/Noise/Yamux hosts, the public retirement stream protocol and public Netty handler extension points. No reflection, mocks, internal selection access or timing-dependent interleaving is used. Each acceptor callback is explicitly awaited. The cases cover approved-but-open refusal, healthy retention and echo, retirement denied after retention, repeated calls, close in flight and terminal closed refusal. All hosts and close gates are released in `finally`.

## Exact commands

Executed from the fresh checkout at `/tmp/claude-1000/-code/165f6eb4-61b9-47f8-944e-feccecc5cdde/scratchpad/ws/L1-libp2p` using JDK 21.0.13 and the repository's Gradle 8.14.3 distribution. The distribution checksum matched the wrapper: `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.

Before every invocation:

```bash
git fetch origin
git rebase origin/develop
export JAVA_OPTS=-Xmx256m
export GRADLE_USER_HOME=/tmp/claude-1000/-code/165f6eb4-61b9-47f8-944e-feccecc5cdde/scratchpad/out/rL1-gradle-home
```

Red: exit 1, 14m56s, three assertion failures:

```bash
/tmp/claude-1000/-code/165f6eb4-61b9-47f8-944e-feccecc5cdde/scratchpad/out/rL1-gradle/gradle-8.14.3/bin/gradle --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=368m' -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.daemon.jvmargs=-Xmx2g :libp2p:test --tests 'io.libp2p.network.NetworkReservation*Test' --stacktrace
```

Green: exit 0, 13m33s, 50 first-execution passes:

```bash
/tmp/claude-1000/-code/165f6eb4-61b9-47f8-944e-feccecc5cdde/scratchpad/out/rL1-gradle/gradle-8.14.3/bin/gradle --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=368m' -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.daemon.jvmargs=-Xmx2g :libp2p:test --tests 'io.libp2p.network.*' --stacktrace
```

An intervening combined invocation exited 1 before executing any tests. It found a brace-formatting correction in the new method, then emitted the existing 368 MiB Metaspace warning and lost its Gradle daemon while compiling tests. It produced no new test XML and is not counted as a test attempt. The formatter's exact brace correction was applied; static checks and tests were separated into different Gradle invocations. No heap, Metaspace, timeout, retention or iteration limit was increased.

```bash
/tmp/claude-1000/-code/165f6eb4-61b9-47f8-944e-feccecc5cdde/scratchpad/out/rL1-gradle/gradle-8.14.3/bin/gradle --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=368m' -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.daemon.jvmargs=-Xmx2g :libp2p:test --tests 'io.libp2p.network.*' :libp2p:spotlessCheck :libp2p:detekt --stacktrace
```

Final static checks: exit 0, 1m24s; `:libp2p:spotlessCheck` and `:libp2p:detekt` passed:

```bash
/tmp/claude-1000/-code/165f6eb4-61b9-47f8-944e-feccecc5cdde/scratchpad/out/rL1-gradle/gradle-8.14.3/bin/gradle --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=368m' -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.daemon.jvmargs=-Xmx2g :libp2p:spotlessCheck :libp2p:detekt --stacktrace
```

Every JVM invocation was admitted only after raw `/proc/*/cmdline` showed no competing test script/runner/driver and cgroup `memory.max - memory.current` was at least 6 GiB. Admission polled every 60 seconds with a 45-minute deadline; each command had a 45-minute deadline too. Headroom at admission: red `6583054336` bytes; intervening combined command `9022488576` bytes; tests-only green `8517484544` bytes; final static checks `9957859328` bytes. No host-memory estimate from `free` was used. JVM heaps stayed at or below 2 GiB.

Independent test and code review passes found no remaining issues. No full suite or CI completion is claimed; the task's bar is targeted native connection-selection verification and a PR. No merge, enqueue, publish or deploy was performed.

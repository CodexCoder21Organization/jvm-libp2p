# F71e plan and findings
Started 2026-10-24 21:07 UTC; cutoff 22:42 UTC.
- [done] Item 1: public write termination reproducer, red, owner-loop fix, green, reversal, restore, push.
- [in progress] Item 2: completed parent shutdown reproducer, red, immutable-result fix, green, reversal, restore, push.
- [pending] Item 3: all 26 register classes, one class per command, first attempts; formatter; description; cleanup.
OBSERVED: Exact required head cf209084f3c8ea8f3a66968d21506db029e08737 verified OPEN; develop base fetched and rebase is a no-op. No subagents.
OBSERVED: Read prior round final and both fresh review findings, README, FORK and CLAUDE. Testing and philosophy guidance read from prior fetched documents; Gradle-specific brief supersedes generic build runner/main instructions.
INFER: Item 1 mechanism: raw promises are failed after their listener executor terminates, so public converted results lose notification. Reproducer: real local mux plus Stream API write, blocked application close, owner shutdown.
INFER: Item 2 mechanism: shutdown close runs before retained completed parent delivery and overwrites its result at the child boundary. Reproducer: completed parent success, queue rejected delivery and shutdown before owner release.

| Resource | Creator / owner / close | Terminal and caller observations | Reentry / close in-flight / restart | Test |
|---|---|---|---|---|
| Accepted payload and child promise | Caller transfers payload; child loop owns buffer | Release once, settle public result before loop stops | Listener may reenter close; stop new writes before callbacks; no persistence | ReviewTerminalWriteConversionTest |
| Parent completed result | Parent executor creates outcome; child loop applies | Success/failure/cancel immutable | Retained delivery rejected during shutdown still reports outcome; stale delivery inert | ReviewCompletedShutdownTest |
| Child completion callback | Child owner executes | Never arbitrary-thread buffer mutation | Queue hold keeps child pending; later loop applies exactly once | ReviewChildLoopAffinityTest |

OBSERVED: 2026-10-24T21:16:02.785530+00:00: Private Gradle home populated from dependency/wrapper caches only, no build outputs copied. One bounded test command is active, compiling main Kotlin. Three new probe classes prepared; no pre-existing test edited.
INFER: The prior TESTS line names 19 classes; its actual register includes the two buffering classes (21 total). Adding three review probes yields24 unique classes, so two adjacent Noise classes are proposed to meet26. Optional user steering requested; no existing class omitted.

OBSERVED: 2026-10-24T21:23:49.082185+00:00: Main and test-fixture compilation completed; test Kotlin compilation is active. No runtime failure or success yet. Original production remains unchanged while the initial reproducer compiles.
OBSERVED: Register plan contains every21 class from the actual prior register, all three adopted review-shaped probe classes, and NoiseAlgorithmReadinessTest plus NoiseHandshakeOffEventLoopTest,26 unique classes total. No answer to optional steering; proceeding with these two adjacent guards.

OBSERVED: 2026-10-24T21:28:22.261545+00:00: Item1 red at test commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ccf483e5d92801fa41f7aedbc3a3c03e514fc379:1 test,1 failure,0 errors,7543ms XML; total command1032976ms including cold compilation. Exact assertion: "The public stream write must settle after loop termination". rawDone=true, publicPayloadRefCnt=0. Full child and JUnit stack: investigation/F71e/item1-red/full-failure.txt. Production is byte-for-byte starting head.
OBSERVED: Item2 test commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/f1d21376 was created; its three outcome cases are now running on unchanged production.

OBSERVED: 2026-10-24T21:35:39.594291+00:00: Before the Item2 test body ran, its new probe was expanded to six cases: success/failure/cancel at shutdown and the same outcomes at ordinary close. This covers the normal close path that may run before shutdown hooks. All production source remains unchanged from the required head. Existing assertions and bounds are unchanged; additions only.

OBSERVED: 2026-10-24T21:41:53.697897+00:00: Item2 initial red executed3 shutdown cases, all3 failed,0 errors,15578ms XML. Exact success assertion: "The original successful parent result must reach the child"; failure assertion: "The exact original parent failure must reach the child"; cancellation assertion: "Parent cancellation must retain its exact cancellation cause". Full stacks preserved at item2-red/full-failure.txt.
OBSERVED: Initial Item2 Java task reused its original three-case compiled class, despite the six-case source being prepared during Kotlin compilation. The original test commit f1d21376 is retained in branch history; the six-case expansion is a separate commit14f703e7. Source correction is preserved in item2-initial-source-correction.json. A separate expanded red run is required before fixing Item2.
OBSERVED: Item1 owner-loop implementation committed at https://github.com/CodexCoder21Organization/jvm-libp2p/commit/ba9c3b9c40c3cc5388e2dcc2c26bcef7df7a2261. Formatter apply succeeded, scoped to MuxChannel plus six new Java test/probe files. Green runtime is compiling the changed source. No existing-at-start test edited.

OBSERVED: 2026-10-24T21:44:11.075298+00:00: Stopped Item1 first green build during compilation (exit130, no test XML or test body executed). The in-process compiler selection disables incremental compilation in Kotlin1.6; official table read: https://kotlinlang.org/docs/whatsnew1620.html#compiler-execution-strategy . This was an avoidable local invocation choice, not a production/test failure.
OBSERVED: Private Gradle daemon stopped before changing strategy. Subsequent commands use the repository default Kotlin daemon with the existing3GiB compiler heap, two reported compiler processors, and a session marker for cleanup. No test JVM options, test bounds, iteration counts or assertions changed. New Item1 green command is item1-green-daemon.

OBSERVED: 2026-10-24T21:48:04.921706+00:00: PR remains OPEN at required remote head, develop base. Item1 default-daemon green run is compiling main Kotlin; marked owned compiler PID3215758, incremental cache now exists. Source/test inputs remain stable. No subagents, no user hold, one Gradle command maximum. About54 minutes remain before the fixed cutoff.

OBSERVED: 2026-10-24T21:58:20.566766+00:00: Default-daemon green run completed main/fixture compilation and is compiling tests. No source/test inputs have changed during this run. Item1 review pass confirmed no remaining post-termination write settlement callback, cancellation before result publication, and owner-side closed-write state before notification. Runtime proof remains pending.
INFER: Review of the new ordinary-close extension found its later-write template branch must expect the existing closed-channel failure after explicit closure. A narrow correction script is prepared outside the repo and will be applied after this stable green run; the expanded cases have not run yet. Original at-start tests and all bounds remain untouched.

OBSERVED: 2026-10-24T22:01:28.095543+00:00: Item1 green at ba9c3b9c:1/1 pass,0 failures/errors,4000ms XML; owner-thread callback and exact identical termination cause passed. Reversal restored the original MuxChannel source and failed1/1 at "The public stream write must settle after loop termination",9406ms XML. Full reversal trace and exact source patch preserved. Fixed source restored byte-for-byte to ba9c3b9c (Git blob equality asserted), without an unnecessary repeat run; final register will verify it again.
OBSERVED: Expanded ordinary-close fixture now expects the existing full closed-channel failure for later writes and checks the child is closed. This corrects only a new Round4 case before its first execution. Third adopted review-shaped ownership class is committed. All at-start tests are unchanged.

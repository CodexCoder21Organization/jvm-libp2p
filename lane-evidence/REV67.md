# REV67 review findings

Request: read-only review of https://github.com/CodexCoder21Organization/jvm-libp2p/pull/67, expected head 13bea7dd. No PR-branch pushes, merge, enqueue, publication, deploy, restart, or buildtest mutations.

## Plan
- [in progress] Verify head, read repository instructions, linked guidance and author evidence; inspect diff.
- [pending] Review lifecycle invariants, concurrency, relay behavior, and binary compatibility with javap and a compiled old implementer.
- [pending] Run new tests three first-attempt times with fix, then three with retry logic removed in a throwaway evidence branch.
- [pending] Spot-check reported baseline failures on unchanged develop.
- [pending] Rank findings, push all evidence to wip/REV67-* and audit background process cleanup.

## Progress
2026-10-23T00:39:34.705650+00:00 OBSERVED: started review. Fresh clone and required documentation retrieval underway. No production or PR changes.

2026-10-23T00:40:29.217623+00:00 OBSERVED: fresh checkout verifies PR head 13bea7dd18571049b0f6f05702b5071f4477b064, base develop e15e0f1947842be7ef6836c116c14ba47cc8eea1; draft/open/unmerged. No AGENTS.md in checkout. README, FORK.md, CLAUDE.md and author JL61.md read. This is a Gradle repository with no scripts/build.bash or scripts/test.bash; use documented ./gradlew :libp2p:test locally, without changing timeouts/iterations/JVM flags. Initial cgroup current 9,666,289,664 bytes; max 10,737,418,240; oom_kill=1. JVM default compiler flag is -Xjvm-default=all at build.gradle.kts:130; bytecode confirmation pending.

Required sources retrieved through GitHub contents API: architecture/TESTING.md and PHILOSOPHY.md. Read core test/public-API guidance, Extreme-Condition Testing, Never Calibrate a Reproducer to a Machine, and Kompile file-layout section (the latter is inapplicable to this Gradle/JUnit repository). Test injection uses real socket connections with public Netty handler controls; no new mocks.

2026-10-23T00:43:27.213248+00:00 OBSERVED: fixed run 1 completed BUILD SUCCESSFUL in 2m12s; new Netty suite 10/10 and real relay suite 1/1 pass, no XML retries/failures/skips. oom_kill remains 1. javap confirms public default CompletableFuture<kotlin.Unit> retryClose(), whose body invokes close(); no abstract addition. Relay ConnectionOverStream has no retryClose override and inherits it. Production implementations enumerated: ConnectionOverNetty and RelayTransport.ConnectionOverStream; fixture ConnectionStub also inherits default. No ordinary shutdown call sites changed.
INFER: the CAS loop cannot replace a pending or successful private attempt, and ordinary callers always get the original. Each replacement is published before channel.close can re-enter. No application lock is acquired by the new helper; all listeners belong to individual Netty futures. State retains only original/latest, not an attempt history. Further adversarial coverage checks pending.

2026-10-23T00:44:29.311276+00:00 OBSERVED: fixed run 2 passed 11/11 first-attempt tests (BUILD SUCCESSFUL 19s); fixed run 3 is executing with cleanTest to prevent Gradle up-to-date reuse. Each XML is copied before the next run. No test-retry plugin is configured in this Gradle build. Read the linked upstream issue and downstream upgrade PR body; they explicitly preserve ordinary failed-close ownership. Testing guidance states kotlin.build(remote) is required; this lane explicitly calls it informational, so the lane instruction controls. FORK.md calls baseline errors environmental; the reviewer treats them as actual baseline defects whose presence must be recorded rather than waived.

## Lifecycle review table

| Resource / state | Owner and terminal state | Concurrent/re-entry/caller behavior | Evidence / remaining probe |
| --- | --- | --- | --- |
| First Netty close | Channel attribute owns original future; settled result is permanent for ordinary calls | setIfAbsent publishes before handlers; ordinary connection and transport callers observe a derived future | ParentCloseOwnershipTest; explicitRetryClosesRealConnectionAfterFailureAndKeepsOrdinaryResult |
| Current retry | AtomicReference owns latest; success is terminal, failure permits another explicit claim | compareAndSet publishes winner before handlers; all pending callers observe winner | 8192 retry observations; first-dispatch and failure/physical-handler re-entry cases |
| Caller observation | One derived future per caller | cancellation/complete/exception cannot alter private owner | concurrentRetriesShareHeldAttemptAndIsolateCallerFutureChanges |
| Channel inactive | Netty closes physical socket and notifies close future | closed-before-retry path schedules completion on Netty listener; original failure retained | retryAfterPeerClosedFollowingFailureDoesNotRequestAnotherPhysicalClose; overlap coverage reviewed separately |
| Retry history/listeners | Channel owns two future references at most; Netty owns per-attempt promise listeners until settled | no historical list, no new lock, no retained listener on completed attempts in helper | 1000 failures + successful recovery; source inspection |
| Relay | Existing stream close owns resource; shared relay parent remains live | default retryClose calls stream close, retains existing policy; class need not override new method | real 3-host RelayConnectionRetryTest; javap default body |
| Persistence/restart | No persisted record or restart state in this helper | channel state ends with that channel | Not applicable; no persistence introduced |

2026-10-23T00:44:47.367453+00:00 OBSERVED: all three fixed runs passed 11/11 tests on first attempt, no failures/errors/skips, Gradle 2m12s/19s/15s. This includes initial-close success and inherited relay behavior controls. javap output preserved.

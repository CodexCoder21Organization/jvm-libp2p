# Snapshot-41 backport starts from a source revision with pre-existing validation failures

What was attempted: FLIBP5 requires an upstream shutdown backport starting from the exact source of published snapshot-41 (25efb1996bd79e59e6eef28d2d44d9a0405fdd78), rather than current develop, and forbids existing-main test edits.

Observed: a Gradle command containing the two selected shutdown test classes, spotlessCheck and detekt exited 1 before tests ran. Detekt reports MuxChannel.kt:401:14 SwallowedException; NoiseMatcherReadinessTest.kt:72:47 ThrowingExceptionFromFinally; NestedConnectionCloseNotificationTest.kt:103:38 ThrowingExceptionFromFinally. All three source files are unchanged from the required source base. Full command output is in upstream-green-final.log. Earlier selected test execution had reached all shutdown behavior assertions, with only new fixture cleanup errors subsequently corrected; that run is not reported as final green.

Observed: push-triggered build Actions runs https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40860140804 and https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40859584416 have startup_failure conclusions. This matches the source revision's existing FORK.md warning about the native workflow; these runs provide no executed-test verdict.

Impact: full upstream validation cannot be called green from these records. The exact release backport is separately verified with its targeted public-API tests and adjacent shutdown tests. No existing test is edited or skipped, no check is rerequested, and no unrelated tip is used for artifact source.

Next owner action: inspect the existing native workflow startup annotation and qualify an exact-source native module run through an allowed workflow; address the three unchanged Detekt findings separately with their relevant tests. This file records the boundary; it does not claim those failures fixed.

The report-challenge skill was read. Its CLI automatically enqueues and merges a PlanRepository PR; the task forbids both operations, so this record is kept on the authorized evidence branch and that CLI is not invoked.

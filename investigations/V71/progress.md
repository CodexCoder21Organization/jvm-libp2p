# V71 findings
Started 2026-10-26 17:31 UTC.

Plan:
- IN PROGRESS: Read prior findings and repository guidance; verify the exact PR head and complete-suite entry point.
- TODO: Run the single complete native Gradle suite through the saved remote bridge; record native counts and first-attempt outcomes. Compare any test errors with the base.
- TODO: Complete a fresh-context correctness review of result delivery, object retention, and ordering. Add a public-API test before any product correction.
- TODO: Save verification evidence on a V71 branch, update the existing PR description, and finish without merge, queue entry, publication, deployment, or CI watching.

OBSERVED: The existing PR is OPEN at 587aad5f1c7fda72a561f0f7bb09dfd8f63b8939, with base develop. There is no main branch. Fetch and rebase on origin/develop retained the exact requested head.
OBSERVED: F71 recorded 31 focused checks and ten first-attempt consumer passes; no native complete suite executed because remote allocation ended earlier. The complete-suite rule at the end of this brief requires remote execution and overrides the earlier local request. The repository uses Gradle and has no kompile suite script at its product head.
The mechanism is that a terminal Netty result can remain undelivered when its listener cannot run, and the evidence that demonstrates this is the F71 reversal control (10/20 red with base conversion) and its 31 focused passing checks at the requested head. V71 will independently review the completion paths and run the complete native suite.

Read: prior F71 findings; repository README.md; DocumentationRepository architecture/TESTING.md, including the two required sections; PHILOSOPHY.md; github-repos and status-report skill instructions.

OBSERVED 17:35 UTC: Single remote complete-suite client started (tracked session 49966). The source archive is pinned to 587aad5f1c7fda72a561f0f7bb09dfd8f63b8939; SHA256 d61257aa6728fc3aed012ce14b7ae7f1bf56d521b591246121a3bd4d2f7d69ad. The saved F71 bridge selected only :libp2p:test and checked status before packaging results. V71 verification tooling now selects root test (all native modules), gathers every module JUnit XML, and retains native status in the artifact. Product source is unchanged. No retry plugin configuration was found in native Gradle files.
INFER: Native test XML will represent single attempts because no native retry configuration is present; the kompile wrapper result alone is not a native-suite count.
Plan: Entry-point verification DONE; complete suite IN PROGRESS; independent completion-path review IN PROGRESS.

OBSERVED 17:39 UTC: Remote client reached service health OK, then recorded ConnectionClosedEffect during service access. The tracked client remains active. No native Gradle task or JUnit result has been reported. No second complete-suite submission has been made. Verification setup checkpoint: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/V71-suite-review .

OBSERVED 17:45 UTC: Fresh-context review found no demonstrated issue in completed-result conversion. README.md:218-223 explicitly allows direct terminal conversion and normal executor delivery for pending operations. CompletedNettyFutureConversionTest and NettyFutureConversionStateTest cover terminal success/error/cancel, the registration window, pending delivery, and caller cancellation independence. The direct terminal path attaches no listener; the registration window retains the same listener ownership as the base.
OBSERVED: A combined callback-handoff/full-queue hypothesis was checked against pinned Netty 4.2.10.Final (versions.gradle:34). DefaultPromise adds listeners under its monitor; its active notifyListenersNow loop reads additions before finishing. A scheduling request that does not succeed does not remove those additions. Thus the existing active physical-close notification still delivers the new listener after its current callback returns. The initial hypothesis is refuted for the public callback path. Official source reference: https://netty.io/4.2/xref/io/netty/util/concurrent/DefaultPromise.html .
OBSERVED: closeOperation is byte-for-byte the same as origin/develop. requestPhysicalClose differs only in direct terminal delivery outside an active observed callback; base always registered a listener for an already-closed channel. A concurrent initial lazy read was considered, but no public-path wrong result or regression was demonstrated. No product correction is currently supported by the evidence.
OBSERVED: The remote client remains in PROVISIONING, with no native task/result. Suite waiting continues on the single submission.

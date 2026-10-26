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

OBSERVED 17:54 UTC: Single remote complete-suite client started (tracked session 49966). The source archive is pinned to 587aad5f1c7fda72a561f0f7bb09dfd8f63b8939; SHA256 d61257aa6728fc3aed012ce14b7ae7f1bf56d521b591246121a3bd4d2f7d69ad. The saved F71 bridge selected only :libp2p:test and checked status before packaging results. V71 verification tooling now selects root test (all native modules), gathers every module JUnit XML, and retains native status in the artifact. Product source is unchanged. No retry plugin configuration was found in native Gradle files.
INFER: Native test XML will represent single attempts because no native retry configuration is present; the kompile wrapper result alone is not a native-suite count.
Plan: Entry-point verification DONE; complete suite IN PROGRESS; independent completion-path review IN PROGRESS.

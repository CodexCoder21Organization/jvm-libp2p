# FLIBP8 plan
1. IN PROGRESS: Read FLIBP7 findings/resume, project instructions and required architecture documents; verify branch/main overlap, exact upstream head and Actions attempts.
2. TODO: Prove next snapshot unused, build exact upstream head, publish exactly one version through Maven publisher, compare downloaded jar entries and POM, record hashes.
3. TODO: Apply preserved consumer candidate on rebased PR branch, confirm resolved dependency, reproduce both error completion paths red-first and classify two cleanup failures.
4. TODO: Run single-attempt 30x probe plus 17x30 other scenarios using at most two JVM slots; review all touched files/tests.
5. TODO: Push consumer product branch and evidence branches with pre-push identity/secret scans and verified remote SHAs; update description and read Actions attempt XML; classify old retries.
6. TODO: Report within about 90 minutes, include exact results and unfinished steps, verify tracked processes and slots cleaned up.

Evidence branches: wip/FLIBP8-evidence-20261104 in both repositories. Never merge/enqueue/close/deploy or run handoff-cli. Upstream branch immutable unless Actions XML identifies a failure to fix. Never change a main test.

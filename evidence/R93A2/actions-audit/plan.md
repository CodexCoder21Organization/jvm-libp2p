# Actions audit plan

- [x] Read repo identity/context and inspect workflow + Gradle retry settings.
- [x] Retrieve run and artifact metadata for the exact head.
- [x] Download all available native test-result XML, hash bundles, and count first attempts, skips, failures, and retry markers by full case name.
- [x] Write OBSERVED/INFER report with exact sources, hashes, touched-test counts, and historical connection-setup finding.

No repository files were changed. All analysis artifacts are in this directory outside the repository.

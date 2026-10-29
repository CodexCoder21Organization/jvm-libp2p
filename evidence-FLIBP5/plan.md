# FLIBP5 plan
- [in progress] Read required documentation and evidence; identify snapshot-41 source and current consumer overlap.
- [pending] Write public-API upstream failing tests; record assertion failure and push evidence.
- [pending] Fix all confirmed resource cleanup gaps test-first; run module tests and independent reviews.
- [pending] Open upstream PR; prove next version unused; publish exact head and verify jar hashes.
- [pending] Bump consumer; verify classpath; run 18 scenarios 30x single-attempt; push and inspect Actions XML and prior suite retries.
- [pending] Verify all remote SHAs, scan evidence, and confirm process cleanup.

Specific instruction precedence: upstream must branch from artifact source commit rather than rebase onto unrelated tip; one artifact publication explicitly authorized by task overrides generic no-publish campaign text. No merges, queues, closes, deploys or handoff-cli. M1–M11 read; M8 disables remote submissions; M10 gives eight local JVM slots; M11 limits GitHub polling to five minutes.

# F93f plan
- [in progress] Read remote F93d/F93e evidence, review, repository instructions, and required documents; verify current develop and PR state.
- [pending] Apply candidate, run all three candidate tests red with product changes removed, restore and run green; push product and evidence.
- [pending] Close late-child cleanup with deterministic public red/green/revert-red evidence; push.
- [pending] Close concurrent cycle soak coverage and prove guard detects an injected leak; push.
- [pending] Resolve third open gap from F93d with red/green/revert-red evidence; push.
- [pending] Review changed files/tests and run every changed/added test 30 single attempts using two JVM slots.
- [pending] Publish unused snapshot-46 from exact tested head and compare downloaded jar/POM SHA-256 with local build.
- [pending] Run 1,770-attempt repetition gate and inspect Actions per-attempt XML on each pushed head.
- [pending] Push final evidence/resume, verify remote SHAs, clean tracked processes, report exact counts within 90 minutes.

No merge, enqueue, close, deploy, remote test submission, handoff-cli, reflection, retries, test weakening, or JVM tuning flags. Evidence branch wip/F93f-evidence-20261105; product pushes only from the product checkout.

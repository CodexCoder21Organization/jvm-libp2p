# jvm-libp2p Actions runs end before starting jobs

OBSERVED: while implementing explicit connection close retry, the existing develop head e15e0f1947842be7ef6836c116c14ba47cc8eea1 had build and publish workflow runs ending with conclusion startup_failure. Build run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39757570745 and publish run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/39757568930 were created 2026-10-22 09:43 UTC, before this lane started. The build run jobs endpoint returned {"total_count":0,"jobs":[]}. The project FORK.md already describes Actions startup failures.

INFER: the build did not reach repository compilation or tests. The exact GitHub/account setting causing startup_failure is not established by these API responses; do not label a specific billing or policy setting without evidence. The PR's own run still needs inspection.

Impact: required Actions verification may not be obtainable even with local tests passing. Workaround: retain complete local verification and report the required checks honestly. Suggested durable fix: repository/organization Actions owner should inspect the workflow startup diagnostic and restore job dispatch.

The report-challenge skill's CLI creates, enqueues, and merges a central record automatically. This lane explicitly prohibits enqueueing and merging, so this record is preserved on the separate evidence branch instead of invoking that CLI.

## Additional evidence: Actions policy conflict

OBSERVED on 2026-10-22 around 22:24 UTC:
- Repository GET /repos/CodexCoder21Organization/jvm-libp2p/actions/permissions returned {"enabled":true,"allowed_actions":"local_only","sha_pinning_required":false}.
- Organization policy returned {"enabled_repositories":"all","allowed_actions":"selected",...}; selected-actions returned {"github_owned_allowed":true,"patterns_allowed":[],"verified_allowed":true}.
- Build workflow .github/workflows/build.yml uses actions/checkout@v3, actions/setup-java@v3, gradle/gradle-build-action@v2, and android-actions/setup-android@v3, all outside this organization.
- Existing startup_failure check suite 107747774815 had zero check runs as well as zero workflow jobs.

INFER: the repository's local_only policy prevents these external Actions from being used, a sufficient explanation for the workflow not launching jobs. GitHub's documented behavior explicitly includes blocking actions/checkout when only organization Actions are allowed: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository . No workflow-run annotation is exposed to confirm any additional startup reason. The Actions owner should decide whether to permit these workflow Actions or replace them with allowed organization/local steps. No policy was changed by this lane.

An attempted read of the legacy organization billing/actions endpoint returned HTTP410, "This endpoint has been moved." No billing conclusion is drawn or needed for this policy diagnosis.

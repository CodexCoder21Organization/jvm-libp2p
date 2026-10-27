# Noise PR Java 11 hosted verification

## Plan

- [x] Read the `kotlin-ci-setup` and `github-repos` skill instructions.
- [x] Fresh clone to the requested path; read `README.md`, `FORK.md`, and `CLAUDE.md`; no repository `AGENTS.md` exists in the checkout or `/code/ws/W2` parent.
- [x] Verify PR state/head and current default `develop`: PR is OPEN at `0737c7820c2159f2360ff7bc18b260de37544c7a`; `origin/develop` is `25efb1996bd79e59e6eef28d2d44d9a0405fdd78`.
- [x] Rebase the existing evidence source branch on fetched `origin/develop` (already based on the fetched head) and create `verify/w2-noise-ci11-20261027`.
- [x] Add JDK 11 after Android SDK preparation in the existing Linux build job; add a branch-push-only 20-minute targeted verification workflow with the exact Gradle tests/static checks and `if: always()` XML upload.
- [x] Validate workflow and diff; commit/push only the evidence branch; verify its remote head.
- [x] Read the resulting workflow run once and record its URL/status; do not wait or monitor.

## Current evidence

- README.md, FORK.md, and CLAUDE.md read. No AGENTS.md found in this checkout or its `/code/ws/W2` parent.
- [PR 77](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/77) was OPEN at head `0737c7820c2159f2360ff7bc18b260de37544c7a`, based on `develop`.
- Fetched `origin/develop` is `25efb1996bd79e59e6eef28d2d44d9a0405fdd78`; fetched `origin/fix/hq100-noise-fixture` is the PR head above. Rebase reported up to date.
- Workflow changes are limited to `.github/workflows/build.yml` and new `.github/workflows/noise-java11-proof.yml`.
- Validated both workflow files with official `rhysd/actionlint` v1.7.12 (release: https://github.com/rhysd/actionlint/releases/tag/v1.7.12; official checksum manifest matched the downloaded linux_amd64 archive). Staged `git diff --check` passed for both files.
- No local Gradle tasks or JVM were run.

No tests or JVM builds will run locally. No PR will be merged, approved, queued, or otherwise changed.

## Completed evidence

- The existing Linux `bld-build` job still uses Java 17 to prepare Android SDK, then switches to Temurin 11 before Kubo and the existing Gradle build. The Windows job and all existing build tasks/time limits are unchanged.
- The new `.github/workflows/noise-java11-proof.yml` triggers only on pushes to `verify/w2-noise-ci11-20261027`, prepares Android SDK under Java 17, switches to Temurin 11, runs the requested targeted Gradle tests plus `:libp2p:spotlessCheck` and `:libp2p:detekt` with `--no-daemon --max-workers=1`, uses a 20-minute job timeout, and uploads `**/build/test-results/**/*.xml` under `if: always()`.
- Official actionlint v1.7.12 passed on both workflow files. Its Linux archive matched the official release checksums file. `git diff --cached --check` passed. No Gradle/JVM command was run locally.
- Commit: `39153badb719e6fb1db940a9b1bfeb9e958454c1` ? https://github.com/CodexCoder21Organization/jvm-libp2p/commit/39153badb719e6fb1db940a9b1bfeb9e958454c1
- Evidence branch: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/verify/w2-noise-ci11-20261027
- Remote `refs/heads/verify/w2-noise-ci11-20261027` was verified at the commit above. PR77 was OPEN at the expected original head immediately before push; no PR branch or description was changed.
- First valid run URL read returned run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40580780370 with status `in_progress` at read time and the pushed commit as `headSha`. No further CI status reads or waits were made. An initial query filtered by workflow filename returned HTTP 404; the single branch-scoped run listing returned the run above.

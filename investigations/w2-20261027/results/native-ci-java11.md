# JDK 11 CI correction for jvm-libp2p PR 75

## Change

In the fresh clone `/code/ws/W2/workspace/jvm-libp2p-w2-ci11-201254`, added one `actions/setup-java@v4` step for Temurin 11 to the Linux `bld-build` job, immediately after Android SDK package installation and before Kubo/Gradle. Android SDK setup still runs under Temurin 17; the full existing Gradle task command then runs under Java 11. Windows stays on Java 17. No build tasks, timeouts, tests, assertions, or other workflow jobs changed.

The project documents JDK 11+ support and targets Java 11. Preparing the Android SDK first preserves its JDK 17 tool requirement while testing Gradle on the documented minimum runtime.

## Validation and push

- Confirmed PR [https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/75) was open at head `bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672`; the origin branch matched.
- Fetched `origin/develop` at `25efb1996bd79e59e6eef28d2d44d9a0405fdd78` immediately before validation. Rebase reported `Current branch ... is up to date`.
- Validated `.github/workflows/build.yml` with official actionlint 1.7.12, downloaded from `rhysd/actionlint`'s GitHub release. Its published SHA-256 check passed; actionlint exited 0 with no diagnostics.
- `git diff --check` passed. The only changed file is `.github/workflows/build.yml` (five inserted lines).
- Committed as [`a4b0b707772b349aaeec12f18b9a46cbac1a6f03`](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/a4b0b707772b349aaeec12f18b9a46cbac1a6f03) and pushed with a normal fast-forward push after confirming the PR remained open and the remote head was still `bcedeb723c6a7a7ef9b2122a9efbcf4df22bd672`.
- Verified the remote branch now points to `a4b0b707772b349aaeec12f18b9a46cbac1a6f03`; PR 75 remains open at that head.
- No tests, Gradle build, CI rerun, CI watch, PR body edit, merge, approval, or queue action was performed.

## State

The fresh working tree is clean and tracks `origin/fix/hq100-native-gradle-ci`. The earlier independent review used Git object reads and made no source changes. Its prior statement that the shared checkout remained on `develop` was inaccurate; `/tmp/W2-native-ci-review.md` has been corrected.

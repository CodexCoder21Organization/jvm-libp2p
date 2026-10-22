# FURR3 verification

Code head: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/e7153395f4a1a05860906366cc46e48ce8fc6422

| Check | Result |
| --- | --- |
| Initial corrected fail-first run | 15 cases, 9 red, 6 green |
| Expanded targeted controls | 153/153 first attempt, including 24 new cases |
| Reversal of earlier 23-case set | 14 red, 9 green |
| Restored new cases | 24/24 first attempt |
| Formatting and static analysis | Pass |
| One complete Gradle suite | 1211 total: 1189 passed, 20 red, 2 skipped |
| Targeted unchanged baseline | 29 total: 8 passed, 20 red, 1 skipped |
| Full/baseline red case identity | All 20 match; none unique to either run |
| New cases in full suite | 24 passed, zero skipped |

The baseline is e15e0f1947842be7ef6836c116c14ba47cc8eea1. It reproduces the complete suite's port-binding and native TLS setup errors, also described in FORK.md. No second complete suite ran. No existing test, timeout or iteration count changed. Gradle/JUnit has no configured test retry; these counts are single executions rather than later successful attempts.

Stream.class and both existing mux binding classes match the stored baseline hashes byte-for-byte. The new binding uses the existing Yamux codec and accounting. The new observer acts only when a capacity cause is recorded; ordinary-close handling remains on its prior path.

The PR remains draft because the repository's complete suite is not clean. No Maven publication, merge, queue, deployment or CI monitoring occurred.

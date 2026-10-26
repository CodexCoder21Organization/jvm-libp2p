# Native base controls

Base commit: `395e0c72b69a8b3429e979586e06fe9c84507cc7` on `develop`.

The controls run from a fresh, submodule-enabled checkout at `jvm-libp2p-base`. Each row records one selected class or case; no complete suite is run here.

| Issue ID | Selector kind | XML counts | Matching case |
|---|---|---|---|
| 70588824648f2c1b646614ed87cb3fce5ca4d91d49fcf3f9f306405e72db5605 | class | total=0, pass=0, failure=0, error=0, skipped=0, gradle_exit=1 | missing (matches=0) |
| 70588824648f2c1b646614ed87cb3fce5ca4d91d49fcf3f9f306405e72db5605 | class-suffix correction | total=28, pass=28, failure=0, error=0, skipped=0, gradle_exit=0 | pass (matches=1) |
| bb6e2305286c5e9167b56fc3913167eaced3ff05dad0aaf85d1a6e32e206d011 | class-suffix | total=1, pass=1, failure=0, error=0, skipped=0, gradle_exit=0 | pass (matches=1) |
| f2139c59c684a3d408c31768cfe7200e1d7b9adea07fb884785e34055710cbf6 | class-suffix | total=24, pass=24, failure=0, error=0, skipped=0, gradle_exit=0 | pass (matches=1) |
| a8db501a097be9d38fcfe4a118fae92a828f825a373ef6ac24b3568db43bd158 | method-suffix | total=1, pass=0, failure=1, error=0, skipped=0, gradle_exit=1 | failure (matches=1) |
| 67befb2b8f991b68c6afaccc86fe9384b0428b1daded4d32515103130ee0fb3b | repeated-class-suffix | total=20, pass=18, failure=2, error=0, skipped=0, gradle_exit=1 | pass (matches=1) |


## Targeted controls on the primary checkout
The checkout was rebased onto `origin/develop` at `395e0c72b69a8b3429e979586e06fe9c84507cc7`. The tracked source diff from PR head `587aad5f1c7fda72a561f0f7bb09dfd8f63b8939` remained empty.

| Issue ID | XML total | Passed | Failed | Errors | Skipped | Matching case |
|---|---:|---:|---:|---:|---:|---|
| `70588824648f2c1b646614ed87cb3fce5ca4d91d49fcf3f9f306405e72db5605` | 28 | 28 | 0 | 0 | 0 | passed (1 matched) |
| `bb6e2305286c5e9167b56fc3913167eaced3ff05dad0aaf85d1a6e32e206d011` | 1 | 1 | 0 | 0 | 0 | passed (1 matched) |
| `f2139c59c684a3d408c31768cfe7200e1d7b9adea07fb884785e34055710cbf6` | 24 | 24 | 0 | 0 | 0 | passed (1 matched) |
| `a8db501a097be9d38fcfe4a118fae92a828f825a373ef6ac24b3568db43bd158` | — | — | — | — | — | exact method filter selected no test |

The fully qualified and method-suffix filters selected no test; the verified class-suffix filter selected two cases, including the matching case.

Kubo diagnostic from the exact-base method control: no failure diagnostic was present in the selected case XML.
| `a8db501a097be9d38fcfe4a118fae92a828f825a373ef6ac24b3568db43bd158` | 2 | 1 | 1 | 0 | 0 | failed (1 matching case) |

Kubo diagnostic from the exact-base class-suffix control: `ConnectException` at `QuicKuboTestJava.java:65`.


## Failure diagnostic comparison
- Kubo: native and exact-base matching cases both report `ConnectException` at `QuicKuboTestJava.java:65`, with the same type and source frame.
- Encrypted class: native reports `AssertionFailedError` at `NoiseEstablishedConnectionTest.kt:170`; both saved base failures show the same type and source frame.

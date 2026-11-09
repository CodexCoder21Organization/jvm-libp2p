GitHub release downloads returned HTTP 504 in both native CI runs [42567018398](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42567018398) and [42567012366](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42567012366), blocking verification of https://github.com/CodexCoder21Organization/jvm-libp2p/pull/97 and the candidate coordinator fix at https://github.com/CodexCoder21Organization/UrlResolver/pull/1694. The requester asked for a verified alternate source and bounded retries, following https://github.com/CodexCoder21Organization/BuildTestEmbedded/pull/1800.

The Kubo install now tries the pinned GitHub release URL, then the REST endpoint for the same asset. Both sources must match the existing SHA-256 before extraction. The shared download command allows at most three attempts per source for HTTP 429, 5xx, or no response; other statuses, incomplete HTTP-200 transfers, and wrong digests move to the next source without retrying it. Every failed attempt reports its URL, status and attempt count. Temporary files are removed and an existing destination survives failure.

The [documentation job](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42567018326/job/128995624615) failed for the same download class: both uploaded `gradle.log` files report HTTP 504 downloading `gradle-8.14.3-bin.zip` from GitHub before compilation. CI now prepares that distribution through the same helper, with its verified REST asset as the alternate, then points the wrapper at the local archive. The wrapper SHA-256 stays unchanged. Latest develop [native build](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41845411944) and [documentation](https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41845411779) both passed; this is not a documentation-content failure attributed to the connection change.

Kubo retains its 30-second connect and 180-second total transfer allowance per attempt. Gradle preparation uses 10-second connect and total transfer bounds from its existing network allowance. Neither workflow job timeout changes. Actual alternate downloads returned HTTP 200 and matched the existing pins: Kubo `42045802fe60c64fb01350bc071190c534d600fe269759c06e27e22b2012fd3e`; Gradle `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.

First-attempt verification:

- Download command register: FIRST 16/16, including 504 fallback, wrong bytes, early close, stalled headers/body, retry counts, REST binary header, preserved existing archives and simultaneous callers.
- Kubo 504 reproduction: FIRST 0/1 before, 10/10 initial green register, 0/1 after reversing the fix; restored final register 16/16.
- Gradle preparation reproduction: FIRST 0/1 before, 1/1 after, 0/1 after reversal; restored in the final 16/16 register.
- REST header guard control: FIRST 0/1 with only the header removed, 2/2 after restoration including stalled-body coverage.
- Existing native workflow contract checks: FIRST 2/2. Real wrapper smoke successfully launched Gradle 8.14.3 from the verified local archive.
- Independent test and code reviews completed against the full testing, code-review and philosophy documents. No original test file or assertion was edited. No timeout or iteration count was raised or lowered.

The HTTP tests run in the workflows before public downloads. Their XML is uploaded even if a later download fails. Evidence and logs: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/fix-libp2p-kubo-download-evidence/evidence

full register: PR CI run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/42569872962, pending/pending at the single status read. Native jobs and the documentation job are in progress; both kotlin.build checks report success. No complete native register ran locally. This lane leaves further CI review to the orchestrator.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

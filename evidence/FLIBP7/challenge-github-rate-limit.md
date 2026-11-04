# GitHub Actions read returned a rate-limit error with a positive quota counter

OBSERVED: On 2026-11-04 at 21:36:29 UTC, the guarded status read of required Actions run https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/41914319187 failed with HTTP 403: API rate limit exceeded for user ID 216437543. Request ID: D723:28D998:4C5B8A:517DD0:6AEBA55D. Exact tool text is in actions-jobs-2136.stderr.

OBSERVED: The one quota probe required by the brief immediately returned limit=5000, remaining=3780, used=1220, reset=1793831242 (2026-11-04 22:27:22 UTC). These observations disagree; this record does not claim to know why.

OBSERVED: The brief requires waiting until that reset before any further GitHub CLI/API call. Publication is therefore withheld: required Actions XML and its first-attempt count cannot yet be read. Local packaging has passed; 1,025 compiled main classes match the staged JAR, and the staged POM has the exact requested coordinate and source head.

OBSERVED: A published snapshot-43 control is used only to reproduce the independent consumer fatal-listener aggregation finding. It does not count toward snapshot-45 release or repetition gates.

INFER: The CLI/proxy owner should preserve the quota headers and credential provenance of a rejected request and its follow-up quota probe, without recording credential contents, to determine whether the two responses refer to the same quota state. A cached quota response, differing credential selection, or a different quota resource are hypotheses, not established causes.

OBSERVED: The report-challenge skill was read. Its CLI explicitly "enqueues it in the merge queue ... waits until merged". This brief prohibits enqueue and merge, so this record stays in the authorized evidence branch; that CLI was not invoked.

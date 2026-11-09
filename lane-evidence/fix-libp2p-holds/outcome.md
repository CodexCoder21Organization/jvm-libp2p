# Lane outcome

OBSERVED: PARTIAL. Upstream owned holds, its draft PR and snapshot 47 are complete. Consumer qualification remains incomplete at an exact probe-count assertion.

| Brief item | State and evidence |
| --- | --- |
| Upstream public red and accepted-child control | Done: current-source cancelled child FIRST 0/1; accepted-child control FIRST 1/1; baseline-source XML. |
| Hold implementation and red/green/reversal | Done: API baseline FIRST 3/7, green 7/7, reversal 3/7; additional peer/concurrent red 0/2 and own-cleanup red 0/1; final network FIRST 71/71 including all nine new cases. |
| Full local upstream suite | Not performed: hard rule 6 explicitly prohibits a whole-repository local suite. No full-suite count is claimed. All reported upstream CI checks are green. |
| Upstream PR | Done: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/97, draft, head cd637ba77130ddf04e913581ef7e3d3afbab092a. |
| Publish snapshot 47 | Done: community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-47; POM HTTP 200; downloaded jar SHA-256 f84b6743b36a63ad1e8f323a71837d6a61e72d7c6922d946a8ba2b745264252d. Used documented Gradle jar/POM staging and CLI 0.0.6 --artifact-dir because the requested kompile build rule is absent. |
| Consumer code and README pins | Candidate prepared and saved to evidence. Direct snapshot 47; main protocol 0.0.1465-transport32-20261029 compiled remotely, so the unrelated 1467 pin was restored. |
| Consumer qualification | Service FIRST 8/9, each has one attempt. New cancelled-child 1/1; five reservation probes 5/5; ping register 2/3. Native --log XML was not returned by the remote client. |
| Remaining consumer failure | Physical close and later public ping succeeded; the later unchanged assertion expected three responder probes and observed five. Full failure and paginated service records are preserved. |
| Consumer reversal/restoration and product push | Incomplete: failed qualification gate and no-new-work cutoff. PR https://github.com/CodexCoder21Organization/UrlResolver/pull/1694 stays unchanged at a2b9d056483fd559d87187dd7f0f3c6541d2db7c. |
| Checks, process cleanup, evidence | Checks read once on each PR. No merge, approval, ready change, queue, CI retry, deploy or other publication. Owned build scratch removed; final process inventory saved. |

OBSERVED: No pre-existing upstream test was edited. Consumer existing-test edits change dependency pin lines only; test-edits.json lists before/after. New cancelled-child probe uses the hold API. No timeout or iteration was raised/lowered and no existing assertion was weakened.

INFER: Next first action is a native XML reproduction of the consumer's three-versus-five probe count from the saved candidate. Preserve the assertion, find the extra calls, then complete reversal and restored qualification before a product push.

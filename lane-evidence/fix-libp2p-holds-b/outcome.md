# Lane result in progress

OBSERVED: BLOCKED by case (c). Both the published snapshot and matching upstream source start a canceled queued child after publication resumes.

| Brief item | State |
| --- | --- |
| Local unchanged count | Done, FIRST 0/1; expected 3, actual 5, all three native attempts fail identically. |
| Extra-probe origin | Done, UrlResolver.kt:13142 opens one provisional probe per caller on the same held duplicate; kept-parent probes account for the intended three. |
| Cancellation control | Done, public snapshot 47 FIRST 0/1; canceled before publication resumes, expected no responder start, actual one. Three native attempts agree. |
| Upstream source test | Done: pushed at 6f8f41e1333ac91221d01df79ecb39835cbea75e; native FIRST 0/1 at the same expected-0/actual-1 assertion, 2233 ms. |
| Consumer source candidate | Saved at d393401aad8f0758244ab8396abfd3b506e004d8 on wip/fix-libp2p-holds-b-candidate-d393401aad; no source correction. |
| Consumer green, reversal and PR push | Blocked by case (c), per the brief. No result or product push claimed. |
| Remote trace | Service FIRST 0/1, expected 3 actual 5; client reached its command bound after dispatch and returned no native XML. |

OBSERVED: No existing assertion, count, timeout or iteration was edited. No merge, queue, approval, ready change, publication, deployment, service change or handoff was performed.

OBSERVED: Final PR https://github.com/CodexCoder21Organization/UrlResolver/pull/1694 remains OPEN/draft, head a2b9d056483fd559d87187dd7f0f3c6541d2db7c, with its three existing checks reporting FAILURE. Its branch and description were not changed.

INFER: Next: correct upstream queued child cancellation before protocol installation, verify the new native reproducer, publish a newly numbered snapshot in an authorized lane, then resume consumer migration and all original gates. Preserve the expected three probes.

The BuildTest coordinator has repeatedly failed droplet allocation while cached direct child-stream setup fails and later relay calls close. The owner asked to finish https://github.com/CodexCoder21Organization/UrlResolver/pull/1694, including the upstream queued-child cancellation correction that its exact three-probe scenario requires.

This PR adds owned provisional parent holds and makes controller cancellation end queued child creation before its protocol is offered. The initializer observes cancellation and closes a late-created pending child. Accepted siblings remain usable, and ordinary already-published streams retain their earlier controller/protocol outcomes. The source version is snapshot 48.

The cancellation mechanism was reproduced through real TCP/Noise hosts with both Yamux and Mplex, including an accepted sibling on the same parent. The old code offered the cancelled protocol after publication resumed. The owned mux creation result now receives cancellation rather than remaining pending.

First-attempt evidence:
- Original Yamux cancellation scenario: FIRST 0/1; expanded both-muxer/sibling register: FIRST 0/4.
- First candidate: FIRST 4/4. Independent review found an ordinary published-stream regression; two unchanged capacity controls were FIRST 0/2.
- Scoped correction: FIRST 6/6, including those controls.
- Implementation reversal: FIRST 2/6; all four queued-child scenarios fail at their exact responder counts again, while ordinary controls pass.
- Restored required network register FIRST 71/71, new cancellation cases FIRST 4/4, and full capacity controls FIRST 23/23: combined FIRST 98/98.
- spotlessCheck, detekt, jar and Maven POM generation passed from this source head. A whole-repository local suite is prohibited by the lane constraints; no full-suite result is claimed.

Existing-test edits in this lane: the cherry-picked `NetworkProvisionalCancellationBeforePublicationTest.kt` received only spotless import and line layout changes. All assertions, counts, conditions and bounds are identical. The existing capacity and network controls were not edited. Two additional files cover Mplex queued cancellation and accepted siblings with both muxers.

Evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/15c7dd61d3a0af3781ac0439932e611b27cd467d/lane-evidence/fix-libp2p-holds-c

Published once from this head as `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-48`. POM HTTP 200; served jar SHA-256 `fc8ec4faaf8176b734c98055247421800b4ddf545dea6b216ad66c1d53f343d5`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

The BuildTest coordinator has repeatedly failed droplet allocation while cached direct child-stream setup fails and later relay calls close. The owner asked to finish https://github.com/CodexCoder21Organization/UrlResolver/pull/1694, including the upstream queued-child cancellation correction that its exact three-probe scenario requires.

This PR adds owned provisional parent holds and makes controller cancellation end queued child creation before its protocol is offered. The initializer observes cancellation and closes a late-created pending child. Accepted siblings remain usable, and ordinary already-published streams retain their earlier controller/protocol outcomes. The source version is snapshot 48.

The cancellation mechanism was reproduced through real TCP/Noise hosts with both Yamux and Mplex, including an accepted sibling on the same parent. The old code offered the cancelled protocol after publication resumed. The owned mux creation result now receives cancellation rather than remaining pending.

First-attempt evidence:
- Original Yamux cancellation scenario: FIRST 0/1; expanded both-muxer/sibling register: FIRST 0/4.
- First candidate: FIRST 4/4. Independent review found an ordinary published-stream regression; two unchanged capacity controls were FIRST 0/2.
- Scoped correction: FIRST 6/6, including those controls.
- Implementation reversal: FIRST 2/6; all four queued-child scenarios fail at their exact responder counts again, while ordinary controls pass.
- RESTORED_REGISTER
- STATIC_CHECKS

Existing-test edits in this lane: the cherry-picked `NetworkProvisionalCancellationBeforePublicationTest.kt` received only spotless import and line layout changes. All assertions, counts, conditions and bounds are identical. The existing capacity and network controls were not edited. Two additional files cover Mplex queued cancellation and accepted siblings with both muxers.

EVIDENCE_LINK

PUBLICATION

🤖 Generated with [Claude Code](https://claude.com/claude-code)

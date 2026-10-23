# Noise timer evidence

Issue: the silent-response test adapter reports omitted bytes sent, which starts a competing peer timer. Paused-clock controls demonstrate responder-first read expiry becoming an initiator connection-close result.

Classification: pre-existing test fixture issue. The Noise source and original test are identical at base and pinned PR head; both reproduce the same result. New child-stream close causes are not present during the parent Noise handshake.

- Base e15e0f1947842be7ef6836c116c14ba47cc8eea1: 188/200 first-attempt passes, 12 red.
- PR head d281e5251be93d12a8aa643cc87d7833b3bab205: 192/200 first-attempt passes, 8 red.
- Ordered responder-first control: 10/10 passes per revision.
- Fixture regressions: 20/20 red on each original revision.
- Corrected fixture: original TCP path 200/200 and new checks 20/20 pass.
- Reversal: new checks 20/20 red again.
- Full Noise class: 29/29 pass; formatting check passes.

Correction branch: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/laneFNOISE-silent-response
Suite head: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/812a22c71175817223c80fe69f8c8ba446bbae44
The ONE complete suite finished: 1,207 cases, 20 red, 2 skipped, no retry entries. NoiseHandshakeOffEventLoopTest passed all 29 cases. The other red cases are retained without a cause attribution in this lane.

The source files on this evidence branch retain the original fixture for reproduction; green results refer to the separate correction branch above. Raw XML preserves case results, output, and full traces. Guidance read: project README, central TESTING.md TDD and machine-independent reproduction sections, central PHILOSOPHY.md, and the prior R66b findings.

Correction PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/69
Final pre-PR selection: 21/21, same head as the complete suite. No CI watch or landing action.

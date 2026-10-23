# Noise timer comparison

Pinned PR: https://github.com/CodexCoder21Organization/jvm-libp2p/pull/66 at d281e5251be93d12a8aa643cc87d7833b3bab205.
Base: e15e0f1947842be7ef6836c116c14ba47cc8eea1 (repository default branch develop).
Noise source and original test are identical across these revisions.
The matched experiment calls the existing message-2 wait test once per dynamic case, 200 cases, eight concurrent callers. Both revisions use the same init script and JVM environment.
The original omitted-write fixture completes its promise successfully. Noise then arms the responder message-3 timer while the initiator awaits message 2. The responder-first ordering needs direct validation; no attribution conclusion yet.
Required guidance: repository README, central TESTING.md TDD and machine-independent reproduction sections, central PHILOSOPHY.md, prior R66b findings.

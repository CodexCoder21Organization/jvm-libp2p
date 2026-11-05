[UrlProtocol PR](https://github.com/CodexCoder21Organization/UrlProtocol/pull/992) needs host shutdown to complete when a full native event-loop queue rejects close work. Its review found that a failed listener close skipped upstream acceptor-group cleanup; expanded coverage then found a second gap: an outbound QUIC UDP parent remained open and registered after its own event loop terminated. This change closes both shutdown gaps before the next consumer dependency is published.

TCP/WebSocket acceptor cleanup and QUIC worker cleanup run after every channel outcome. QUIC now retains each dial's UDP parent separately from its live child list, explicitly closes those parents after child settlement, and only then terminates its group. A child closed before transport shutdown cannot discard its parent's ownership. The original child failure remains primary, followed by a parent-close failure when there was no child failure; caller mutation cannot stop private cleanup.

The measured failure was a client UDP parent with `open=true`, `registered=true`, `closeFuture.isDone=false`, and a terminated `SingleThreadIoEventLoop` belonging to the client group. A held child-close promise and sibling loop barriers force cleanup installation before the last callback, making the original timing-dependent failure deterministic.

Validation on [e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe):
- Required adjacent register: **56/56**, one native attempt each, zero failures or skips.
- Additional fixed cases: **4/4**, one attempt each (four-dial retest, already-closed child, parent-only failure, child-plus-parent failure).
- Deterministic original product: **0/2** passes on physical-parent assertions.
- Same final tests with the exact baseline product JAR first on the classpath: **0/4**, all intended assertion failures; no test/dependency remainder changed.
- Independent test and source reviews read all touched files and the full Testing, Code Review Red Flags and Engineering Philosophy documents; the parent-failure coverage finding is covered by the final two parameter cases.

Default branch is unchanged develop [436997d5e5f5b80f1503504b19e5b813654a3a49](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/436997d5e5f5b80f1503504b19e5b813654a3a49); it has no equivalent cleanup. **Existing default-branch test edits: none.** The four additional test files are new to this PR.

Published artifact: `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-45`, built from exact source head `e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe`. JAR SHA-256: `a2c9e25b16c69d83bdd813648c447d74bf549ec9a5eaad83cdb51c53a3b19a2c`. [FLIBP8 publication evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/ebcb5f2d737554c506bab6c070ba33a4c6c09b0e/evidence/FLIBP8) records exact-head packaging, one upload and downloaded-byte verification. Any later source correction requires a new snapshot; never overwrite this version.

[FLIBP7 evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/FLIBP7-evidence-20261104/evidence/FLIBP7) preserves measured state, red/green/control XML, source checkpoint, class hashes and independent review. No merge, enqueue, close, deployment or check re-request is part of this work.


R93a review is REQUEST CHANGES; its confirmed findings must be addressed before approval. [R93a evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/R93a-evidence-20261104/evidence/R93a).

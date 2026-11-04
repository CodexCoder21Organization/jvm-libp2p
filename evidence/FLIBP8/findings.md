# FLIBP8 findings

2026-11-04T22:21:36.767633+00:00 OBSERVED: Read upstream FLIBP7 findings and resume in full. OBSERVED: Fresh clone, exact PR source e3e5caf4a0d5cfc1acd40be2ec7468078ce9f8fe rebases without change onto unchanged default develop 436997d5e5f5b80f1503504b19e5b813654a3a49; no origin/main exists. OBSERVED: Default still lacks both shutdown ownership changes. OBSERVED: README and FORK read; no root AGENTS.md exists. OBSERVED: Initial repo discovery calls succeeded; inherited API reset 22:27:22 is being honored before further API reads. OBSERVED: Addendum scan found no headings. INFER: Next checkpoint is exact-head Actions XML after the inherited reset and fresh local package; no publication occurred.

OBSERVED pre-push ["git", "remote", "-v"]: origin	https://github.com/CodexCoder21Organization/jvm-libp2p.git (fetch)
origin	https://github.com/CodexCoder21Organization/jvm-libp2p.git (push)
upstream	https://github.com/libp2p/jvm-libp2p.git (fetch)
upstream	https://github.com/libp2p/jvm-libp2p.git (push)

OBSERVED pre-push ["git", "rev-parse", "--abbrev-ref", "HEAD"]: wip/FLIBP8-evidence-20261104

OBSERVED pre-push ["git", "log", "-1", "--oneline"]: 436997d5 Merge pull request #92 from CodexCoder21Organization/fix/public-stream-reservation

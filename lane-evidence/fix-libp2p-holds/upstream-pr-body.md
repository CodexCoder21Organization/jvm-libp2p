UrlResolver and the BuildTest web UI depend on reliable cached parent selection during bytecode fetches. The requester needs https://github.com/CodexCoder21Organization/UrlResolver/pull/1694 to close a surplus parent after its provisional child is cancelled, while preserving accepted children and ordinary reservations. Snapshot 46 has no owned release operation for that situation.

This adds `Network.holdConnection`, an owned `ConnectionHold`, provisional child creation and promotion to ordinary retention. Released duplicates retire after their admitted children finish. Incoming peer children remain usable during a pending retirement reply; agreed physical close is committed only after the last admitted child finishes. Ordinary `reserveConnection` and stream creation keep their lifetime guarantee. Source version is snapshot 47.

Ownership decision: https://github.com/CodexCoder21Organization/UrlResolver/blob/3480b5175a81d6fba5e042e92db7ebd871eb9dc9/lane-evidence/design-1694/ownership-decision.md

First-attempt evidence:
- Current-source cancelled-child baseline FIRST 0/1; accepted ordinary-child control FIRST 1/1.
- New API retained baseline FIRST 3/7; fixed FIRST 7/7; reversal FIRST 3/7.
- Additional peer/concurrent baseline FIRST 0/2; cancellation without caller cleanup FIRST 0/1.
- Broader register initially FIRST 68/71 exposed three incoming-admission regressions. Those unchanged scenarios became FIRST 7/7 after the correction.
- Final connection-management register FIRST 71/71, including all nine new scenarios. Formatting, static checks, jar and POM generation pass.
- A whole-repository local suite is prohibited by the lane's hard rule 6, so no full-suite result is claimed.

Existing upstream test edits: none. New scenario preparation corrected interface parameter names and formatting; all bounds and assertions remain intact. Separate test and implementation reviews are recorded with the evidence.

Evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/381d177dbba276113fee7d847486cc4e16238a59/lane-evidence/fix-libp2p-holds

Published from this PR head as `community.kotlin.libp2p:jvm-libp2p:1.3.0-codexcoder21-snapshot-47`. POM HTTP 200; downloaded jar SHA-256 `f84b6743b36a63ad1e8f323a71837d6a61e72d7c6922d946a8ba2b745264252d`.

Publication evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/fix-libp2p-holds-evidence/lane-evidence/fix-libp2p-holds

🤖 Generated with [Claude Code](https://claude.com/claude-code)

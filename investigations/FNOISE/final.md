The Noise issue predates [the capacity PR](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/66): the fixture started a competing responder timer.

First attempts: base 188/200, head 192/200; fix 220/220. Reversal: 20/20 red.
Complete suite: 1,207 cases, 20 red in other groups, 2 skipped; Noise 29/29 passed.

[Fix PR](https://github.com/CodexCoder21Organization/jvm-libp2p/pull/69) · [Evidence](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/laneFNOISE-evidence) · [Findings](/tmp/claude-501/-code/79f96be0-ae83-4479-b38c-8894e2722ba8/scratchpad/out/rFNOISE-findings.md)
No PR merged or queued; no publication, deployment, or CI watch.

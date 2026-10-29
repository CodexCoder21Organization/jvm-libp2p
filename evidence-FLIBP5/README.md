# FLIBP5 evidence

Task: fix the snapshot-41 native transport cleanup gap behind UrlProtocol host shutdown, publish exactly one new artifact, qualify consumer https://github.com/CodexCoder21Organization/UrlProtocol/pull/992 without merging or deploying.

Source provenance: snapshot41.pom downloaded from kotlin.directory has SHA-256 144f73fdee0cfe6a2269c758976d6a642e588f4e619e7f602e3438bfb23cf48e; downloaded jar SHA-256 is 2482ff751bd14b929b3e6b9ad6161ce7a645eaff1c1bad7d43423a731edbfe3d. Both match snapshot41-publication-record.md, preserved from https://github.com/CodexCoder21Organization/jvm-libp2p/blob/4af178f6e6bcabf6fa511dd7c79e29c74a400f2d/investigation/PUB71/findings.md . That record names exact source commit https://github.com/CodexCoder21Organization/jvm-libp2p/commit/25efb1996bd79e59e6eef28d2d44d9a0405fdd78 . The POM has no SCM field and the repository has no publication tag; no unrelated tip is substituted.

Required reading completed: full DocumentationRepository PHILOSOPHY.md and architecture/TESTING.md from fresh docs clone; upstream README.md, FORK.md and CLAUDE.md; consumer README setup/dependency and shutdown context; F992b final report and original public acceptor probe. Invariant table, plan, gap audit and incremental findings are separate files here.

Eight JVM slots and five-minute GitHub status polling apply. No handoff-cli, merge, enqueue, close, deploy or check rerun is authorized. Publication is explicitly authorized only for the exact upstream PR head and an unused version; the user's specific permission overrides generic campaign prohibition and skill reconfirmation text.

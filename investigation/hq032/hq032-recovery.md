# Recover the preserved source commits

The evidence branch stores complete Git bundles as well as readable patches. They preserve the isolated original cleanup proposal and later phase corrections as separate commits.

- Protocol head: `cc33cf0df81e5980d9cc80234a393192ecef392e`, prerequisite main `1228066bfd53af6e7661f68e237710ab33176aa8`.
- Resolver head: `0ded77ac77f86ba01db7336f53a9a6fb0946303b`, prerequisite main `b561c483ae160d9b9cf5a56a7ac4c910f3b9cd1c`.

Download the bundle through authenticated `gh api` from the evidence branch, then verify and fetch it from a clone containing the prerequisite main:

```bash
gh api -H 'Accept: application/vnd.github.raw' 'repos/CodexCoder21Organization/jvm-libp2p/contents/investigation/hq032/hq032-protocol-checkpoint.bundle?ref=fix%2Fhq032-rollout-evidence' > protocol.bundle
git bundle verify protocol.bundle
git fetch ./protocol.bundle refs/heads/fix/fl635-rejected-drain-host-stop:refs/heads/fix/hq032-resumed-protocol
```

For the resolver bundle substitute `hq032-resolver-checkpoint.bundle` and `refs/heads/fix/hq023-published-parent-regression`.

The ordinary verification commands use each repository's scripts and the original test selectors. Direct component comparisons isolate the public test bodies from the separately recorded NativeSha256 bootstrap/readiness failure; they are additional component evidence and do not replace the full-suite/CI gate. Their input records carry source hashes and exact jar basenames/digests; dependency caches and compiled jars are intentionally excluded from this branch. Rebuild source artifacts from the preserved commits rather than publishing a cached jar.

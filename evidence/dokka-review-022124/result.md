# Scoped review repair result

```json
{
  "handoff": "hf-2026-10-22-restore-required-verification-for-jvm-libp2p-explicit-close-retry",
  "outcome": "FIXED",
  "prs": [
    "https://github.com/CodexCoder21Organization/jvm-libp2p/pull/79"
  ],
  "heads": [
    "f6717ac782ab521ed39cdca7ab0eb024352b7fd7"
  ],
  "summary": "Budget: 368 MiB root + one 144 MiB worker = 512 MiB. Removed opt-in/finalization; serialization stays (656 MiB reversal).\nGuard: 8 documentation + 1 ordinary-command + 1 native proof passed first attempt: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40626246310 .\nEleven required reversals confirmed: original OOM, old 816 MiB budget, aliases, archives, metadata, loader/resources and eager resolution.\nHTML template reversal: https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40627088084 .\nGap search: offline help failed first, passed after repair, then failed after reversal; independent reviews found no remaining issue.\nSupplementary compiler-load diagnostics are inconclusive (daemon exit, no recorded Java OOM) and excluded from the proof.\nPR is open and unmerged. Native proof permits only three existing Detekt findings; no merge, enqueue or deployment. Handoff mirror timed out.\nEvidence: https://github.com/CodexCoder21Organization/jvm-libp2p/blob/evidence/dokka-review-022124/evidence/dokka-review-022124/result.md"
}
```

DONE — 2026-10-28 03:45:28 UTC. The requested BUDGET and maintained-GUARD findings on https://github.com/CodexCoder21Organization/jvm-libp2p/pull/79 are fixed on source https://github.com/CodexCoder21Organization/jvm-libp2p/commit/f6717ac782ab521ed39cdca7ab0eb024352b7fd7, pushed with an explicit old-head lease after fresh OPEN/head check and rebase. No merge/enqueue/deploy/publication. Fresh PR mutation response confirms OPEN at the pushed head.

The original512MiBaggregate is preserved as368MiBroot+one144MiBworker, with original3GiBaggregate heap split2+1. The buildscript compiler is removed, normal common renderer/compiler libraries reuse one worker, and standard Sync supplies shared Javadoc assets. The opt-in and executor-finalization settings are gone; serialization stays because removal gives656MiB.

First-attempt final source proofs: documentation8/8 and ordinary-command1/1 at https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40626246310/job/122611743970 ; native documentation1/1 at https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40626246310/job/122611743754 . Complete combined build exits0. Native documentation verdict permits exactly3existingDetekt findings; no full-suitegreen claimed.

Eleven required negative controls were inspected inXML/logs, including https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40627088084 for the post-green HTMLtemplate reversal. A supplementarycompiler-load probe ended with an unexplaineddaemonexit even after collecting its daemonlog at https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40627647171 ; it is inconclusive and excluded, with no attempt to call it JavaOOM.

Delegated work at a glance: one gpt-6-luna/max reviewer completed independent guard, DSLparity, loader and Sync/provider reviews; no outstandingissue and no activeagent. No requiredblocker. No unnecessaryholds. No idlelandinggates. Ownerdirectioncaptured: none new.

The broader handoff remains a separate integration effort; do not archive it on this scoped result. HandoffRPC reads/reports timedout afterbytecode-fetch failures, so this report and complete findings are also saved remotely on the evidence branch. ResultJSON is written at the requested path. No taskwork remains and no furtherverification will run.

Lessons: budgetallretainedprocesses; keep relatedlibraryclasses/resources together; verify optionaldependencyresolution at the publicboundary.


|Branch|Verifiedhead|State|
|---|---|---|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-classloader-proof-022124)|[1b8e2c15](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/1b8e2c153d516491174b0e296ece1e234f3dd9ab)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-compiler-cause-022124)|[6c1ae375](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/6c1ae37576b1282fbc3cb9928fd4275f347b8535)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-final-022124)|[d15fee62](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/d15fee624fa9597356bd539f6bef4aab6be4ab48)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-final-proof-022124)|[dd6cfe93](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/dd6cfe93632013808167b900091f5b8bb75724d1)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-final-renderers-022124)|[df493d24](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/df493d24de79fae2c98d372f89f598b30f9179ce)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-groovy-classloader-022124)|[5beb1a81](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/5beb1a8138a0a2947896167ca7620d9bad46c6a6)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-lazy-red-022124)|[fc2e9539](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/fc2e953936d1a6f0fb14a75b083cf23de9756fd1)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-minimal-022124)|[37f07a3b](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/37f07a3bdb2476d691358653134559f47bab45f3)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-minimal-proof-022124)|[bdf21c1e](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/bdf21c1e5bc39fb151aedb34deb1a21e57164cfa)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-plugin-boundary-022124)|[3476b30f](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/3476b30f0774bba7cf929443bb2816f78132d40d)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-plugin-boundary-proof-022124)|[fa41bdc8](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/fa41bdc8bedd6fd65b51fc90eb2c0cf0259543ad)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-renderer-boundary-022124)|[22f18983](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/22f1898320bf709035de7397236aa17f6758a797)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-renderer-proof-022124)|[6c4bd4ba](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/6c4bd4ba9a7705d9df57cfb97be7173ef31924ba)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-shared-assets-022124)|[f6717ac7](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/f6717ac782ab521ed39cdca7ab0eb024352b7fd7)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-shared-assets-proof-022124)|[feaacc6f](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/feaacc6f2430bee721f52c3a1ea3e3756af5bdf8)|Evidence/control only; never merge|
|[branch](https://github.com/CodexCoder21Organization/jvm-libp2p/tree/proof/dokka-template-control-022124)|[9b4a90f1](https://github.com/CodexCoder21Organization/jvm-libp2p/commit/9b4a90f120991972d9b929c31eec2f711acd6f48)|Evidence/control only; never merge|

Final central report and claim release each timed out after20seconds; both exited124. This scoped PR repair is complete, and the broader handoff was not archived.

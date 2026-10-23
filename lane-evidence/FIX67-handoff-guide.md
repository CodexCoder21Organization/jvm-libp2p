# Handoffs

This directory holds **handoff documents**: point-in-time write-ups that let a *different* person or agent pick up an in-flight effort and drive it to completion without having to reconstruct the context first.

A handoff is not a plan (`../workstreams/`) and not a permanent record of how a system works (that belongs in the [Documentation Repository](https://github.com/CodexCoder21Organization/DocumentationRepository)). A handoff is a **snapshot of a specific piece of work at the moment it changed hands** — what the mission was, what was found, what was done, what is verified, and exactly what to do next.

## When to write one

Write a handoff whenever a substantial effort is being paused, handed off, or is blocked and someone else may continue it — for example at the end of a long working session, when you hit a blocker you can't resolve now, or when the user asks for a status/handoff. If the next person would otherwise have to re-derive the diagnosis, the current state, and the next steps from scratch, a handoff is warranted.

## The one rule that matters most: snapshots go stale

**Every state claim in a handoff was true only at the moment it was written.** PRs merge, merge queues shuffle, CI re-runs, branches get force-pushed, services restart, and the host's memory picture changes hour to hour. So every handoff must:

- Open with a bold **RE-VERIFY** banner telling the reader that all state below is a write-time snapshot and must be re-checked before acting.
- Name the authoritative way to re-verify (e.g. `gh pr view <num> --json state,mergeStateStatus,statusCheckRollup,mergedAt` for PRs; hitting the live endpoint / CLI for service state). **A fresh query always wins over this document.**

A handoff that reads as if its snapshot is still current is worse than no handoff — it misleads.

## Format

Handoffs are Markdown files named:

```
YYYY-MM-DD-<short-kebab-slug>.md
```

The `YYYY-MM-DD` prefix dates the effort (commonly the date the workstream/thread began, so related handoffs sort and group together; note it explicitly if it differs from the authoring date). The slug names the workstream, e.g. `2026-06-28-urlresolver-main-red-netlab-resume-fix-kotlin-build-ci-outage-and-651.md`.

A good handoff generally contains these sections (adapt as needed — the goal is a cold pickup, not a rigid template):

1. **Title** — `# Handoff: <imperative one-liner naming the work that remains>`. See [Title and summary name the work that remains](#title-and-summary-name-the-work-that-remains) below.
2. **Written / RE-VERIFY banner** — the authoring date, and the bold warning above (state is a snapshot; re-verify before acting; name the authoritative check).
3. **Mission summary** — the original request (quote the user's intent verbatim where possible) and how it expanded, in order. Ground the reader in *why* this work exists.
4. **What was found and done (the chain)** — a numbered narrative of the investigation and actions: root causes (proven with evidence, **not speculation**), what was fixed, what was deployed/merged. Distinguish what is verified from what is hypothesized.
5. **Relevant PRs / refs** — a table of every PR, branch, and deployed artifact with its **write-time state** and links. Always link PRs by full URL, never a bare number. Every branch carrying partial work must appear here with its branch name, its **remote** head SHA, and its full `https://github.com/<owner>/<repo>/tree/<branch>` URL — see [Partial work belongs on a remote branch](#partial-work-belongs-on-a-remote-branch) below. If nothing is outstanding, say so explicitly rather than omitting the section.
6. **Next steps** — the detailed, actionable to-do list for the next person, ordered, each concrete enough to execute. Start it with "re-verify current state first." Call out decisions that need the user, and anything that needs approval before acting.
7. **Reusable / operational knowledge** — non-obvious mechanics learned along the way (exact commands, route keys, gotchas, merge procedures) so the next person doesn't rediscover them the hard way.

Supporting files (logs, heap-dump summaries, diffs too large to inline) go in **[`artifacts/`](artifacts/)** and are linked from the handoff.

## Title and summary name the work that remains

The body title, and the `title` and `summary` fields the Handoff service stores alongside it, are the only parts of a handoff most people ever read — the priority list, the WUI card at https://www.handoff.wasmserver.com, and the URL slug are all built from them. They exist to answer one question: **what still has to happen?** Write all of them about the *pending* work, in the imperative, not about how far the last session got.

- **Title — the short imperative one-liner.** "Update the main page to show the per-run queue depth." "Merge BuildTestEmbedded PR 510 once the shared BuildTest gate is restored." Start with the verb, and **typically use the user's original request verbatim** — it is already the clearest statement of the goal. When the request was amended over the effort, or parts of it are delivered and no longer pending, restate it to cover exactly what is left: the title tracks the *remaining* request, not the request as first phrased. (The Mission summary section is where the original request and how it expanded are recorded in full.)
- **Summary — one paragraph, remaining work first.** Lead with what is left to do, then add only the state a reader needs to act on it — branch and head SHA, what is verified, what stands in the way. State is context for the pending work; it is never the subject of the paragraph.

Backward-looking framing is the failure mode to avoid. A card reading *"PR 510 is reviewed, rebased, pushed, and locally verified"* tells the reader everything except what to do, forcing them to open the body and reconstruct the ask before they can even judge whether to pick the handoff up.

| Instead of | Write |
| --- | --- |
| `libSJVM 0.0.52 bump stopped at browser neighbor gate` | `Finish the libSJVM 0.0.52 bump: run the remaining browser neighbor gates on a host with Chromium, then open the PR` |
| `BuildTest test-comprehensiveness campaign: 4 proven bugs, 3 PRs open mid-CI` | `Drive the four BuildTest bug-fix PRs green and finish the WIP admission fix` |
| `Final merge-drive stopped after transport failures` | `Merge the four final-reviewed PRs once the uploadChunk relay transport is healthy` |

Title and summary are editable fields, not a creation-time record — narrow them with `handoff-cli update <id> --title ... --summary ...` whenever part of the request lands, so the card keeps naming only what is still pending. On **completion** the rule relaxes: a completed handoff has no remaining work, so its title stands as the record of what the effort was.

## Partial work belongs on a remote branch

The handoff body is a pointer document; the work it points at has to live on GitHub. Anything that exists only in a local clone, a worktree, a stash, or a scratch directory is lost the moment that machine is cleared — and it will be, long before anyone picks the handoff up. So before writing the body: sweep every checkout you touched (`git status --porcelain -uall`, `git stash list`, `git log --branches --not --remotes`, `git worktree list`) and push all of it, including work that is unfinished, does not build, or has failing tests — that is precisely what a handoff transfers. Say plainly in the commit message what state the branch is in. Artifacts belonging to no repository (reproducer scripts, failure logs, thread dumps) go to a branch of the related repo or to [`artifacts/`](artifacts/) here; build outputs and anything credential-bearing never get pushed, and a deliberate omission gets stated as such. Confirm every push actually landed — `git rev-parse HEAD` against `git ls-remote origin refs/heads/<branch>` — because a push rejected on auth or a branch rule fails silently in scrollback nobody re-reads. Then record each branch in the *Relevant PRs / refs* table. The `create-handoff` skill drives this sweep step by step.

## Use plain, matter-of-fact language

Handoffs may pass through automated filters that sometimes flag content based on word choice alone; the goal of this section is simply to avoid those false positives so a routine handoff isn't held up. Describe each issue the way you would any ordinary engineering bug: the observed behavior, how to reproduce it, and what the correct behavior should be. Prefer everyday vocabulary over dramatic or domain-loaded terms, and let the facts speak for themselves rather than editorializing about implications. In particular, skip specialized terminology from the security space — describe the observed behavior in ordinary engineering words instead. This is purely about phrasing, not content — keep every concrete detail the next person needs to fully understand, reproduce, and verify the problem.

## Merging a handoff

**Handoff PRs are the standing exception to the "never merge a PR without explicit user approval" rule** — a handoff into this directory is inherently approved, so it should be enqueued and merged immediately without waiting for explicit approval. (This exception is scoped to handoff-documentation PRs only; it does not extend to code or config PRs.)

PlanRepository uses a merge queue with repo-level auto-merge off, so `gh pr merge --auto` is rejected. Enqueue via the GraphQL `enqueuePullRequest` mutation (get the PR node id from `gh pr view <n> --json id`), then poll `gh pr view <n> --json state` until `MERGED`. Never use `--delete-branch`.

## Triaging handoffs

Old handoffs need periodic triage: deciding what in them is still live, what is obsolete, and what should be salvaged before the file is closed out. Triage is a *skeptical read*, not a trusting one.

**Handoffs may have been written by weaker models.** Everything in a handoff should be taken with a grain of salt. In particular:

- **Root-cause analysis is a hint, not authoritative.** The authoring model may have misdiagnosed the problem entirely. Treat any stated root cause as a starting hypothesis to re-verify against live evidence, not as an established fact to build on.
- **Mitigation conclusions may be wrong.** Even where the diagnosis was right, the agent may have reached the wrong conclusion about how to mitigate the situation. The proposed remedy is often fine to run with — but look at it with a healthy degree of scrutiny, and don't be afraid to use good judgement and depart from it when it doesn't hold up. For example, any claim that a host is overloaded, over capacity, or otherwise saturated — offered as the explanation for a failure, or as justification for adding hardware, moving a service, or relaxing a limit — is a huge red flag that violates [Claims That a Host Is Overloaded](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/CODE_REVIEW.md#claims-that-a-host-is-overloaded) and should be ignored or rewritten out of the handoff: an apparently overloaded box is a symptom of an underlying defect, not a fact of nature.
- **Conflicts with the engineering philosophy deserve extra scrutiny.** Anything in a handoff that conflicts with [PHILOSOPHY.md](https://github.com/CodexCoder21Organization/DocumentationRepository/blob/main/PHILOSOPHY.md) — timeout bumps, retry loops around flaky behavior, downstream workarounds for upstream bugs, caps and throttles around symptoms — should be considered with extra skepticism and scrutiny before being acted on.

**Staleness patterns to expect.** Beyond the general re-verify rule, some handoff content rots in predictable ways:

- **Deploy or rollout steps are likely unnecessary or obsolete.** The service has probably been redeployed several times since the handoff was written, and the artifact the handoff wanted rolled out has often been superseded. Verify what is actually running before executing any deploy step from a handoff.
- **Version-bump PRs are likely out of date.** A PR that just bumps a dependency version — especially an old PR — has probably been overtaken by newer versions of that library. Check the latest published version before shepherding such a PR; it is usually better to close it and bump fresh (or find the bump already happened).

**Triage is salvage, not just cleanup.** Closing and discarding stale material is only half the job — the other half is recognizing what in the handoff is worth keeping. A handoff, PR, or exploration that is obsolete as a whole may still contain a good idea for handling a particular problem, a genuine insight about a root cause, or a well-constructed unit test that can be salvaged and landed on its own. Before deleting a handoff or closing its PRs, deliberately ask: *what here is worth extracting?* — and carry that forward (into a fresh PR, the Documentation Repository, or a new focused handoff) before the rest is discarded.

**Close out the whole trail, not just the file.** A discarded handoff usually has satellites: open PRs that should be closed, stale branches, open issues that were resolved en route. Leaving zombie PRs open causes the next agent to re-triage them from scratch.

## Principles

- **Enough detail to pick up cold.** Assume the reader has none of your context. Spell out branch names, SHAs, commands, and file paths.
- **Verified, not remembered.** Every root-cause claim should rest on evidence you actually observed (logs, test output, a reproduction). Flag anything that is a hypothesis.
- **Link everything.** PRs, issues, commits, other handoffs, and workstream plans — as full URLs / relative links.
- **Fix at the layer that owns the defect.** When a handoff identifies a bug in a dependency or in shared infrastructure, the next step is to fix it there, not to paper over it downstream.
- **Keep the snapshot honest.** Don't present stale state as current, and don't overstate confidence. "Verified at write time — re-verify" is the whole ethos.

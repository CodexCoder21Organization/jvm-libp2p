06:28 UTC — RUNNING. The full-suite comparison for https://github.com/CodexCoder21Organization/jvm-libp2p/pull/70 is still waiting for the shared lock, with two lanes ahead after68minutes. The current holder is actively starting test JVMs. The PR remains open/draft at091b6d17 on unchanged develop; oom_kill remains1.

The existing fix tracks explicit child-read demand. Delivery consumes that demand before callbacks run. A pending request also drives the paused physical transport.

No source changes or delegates. Blockers: no user action needed. Holds audit: comparison needs fresh suite results; final counts need comparison. Landing sweep: no merge authorized and no new CI run requested. Owner direction: none new.

Next: finish the locked suite, compare every failure on both branches, fix any new defect, and update the draft description. Lessons: retain the full execution allowance after queueing; reject stale reports; verify baseline claims by exact test identity.

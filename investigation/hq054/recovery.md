# Recover the hq054 evidence

The sources and results here are evidence, not implementation changes or a passing full gate. Protocol source is cc33cf0df81e5980d9cc80234a393192ecef392e on https://github.com/CodexCoder21Organization/UrlProtocol/tree/fix/hq032-protocol-rollout. Its source bundle and the earlier red controls remain on https://github.com/CodexCoder21Organization/jvm-libp2p/tree/fix/hq032-rollout-evidence/investigation/hq032.

The original startup failure is on that earlier evidence branch. This directory contains the new controls: `results.json` and `hq054-startup-probe.log.gz` for the first 32-agent/32-JDK comparison; `hq054-runner-startup.xml` and `runner-startup/` for the actual manager209 register; seven `hq054-stress-startup-N.xml` first-attempt passes; and `startup-profile/results.json` with all later paired class-init logs. The eighth exact-path command ended at the register deadline without a result. The full run exited137 and the directed remote run failed health before submission. Neither has a full XML result.

To replay the isolated startup controls in a new owned directory:

1. Copy `StartupProbe.java`, `run.py` and the original `inputs.json` there.
2. Resolve CLI125 through Coursier and locate its manager209 JAR. Extract `/unittest-native-sha256-agent.jar` from that manager with Python's `zipfile` module. Keep the original input SHA-256 values in `hq054-startup-probe.log.gz` when checking provenance.
3. Fetch the regular `org.conscrypt:conscrypt-openjdk:2.6.3:linux-x86_64` JAR through a declared repository. Rewrite only `manager` and `native` paths in the copied `inputs.json` for the new directory, then run `javac StartupProbe.java` and `python3 run.py`.
4. The profiled comparison's `startup-profile/run.py` preserves the exact source used here, including its old absolute `base` path. Point that single path at the recovered control directory before running it. It creates only real JVM startup/registry work, eight callers, with the recorded128MiB/C1/G1 options and60s bound. Do not change those bounds or treat a passing rerun as a source fix.

`monitor-stress.py`, `monitor-full.py` and `run-full.sh` also preserve the executed absolute workspace/PID paths. They are historical commands, not commands to execute blindly. Update owned paths/process identity for a fresh run, retain test assertions/limits, and follow the supervisor's current execution direction. Heavy runs are currently remote-only; the local full command must not be repeated without new direction.

Generated JARs/classes and dependency caches are deliberately excluded. Compressed logs use binary Git attributes. `hq054-parent-prs.json` and `hq054-final-pr-state.json` contain complete URLs and write-time states. The central handoff remains authoritative for what is left: url://handoff/handoffs/hf-2026-10-26-roll-the-merged-jvm-libp2p-duplicate-settlement-fix-downstream-publish-fork-pin-urlprotocol-publish-protocol-pin-urlresolver-flip-the-consumer-reproducer-and-finish-the-remote-runner-failure-reproducers.

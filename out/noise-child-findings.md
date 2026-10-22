# Noise child-process qualification

Plan
- [in progress] Read harness/evidence; measure child startup, work and shutdown.
- [pending] State mechanism and invariants; deterministic failing regression.
- [pending] Fix; pass/revert/red/restore; independent test and code review passes.
- [pending] First class and Noise-package qualification, full libp2p suite once, format/static checks.
- [pending] Rebase develop, targeted verification, checkpoint and PR.

2026-10-22 02:49 UTC
OBSERVED: Fresh clone at b01e08e5 on fix/noise-child-process-qualification. README and FORK.md read; no AGENTS.md in clone. CONTRACT.md read fully; it describes Handoff usage, while lane-specific brief assigns this Gradle/JUnit Noise qualification and develop target. The lane-specific instructions govern. No sibling checkout edited.
OBSERVED: Required testing documentation sections Test-Driven Development and Tests Must Be Hermetically Sealed read through authenticated GitHub API (web page returned 404). Baseline findings, child harness all 738 lines, baseline/profile logs and transport shutdown read. Transport already uses zero quiet period, daemon threads, shared workers. The inline proof precedes finally/stop; captured output uses readText to EOF.
OBSERVED: rg unavailable; using grep/find. df reports 29 GiB free. Gradle rather than kompile scripts; boxrun wraps Gradle directly.
INFER: Cold class loading precedes first harness output. JFR and live measurements needed to identify an actionable mechanism; neither a shutdown defect nor buffering is established yet.

2026-10-22 02:52 UTC
OBSERVED: Baseline JFR has 112 execution/wait events, with stack depth truncated before harness callers. Main samples include manifest parse/verification between 01:57:43.895 and 01:57:45.133, Ed25519 initial table work at 01:57:45.300–45.364, Noise Curve25519 work at 01:57:48.679. The supplied proof print was at 10.475 seconds and process exit at 11.202 seconds. This is sampled evidence, not a complete stage timeline.
OBSERVED: getconf reports 8 online processors and cgroup cpu.max is unlimited; the source computes 4 Noise workers on this machine. No evidence yet of an oversized pool. Build preparation has reached compileKotlin.

2026-10-22 02:55 UTC — checkpoint df1f4208f2304b22c28527cae937293922c80cf1
OBSERVED: Preparation is compiling test Kotlin. Scratch-only instrumented copy compiled successfully through boxrun; it adds timestamps at main, recorder, host build/start/stop. No production or test source edited. Diagnostic child is running with periodic jstack summaries and a CPU/wait-only JFR at stackdepth 128. Diagnostic overhead means this run is not a test qualification.
OBSERVED: Both parent paths start whole-process deadlines immediately after ProcessBuilder.start (test lines 51–64 and 699–710), before any host/classpath initialization. Output is read to EOF, but child println writes to the pipe during execution; no evidence yet that output buffering causes the empty baseline capture.
INFER: HandshakeState creation (NoiseXXSecureChannel.kt:188) precedes admission to the computation executor (line 528 onward), so observer counts do not account for construction. Whether this contributed to measured p95 is not established.

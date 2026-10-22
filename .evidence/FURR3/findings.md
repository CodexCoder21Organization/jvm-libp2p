# FURR3 findings
Started 2026-10-22 UTC.
Plan:
- [in progress] Locate consumed fork; read design, source and test guidance.
- [pending] Add library-boundary reproducer and push first red checkpoint.
- [pending] Add negotiated binding, optional capability and typed setup result.
- [pending] Targeted checks, reversal control, correctness and test review.
- [pending] One documented complete suite, PR and final file.
Scope: jvm-libp2p only; no publication, merge, queue or deployment. Stop after PR creation without CI monitoring.
Required reading: approved rFURR2-design.md and rFURR2-findings.md read in full. The final design uses Yamux; the earlier findings include superseded Mplex wording.

OBSERVED: Fork https://github.com/CodexCoder21Organization/jvm-libp2p has default branch develop, no main, and develop is e15e0f1947842be7ef6836c116c14ba47cc8eea1, exactly the design commit. FORK.md assigns snapshot-37; snapshot-32 is the earlier consumer pin. No version changes planned.
OBSERVED: README.md and FORK.md read; no AGENTS.md found. This is Gradle, with JUnit Kotlin tests under libp2p/src/test/kotlin. Documented complete command is ./gradlew :libp2p:test. No scripts/test.bash exists, so the brief's kompile-only XML and remote command do not apply.
OBSERVED: YamuxHandler.kt:196 maps every RST to onRemoteClose; :343 emits empty DATA/RST. AbstractMuxHandler.kt:374 calls resetRemoteSubstream before creating a child. Yamux currently inherits its empty implementation. ProtocolSelect.kt:90 completes protocol and selected futures before close, but callbacks may close the child between completions.
The mechanism is that Yamux sends and reads an ordinary empty reset for a receiver capacity decision, and the evidence that demonstrates it is YamuxHandler.kt:196,343 and AbstractMuxHandler.kt:374. A negotiated marker can carry that per-child result; recording its cause before callbacks preserves it during close re-entry.
Plan refinement: preserve existing binding source files and Stream.kt unchanged; add a separate binding with the same codec and accounting. Add a terminal setup cause before any completion callback. Tests use real Netty channels and multistream selection, plus real host negotiation.
Invariant table:
| Resource | Creator/owner | Close and terminal states | Caller and callback contract | Public check |
| Binding | Multistream/connection | Immutable per session | Legacy has no capacity semantics | New/new and both mixed directions |
| Reset body | Codec/mux | Release once on every route | No body reaches application reads | Partial/coalesced, duplicate/late, unknown bytes |
| Setup futures | Mux and ProtocolSelect | First completion retained | Set terminal cause before callbacks; close re-entry retains it | Pending protocol/controller, completed and cancelled futures |
| Child | Mux session | Close once | Capacity affects exact local child only | Siblings usable and new slot reusable |
| Parent | Transport | Parent close terminal | Capacity never reopens a closed parent | Parent-close control |

OBSERVED: Initial tests and API scaffold added under the repository's Gradle/JUnit layout. The new binding currently delegates to the unchanged handler, so capacity cases should report ordinary closure. Targeted run is downloading the documented Gradle wrapper distribution; no test verdict yet. Test channels use real framing and multistream negotiation, with manual byte delivery to make ordering deterministic.

OBSERVED: Preserved-source baseline hashes:
libp2p/src/main/kotlin/io/libp2p/core/Stream.kt 7a2239f482f44e1c8eab5bc2b27cf20d07c84fd4ef7f6d47f8c32f9446086c7d
libp2p/src/main/kotlin/io/libp2p/mux/yamux/YamuxStreamMuxer.kt 3f45f6d3ca35d4d75d05531ec78b47bd677fd66791fd51b1722423d58608f2ea
libp2p/src/main/kotlin/io/libp2p/mux/mplex/MplexStreamMuxer.kt 4bbf965adfe5073595406fadb1187827f4c8d90740349fc2e52545d723a720e2

OBSERVED: First targeted command completed. The initial embedded-channel harness had not advanced its queued child creation before reading a stream future. This produced setup waits at the test helper rather than the requested capacity assertion, so this run is not evidence of the transport defect. The helper now advances the embedded event loop immediately after opening; budgets and assertions are unchanged. Re-running both capacity test classes before implementation.

OBSERVED: Corrected fail-first targeted run: 15 tests, 9 red, 6 green; each executed once (Gradle/JUnit has no configured test retry). Seven marked-reset setup cases report ConnectionClosedException instead of StreamCapacityException; application capacity new/new over real TCP/Noise also reports ConnectionClosedException; the small mux-limit case remains pending because the scaffold delegates to legacy Yamux. Both mixed TCP directions and old/old pass, as do ordinary reset and actual parent close controls. This demonstrates the missing negotiated signal, rather than scheduler timing.
Targeted class io.libp2p.mux.yamux.YamuxCapacityHostsTest: tests=4, red=1, skipped=0.
Targeted class io.libp2p.mux.yamux.YamuxCapacityResultTest: tests=11, red=8, skipped=0.

OBSERVED: Implementation targeted run is green: 15/15 first attempt, zero skipped, zero test retries configured. Both capacity test classes passed, including 4 real TCP/Noise compatibility combinations. Capacity frames now complete pending setup futures with the same typed cause before close callbacks, while completed futures retain their prior result. The optional operation sends ordinary reset on legacy sessions. New mux admission checks the bound before allocating a child or a stream handler.
Plan: red reproducer and implementation complete. Next: independent correctness/test review, add any identified boundary controls, reversal red, final targeted controls and the one complete suite.

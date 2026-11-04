# Coverage additions progress

## 2026-11-04 18:32 UTC

- **OBSERVED:** Added `PlainNettyTransportFailedCloseSetTest.kt` with two ephemeral TCP listeners and eight live accepted roots created by eight separately configured client hosts. It injects a close failure after physically closing one listener, checks the exact failure object, every captured listener/root close future, boss-group termination and thread exit, and continued shared-worker availability.
- **OBSERVED:** Added `QuicTransportFailedConnectionSetTest.kt` with four direct QUIC dials from one client transport to one server. It injects the same physical-release-before-failure close behavior on one outbound QUIC channel, checks the exact failure object, all four QUIC channels and distinct UDP parents, client group/thread termination, and continued server-group/listener availability.
- **OBSERVED:** No production source or existing tests were changed. No builds, tests, or JVMs were run, as assigned; root owns red/green verification and formatting.
- **INFER:** These tests exercise the captured close sets identified in `coverage-plan.md`; test execution is still needed to establish compilation and red/green behavior.

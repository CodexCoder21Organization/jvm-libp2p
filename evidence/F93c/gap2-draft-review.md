# F93c multiple QUIC close failures: draft review

## Plan

1. Read the linked engineering, review, and testing guidance in full.
2. Read the product README and both named transport tests in full.
3. Draft one self-contained public-transport test in the evidence directory.
4. Review the complete draft against the brief and report any limits.

## Progress

### Documentation and existing-test review complete

**Observed:** The current test `QuicTransportFailedConnectionSetTest` covers one outbound child close failure and four direct QUIC dials. `F93bQuicSecondaryCloseFailureTest` covers one child plus one UDP-parent close failure. The product implementation snapshots child channels in insertion order, waits for child close operations before starting UDP-parent closes, then shuts down the owned worker group. `transportCloseResult` walks the supplied operations in order, preserves the first distinct failure as primary, and adds later distinct failures as suppressed.

**Inferred:** Sequentially awaiting each direct dial makes its first child the first tracked client child. A focused test with four unique child failures and four unique parent failures can check the missing multi-operation failure aggregation case while using real QUIC peers and public channel operations.

## Draft status

Not written yet.

Draft only; this probe has not been compiled or run.

It is designed to check public ordering: whether the third remote QUIC child closes while the anchor child's shutdown close result is held, whether the third dial remains pending, and whether the captured client UDP source port stays bound until the shutdown barrier finishes. It does not observe the rejected local child's physical close future or its result, so it cannot establish whether that close succeeds or whether its result is lost.

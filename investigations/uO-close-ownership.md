# Shared parent close ownership

The buildtest utilization effort led to UrlResolver connection-lifetime review at https://github.com/CodexCoder21Organization/UrlResolver/pull/1080. Its relay shutdown gate observed duplicate close requests on a physical parent.

OBSERVED: The mechanism is NetworkImpl.close invoking Transport.close and Connection.close on the same Netty parent while the first request is still pending. The evidence is the consumer caller trace and testNativeNetworkShutdownClosesParentOnce: holding the first physical close produces exactly five requests from two network shutdowns plus one explicit connection close (expected one). The native ConnectionOverNetty, P2PChannelOverNetty, and PlainNettyTransport files match the published snapshot-26 source. The equivalent upstream public-API test is ParentCloseOwnershipTest.networkAndConnectionShutdownShareOnePendingPhysicalClose. Its initial Gradle build is running; no source fix is applied yet.

| Resource | Creator / owner | Closing rule | Hostile caller proof |
| --- | --- | --- | --- |
| Physical parent channel | Transport creates; the channel owns its one close operation | First requester claims the operation before calling handlers; all later connection/transport requests join it | Hold first close and invoke connection plus repeated network shutdown; physical count remains one |
| Close operation result | Channel retains private completion state; each caller borrows a dependent future | A caller cannot finish or cancel another caller's close; completion reflects the physical operation | Complete one caller's future before releasing held close; both network shutdowns remain pending |
| Completed parent | Channel close is terminal | Later explicit close does not traverse the pipeline again | Repeat connection and network close after completion; count remains one |
| Failed close | Operation retains its failed result | Later owners receive the failure; no implicit retry | Pending additional failure-path test before relying on a release |

INFER: A channel-level operation is needed because Connection.close and transport teardown currently enter through different APIs. A resolver-only delay would hide the upstream duplication and would not fix NetworkImpl's public contract. No production deployment or artifact publication is authorized in this lane; source/test checkpoints remain on remote branches.

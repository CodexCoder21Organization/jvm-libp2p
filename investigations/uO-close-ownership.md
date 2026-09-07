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

OBSERVED (18:53 UTC): The upstream public-API baseline completed: two tests, two failures. Pending-close case expected 1 request and observed 5; failed-close case expected 1 and observed 3. Both fail at counts, not timeouts, and both preserve the exact original transport error. Full console: uO-upstream-baseline.txt. The fixture compilation correction only documented use of Netty's explicit-executor overload; no production source had changed.

The mechanism I am about to fix is independent connection and transport close admission for the same physical channel, and the reproducer that demonstrates it is ParentCloseOwnershipTest with the first channel close held or failed deterministically. The channel will retain one private operation future, installed atomically before handlers can re-enter. Connection close and transport shutdown will join that operation; each caller receives a dependent future so completing a borrowed future cannot finish shutdown for others.

OBSERVED (19:05 UTC): Both upstream test scenarios pass (2 tests, 0 failures) after the source change at d80a3253. The source review also strengthened borrower isolation to assert directly against another connection-close future. The just-finished Gradle compilation took its test snapshot before that last assertion was added (confirmed by checking the assertion text in the generated class). The stronger assertion is being exercised in the consumer counting gate; no exact-final-head Gradle pass is claimed. No artifact was published.

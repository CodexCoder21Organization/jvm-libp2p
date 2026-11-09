UrlProtocol host shutdown must complete when a full native event-loop queue rejects close work. This is the library change needed by https://github.com/CodexCoder21Organization/UrlProtocol/pull/992: the user requested proof of selector release on both successful and exceptional parent-close routes before completing failed dials.

TCP and WebSocket always shut down their owned acceptor groups after listener and connection close work settles. QUIC retains each outbound UDP parent until its physical close and selector release finish, preserving the original close result, including a failed request after the parent has physically closed.

## Ownership and completion invariant

For a dial whose UDP parent is being cleaned up, completion, success or failure, is delivered only after that parent has deregistered and released its port. Per-loop release waiters are created by owned parent cleanup, owned by that loop, and completed after a selector pass or completed selector destruction. A failed request result is preserved while awaiting release when the physical parent is already closed. A rejected request that leaves the parent open must not wait forever. Callback re-entry enters the next batch; terminal destruction drains all waiters.

## Proof on the selector-release change

- Candidate with the exceptional-route wait missing: normal fixture1/1 pass; exceptional fixture0/1 pass, failing because the dial was already complete while deregistration stayed held.
- Corrected implementation: both stronger fixtures2/2 first passes; exceptional fixture30/30 single attempts,0 retries/skips.
- Explicit old-source control: the same compiled fixtures0/2 first passes at the pending-result assertion.
- Both fixtures force a real queued physical UDP close, show the port is unavailable while held, and rebind the exact address after release. The exceptional fixture checks the exact original close failure at shutdown.
- Rejected and terminated-loop neighbors3/3 first attempts,0 retries/skips. Incremental Gradle compilation and formatting1/1 pass.
- The original F93f diagnostic is incompatible with the requested invariant: it waits for dial failure at line48 before releasing held unregistration at line53. Two single attempts timed out at line48 under the corrected behavior; neither continued after its first failure. It cannot truthfully be reported as an unchanged60-pass test. The stronger contract-correct fixture is the30-attempt gate.

## Actions and current limits

Inherited head68c436af Actions:1669/1672 first passes,1 queued sequential connection-setup failure,2 pre-existing skips,0 retries. Exact develop3b2b6640:1631/1633 first passes,0 failures,2 skips,0 retries. The setup harness has now passed30/30 single attempts on each side. That does not establish that the earlier failure is inherited; it remains unresolved. Its TCP inline-admission path does not use the UDP selector-release wait.

Develop remains3b2b6640. The previously reviewed7 overlap commits affect README/FORK/build.gradle, with0 equivalent transport fixes. No newer develop commits were present during this lane's rebase.

Addendum A2 removes the all-touched30 register and assigns remaining work to the orchestrator: pending bd0e430f/9467f665 fixtures, late-child cleanup and failed-history gaps, concurrent waiter/re-entry/destruction coverage, wider repeat gates and publication. No artifact has been published by F93i; no merge, queue, close or deploy action was taken.

Final-head Actions XML is pending collection after this product push. Product commit: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/12cb229eb8f6baf50c060ac9bc29156611301af8

## Existing default-branch test edits

Exact before/after text and justifications are also preserved in https://github.com/CodexCoder21Organization/jvm-libp2p/blob/wip/F93i-evidence-20261109/evidence/F93i/existing-test-edits.md . F93i made0 new default-branch test edits. The inherited3 cleanup replacements have the2026-11-05 02:56 approval; the2 added body assertions and preparation handling below are marked orchestrator ruling needed.

OBSERVED: Inherited edits compared against develop 3b2b6640; 3 approved cleanup replacements and 3 changes needing an orchestrator ruling.

OBSERVED: ParentCloseOwnershipTest.kt exact before/after text follows; removed lines are develop and added lines are inherited PR head.
```diff
diff --git a/libp2p/src/test/kotlin/io/libp2p/transport/ParentCloseOwnershipTest.kt b/libp2p/src/test/kotlin/io/libp2p/transport/ParentCloseOwnershipTest.kt
index 54e70ffa..fbb8b156 100644
--- a/libp2p/src/test/kotlin/io/libp2p/transport/ParentCloseOwnershipTest.kt
+++ b/libp2p/src/test/kotlin/io/libp2p/transport/ParentCloseOwnershipTest.kt
@@ -118,7 +118,13 @@ class ParentCloseOwnershipTest {
                 parent.nettyChannel.close().get(5, TimeUnit.SECONDS)
                 parent.nettyChannel.eventLoop().submit {}.get(5, TimeUnit.SECONDS)
             }
-            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
+            try {
+                var reported: Throwable = assertThrows(ExecutionException::class.java) { client.stop().get(10, TimeUnit.SECONDS) }
+                while (reported.cause != null) reported = reported.cause!!
+                assertSame(failure, reported, "Host shutdown must retain the original failed parent close after socket cleanup.")
+            } finally {
+                server.stop().get(10, TimeUnit.SECONDS)
+            }
         }
     }
 }

```
OBSERVED: ConnectionCloseRetryTest.kt exact before/after text follows; removed lines are develop and added lines are inherited PR head.
```diff
diff --git a/libp2p/src/test/kotlin/io/libp2p/transport/ConnectionCloseRetryTest.kt b/libp2p/src/test/kotlin/io/libp2p/transport/ConnectionCloseRetryTest.kt
index 3f30fa9a..b9fc5f12 100644
--- a/libp2p/src/test/kotlin/io/libp2p/transport/ConnectionCloseRetryTest.kt
+++ b/libp2p/src/test/kotlin/io/libp2p/transport/ConnectionCloseRetryTest.kt
@@ -131,6 +131,7 @@ class ConnectionCloseRetryTest {
             connection.closeFuture().get(5, TimeUnit.SECONDS)
             assertEquals(2, requests.get(), "Retry must recover a physical close first owned by transport shutdown.")
             assertSame(failure, retryCloseFailure(connection.close()))
+            assertSame(failure, retryCloseFailure(connection.transport().close()))
         }
     }
 
@@ -220,6 +221,7 @@ class ConnectionCloseRetryTest {
                 connection.retryClose().get(5, TimeUnit.SECONDS)
                 assertSame(failure, retryCloseFailure(connection.close()))
                 assertEquals(2, requests.get(), "Success must remain terminal across subsequent retry and ordinary callers.")
+                assertSame(failure, retryCloseFailure(connection.transport().close()))
             } finally {
                 workers.shutdownNow()
                 try {
@@ -341,7 +343,25 @@ private fun withRetryConnection(body: (ConnectionOverNetty, Connection) -> Unit)
         try {
             cleanupRetryConnection(parent, "retry-close-control")
         } finally {
-            CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
+            val connection = parent
+            val readiness = connection?.transport()?.ready()
+            assertTrue(readiness == null || readiness.isDone, "The fixture must already have completed transport preparation.")
+            if (readiness == null || !readiness.isCompletedExceptionally) {
+                CompletableFuture.allOf(client.stop(), server.stop()).get(10, TimeUnit.SECONDS)
+            } else {
+                try {
+                    var closed: Throwable = assertThrows(ExecutionException::class.java) { readiness.get(10, TimeUnit.SECONDS) }
+                    while (closed.cause != null) closed = closed.cause!!
+                    assertEquals("Transport is closed", closed.message)
+                    // The scenario already checks this same public result against its local failure object.
+                    val expected = retryCloseFailure(requireNotNull(connection).transport().close())
+                    var reported: Throwable = assertThrows(ExecutionException::class.java) { client.stop().get(10, TimeUnit.SECONDS) }
+                    while (reported.cause != null) reported = reported.cause!!
+                    assertSame(expected, reported, "Host shutdown must retain the same failed transport result after socket cleanup.")
+                } finally {
+                    server.stop().get(10, TimeUnit.SECONDS)
+                }
+            }
         }
     }
 }

```
INFER: The 3 cleanup replacements preserve the original failure identity and require the server stop to succeed within the same 10 second bound; approved ruling 2026-11-05 02:56.
INFER: The 2 ConnectionCloseRetryTest body assertions and the preparation handling change require orchestrator ruling; inspect the attached exact diff, no additional default test edits made by F93i.
OBSERVED: ConnectionCloseRetryTest body change1 adds an exact identity assertion on transport.close() after the first retained failed close; before there was no transport.close() assertion at that position.
OBSERVED: ConnectionCloseRetryTest body change2 adds the same exact identity assertion in the explicit retry case; before there was no transport.close() assertion at that position.
INFER: Both added assertions check that transport shutdown retains the original channel-close failure even after physical cleanup; they are stricter checks, but touch default-branch test bodies, so both are marked orchestrator ruling needed.
OBSERVED: Preparation handling replaces unconditional combined successful host stops with a readiness completion assertion, an unchanged successful-stop branch for ordinary preparation, and exact closed-preparation plus retained failure checks in the failed-preparation branch; server.stop still must succeed within10 seconds.
INFER: Preparation may complete exceptionally when its connection callback closes the transport; this is why unconditional successful client.stop no longer describes the expected outcome. This change is marked orchestrator ruling needed.


Evidence: https://github.com/CodexCoder21Organization/jvm-libp2p/tree/wip/F93i-evidence-20261109/evidence/F93i

🤖 Generated with [Claude Code](https://claude.com/claude-code)

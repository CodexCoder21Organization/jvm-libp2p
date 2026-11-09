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

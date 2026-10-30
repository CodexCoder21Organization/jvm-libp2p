package io.libp2p.pubsub.gossip

import io.libp2p.core.RpcHandlerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.RepeatedTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GossipListenPortIsolationTest {
    @RepeatedTest(10)
    fun `overlapping gossip fixtures can connect independently`() {
        val first = GossipBackwardCompatibilityTest()
        val second = GossipBackwardCompatibilityTest()
        try {
            // Keep the first pair bound, forcing the overlap instead of relying on scheduling.
            first.startHosts()
            second.startHosts()
            first.testConnect_1_1_to_1_2()
            second.testConnect_1_2_to_1_1()
            val addresses = listOf(first.host1, first.host2, second.host1, second.host2)
                .flatMap { it.listenAddresses() }
                .map { it.toString().substringBefore("/p2p/") }
            assertEquals(4, addresses.toSet().size)
        } finally {
            CompletableFuture.allOf(
                first.host1.stop(),
                first.host2.stop(),
                second.host1.stop(),
                second.host2.stop()
            ).get(5, TimeUnit.SECONDS)
        }
    }

    @RepeatedTest(10)
    fun `overlapping rpc fixtures can connect independently`() {
        RpcHandlerTest().apply {
            onListening = { first ->
                RpcHandlerTest().apply {
                    onListening = { second -> assertNotEquals(first, second) }
                }.test1()
            }
        }.test1()
    }

    @RepeatedTest(10)
    fun `rpc fixture works while gossip listeners remain open`() {
        val gossip = GossipBackwardCompatibilityTest()
        try {
            gossip.startHosts()
            RpcHandlerTest().test1()
            gossip.testConnect_1_1_to_1_2()
        } finally {
            CompletableFuture.allOf(gossip.host1.stop(), gossip.host2.stop()).get(5, TimeUnit.SECONDS)
        }
    }
}

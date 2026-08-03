/*
 * Load generator for [ChurnVictim]. Each client host repeats the production churn shape observed
 * downstream: establish a connection, open a burst of 150 substreams the victim cannot finish
 * negotiating, pause, then drop the whole connection so those substreams close mid-negotiation.
 * See [ChurnVictim] for how to run the pair and for the measurements taken with it.
 */
package io.libp2p.network

import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping
import io.libp2p.protocol.PingController
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ChurnDriver {
    @JvmStatic
    fun main(args: Array<String>) {
        val serverPeerId = PeerId.fromBase58(args[0])
        val serverAddress = Multiaddr(args[1])
        val durationSeconds = args[2].toLong()
        val clientCount = if (args.size > 3) args[3].toInt() else 10
        val pauseMillis = if (args.size > 4) args[4].toLong() else 20L
        val stop = AtomicBoolean(false)
        val opened = AtomicLong()
        val hosts = (0 until clientCount).map {
            host {
                muxers { +StreamMuxerProtocol.getYamux() }
                protocols { add(Ping()) }
            }.also { h -> h.start().get(15, TimeUnit.SECONDS) }
        }
        val threads = hosts.mapIndexed { index, client ->
            Thread {
                while (!stop.get()) {
                    runCatching {
                        client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), serverPeerId, serverAddress)
                            .stream.get(6, TimeUnit.SECONDS)
                    }
                    repeat(150) {
                        runCatching {
                            client.newStream<PingController>(listOf("/ipfs/ping/1.0.0"), serverPeerId, serverAddress)
                            opened.incrementAndGet()
                        }
                    }
                    if (pauseMillis > 0) Thread.sleep(pauseMillis)
                    client.network.connections.toList().forEach {
                        runCatching { client.network.disconnect(it).get(5, TimeUnit.SECONDS) }
                    }
                }
            }.apply {
                isDaemon = true
                name = "churn-$index"
            }
        }
        threads.forEach { it.start() }
        val end = System.currentTimeMillis() + durationSeconds * 1000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(5000)
            println("DRIVER opened=${opened.get()}")
            System.out.flush()
        }
        stop.set(true)
        threads.forEach { it.join(30_000) }
        println("DRIVER done opened=${opened.get()}")
        hosts.forEach { runCatching { it.stop().get(10, TimeUnit.SECONDS) } }
    }
}

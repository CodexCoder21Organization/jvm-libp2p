/*
 * Reproduction harness for the reported "NetworkImpl connection table grows without bound under
 * connection churn" behaviour. Run this class as the victim in one JVM and [ChurnDriver] as the
 * load generator in another:
 *
 *   CP=<test runtime classpath>
 *   java -Xmx128m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError \
 *        -cp "$CP" io.libp2p.network.ChurnVictim            # prints READY peerId=... addr=...
 *   java -cp "$CP" io.libp2p.network.ChurnDriver <peerId> <addr> <seconds> [clients] [pauseMillis]
 *
 * The victim prints its `network.connections` size and used heap once a second, so the connection
 * table can be watched directly while the driver churns.
 *
 * Measured on develop @ 91d422b (1.3.0-codexcoder21-snapshot-19), victim constrained as above:
 *   10 clients / 20ms pause / 150s -> 4,236,900+ mid-negotiation substream opens,
 *       connection table peaked at 11, heap peaked at 90MB of 128MB, table settled to 0.
 *   20 clients / no pause  / 150s -> 8,087,250 mid-negotiation substream opens,
 *       connection table peaked at 10, heap peaked at 88MB of 128MB, table settled to 0.
 * Neither run exhausted the heap and neither showed the connection table growing.
 */
package io.libp2p.network

import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.protocol.Ping

object ChurnVictim {
    @JvmStatic
    fun main(args: Array<String>) {
        val server = host {
            muxers { +StreamMuxerProtocol.getYamux() }
            network { listen("/ip4/127.0.0.1/tcp/0") }
            protocols { add(Ping()) }
        }
        server.start().get()
        println("READY peerId=${server.peerId.toBase58()} addr=${server.listenAddresses().single()}")
        System.out.flush()
        val runtime = Runtime.getRuntime()
        while (true) {
            Thread.sleep(1000)
            println(
                "STATE connections=${server.network.connections.size} " +
                    "heapUsedMb=${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)}"
            )
            System.out.flush()
        }
    }
}

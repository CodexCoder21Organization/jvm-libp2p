package io.libp2p.etc.util.netty

import io.netty.channel.Channel
import io.netty.util.concurrent.FastThreadLocal
import io.netty.util.concurrent.SingleThreadEventExecutor

private val connectionsOnLoop = object : FastThreadLocal<MutableSet<Channel>>() {
    override fun initialValue(): MutableSet<Channel> = mutableSetOf()
}

internal fun trackConnectionOnLoop(channel: Channel) {
    val loop = channel.eventLoop()
    if (loop !is SingleThreadEventExecutor) return
    check(loop.inEventLoop()) { "Connection registration must run on its event loop: $channel" }

    val connections = connectionsOnLoop.get()
    if (connections.add(channel)) {
        channel.closeFuture().addListener { connections.remove(channel) }
    }
}

internal fun hasOtherConnectionsOnLoop(channel: Channel): Boolean {
    val loop = channel.eventLoop()
    if (loop !is SingleThreadEventExecutor) return false
    check(loop.inEventLoop()) { "Connection scheduling must run on its event loop: $channel" }
    return connectionsOnLoop.get().any { it !== channel }
}

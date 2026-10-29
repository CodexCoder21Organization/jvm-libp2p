package io.libp2p.etc.util.netty

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.MessageToMessageCodec

/**
 * Adds/removes trailing character from messages
 */
class StringSuffixCodec(val trailingChar: Char) : MessageToMessageCodec<String, String>(String::class.java, String::class.java) {
    internal companion object Factory {
        fun create(trailingChar: Char) = StringSuffixCodec(trailingChar)
    }

    override fun encode(ctx: ChannelHandlerContext?, msg: String, out: MutableList<Any>) {
        out += (msg + trailingChar)
    }

    override fun decode(ctx: ChannelHandlerContext?, msg: String, out: MutableList<Any>) {
        if (!msg.endsWith(trailingChar)) throw DecoderException("Missing message end character")
        out += msg.substring(0, msg.length - 1)
    }
}

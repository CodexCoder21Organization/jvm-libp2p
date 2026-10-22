package io.libp2p.etc.util.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufHolder
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler

/**
 * [LoggingHandler] limiting message size
 */
class LoggingHandlerShort : LoggingHandler {

    constructor() : super()
    constructor(format: ByteBufFormat?) : super(format)
    constructor(level: LogLevel?) : super(level)
    constructor(level: LogLevel?, byteBufFormat: ByteBufFormat?) : super(level, byteBufFormat)
    constructor(clazz: Class<*>?) : super(clazz)
    constructor(clazz: Class<*>?, level: LogLevel?) : super(clazz, level)
    constructor(clazz: Class<*>?, level: LogLevel?, byteBufFormat: ByteBufFormat?) : super(clazz, level, byteBufFormat)
    constructor(name: String?) : super(name)
    constructor(name: String?, level: LogLevel?) : super(name, level)
    constructor(name: String?, level: LogLevel?, byteBufFormat: ByteBufFormat?) : super(name, level, byteBufFormat)

    var maxHeadingLines: Int = 50
    var maxTrailingLines: Int = 10
    var linesCutThreshold = 30
    var maxLineHeadingChars: Int = 1024
    var maxLineTrailingChars: Int = 128
    var charsCutThreshold = 256

    override fun format(ctx: ChannelHandlerContext?, eventName: String?, arg: Any?): String {
        val buffer = when (arg) {
            is ByteBuf -> arg
            is ByteBufHolder -> arg.content()
            else -> null
        }
        val visibleRows = maxHeadingLines.toLong() + maxTrailingLines + linesCutThreshold
        val orig = if (buffer != null && byteBufFormat() == ByteBufFormat.HEX_DUMP &&
            buffer.readableBytes().toLong() > visibleRows * 16
        ) {
            formatShortBuffer(ctx, eventName, arg, buffer, maxHeadingLines, maxTrailingLines)
        } else {
            super.format(ctx, eventName, arg)
        }
        val lines = orig.lines()
        val extraLines = lines.size - (maxHeadingLines + maxTrailingLines)

        val lessLines =
            if (extraLines > linesCutThreshold) {
                lines.take(maxHeadingLines) +
                    "" +
                    "............. more $extraLines lines ............." +
                    "" +
                    lines.takeLast(maxTrailingLines)
            } else {
                lines
            }
        val shortLines = lessLines.map {
            val extraChars = it.length - (maxLineHeadingChars + maxLineTrailingChars)
            if (extraChars > charsCutThreshold) {
                it.take(maxLineHeadingChars) +
                    " ... more $extraChars chars ... " +
                    it.takeLast(maxLineTrailingChars)
            } else {
                it
            }
        }
        return shortLines.joinToString("\n")
    }
}

// Read only the ranges that can appear in the shortened log. The payload is borrowed:
// formatting must leave its indexes and reference count unchanged.
private fun formatShortBuffer(
    ctx: ChannelHandlerContext?,
    eventName: String?,
    message: Any?,
    buffer: ByteBuf,
    headingRows: Int,
    trailingRows: Int
): String {
    val length = buffer.readableBytes()
    val headingBytes = minOf(length.toLong(), headingRows.toLong() * 16).toInt()
    val trailingBytes = minOf((length - headingBytes).toLong(), trailingRows.toLong() * 16).toInt()
    val start = buffer.readerIndex()
    return buildString {
        append(ctx?.channel()).append(' ').append(eventName).append(": ")
        if (message is ByteBufHolder) append(message).append(", ")
        append(length).append('B')
        if (headingBytes > 0) {
            append('\n').append(ByteBufUtil.prettyHexDump(buffer, start, headingBytes))
        }
        append("\n............. more ").append(length - headingBytes - trailingBytes)
            .append(" bytes .............\n")
        if (trailingBytes > 0) {
            append(ByteBufUtil.prettyHexDump(buffer, start + length - trailingBytes, trailingBytes))
        }
    }
}

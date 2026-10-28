package io.libp2p.etc.util.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufHolder
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.logging.ByteBufFormat
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.ArrayDeque

/**
 * [LoggingHandler] limiting message size. Hex dumps inspect only the displayed payload rows
 * and preserve the source buffer's contents, indices and reference count.
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
        if (buffer != null && byteBufFormat() == ByteBufFormat.HEX_DUMP && buffer.isReadable) {
            return shortBufferLog(ctx!!, eventName, arg, buffer, this)
        }
        return shortTextLog(super.format(ctx, eventName, arg), this)
    }
}

private class ShortLogLine(val text: String, val start: Int = 0, val end: Int = text.length)

private fun shortLogLine(line: ShortLogLine, logger: LoggingHandlerShort): String {
    val extra = line.end.toLong() - line.start - logger.maxLineHeadingChars - logger.maxLineTrailingChars
    return if (extra > logger.charsCutThreshold) {
        require(logger.maxLineHeadingChars >= 0 && logger.maxLineTrailingChars >= 0)
        val headingEnd = minOf(line.end.toLong(), line.start.toLong() + logger.maxLineHeadingChars).toInt()
        val trailingStart = maxOf(line.start.toLong(), line.end.toLong() - logger.maxLineTrailingChars).toInt()
        line.text.substring(line.start, headingEnd) +
            " ... more $extra chars ... " +
            line.text.substring(trailingStart, line.end)
    } else {
        line.text.substring(line.start, line.end)
    }
}

private fun shortLogLines(
    count: Int,
    logger: LoggingHandlerShort,
    lineAt: (Int) -> ShortLogLine
): String {
    val extra = count.toLong() - logger.maxHeadingLines - logger.maxTrailingLines
    val lines = mutableListOf<String>()
    if (extra > logger.linesCutThreshold) {
        require(logger.maxHeadingLines >= 0 && logger.maxTrailingLines >= 0)
        repeat(minOf(count, logger.maxHeadingLines)) { lines += shortLogLine(lineAt(it), logger) }
        lines += shortLogLine(ShortLogLine(""), logger)
        lines += shortLogLine(ShortLogLine("............. more $extra lines ............."), logger)
        lines += shortLogLine(ShortLogLine(""), logger)
        for (index in maxOf(0, count - logger.maxTrailingLines) until count) {
            lines += shortLogLine(lineAt(index), logger)
        }
    } else {
        repeat(count) { lines += shortLogLine(lineAt(it), logger) }
    }
    return lines.joinToString("\n")
}

private fun shortTextLog(text: String, logger: LoggingHandlerShort): String {
    val first = mutableListOf<ShortLogLine>()
    val last = ArrayDeque<ShortLogLine>()
    val firstLimit = logger.maxHeadingLines.toLong() + logger.maxTrailingLines + maxOf(0, logger.linesCutThreshold)
    var count = 0
    var start = 0
    var nextLf = text.indexOf('\n')
    var nextCr = text.indexOf('\r')
    while (true) {
        val end = when {
            nextLf < 0 -> if (nextCr < 0) text.length else nextCr
            nextCr < 0 -> nextLf
            else -> minOf(nextLf, nextCr)
        }
        val line = ShortLogLine(text, start, end)
        if (count < firstLimit) first += line
        if (logger.maxTrailingLines > 0) {
            if (last.size == logger.maxTrailingLines) last.removeFirst()
            last.addLast(line)
        }
        count++
        if (end == text.length) break
        start = end + if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') 2 else 1
        // Cache both searches: a log with many LF lines and no CR must not rescan its suffix.
        if (nextLf >= 0 && nextLf < start) nextLf = text.indexOf('\n', start)
        if (nextCr >= 0 && nextCr < start) nextCr = text.indexOf('\r', start)
    }
    val trailing = last.toList()
    return shortLogLines(count, logger) { index ->
        if (index < first.size) first[index] else trailing[index - (count - trailing.size)]
    }
}

private fun shortBufferLog(
    ctx: ChannelHandlerContext,
    eventName: String?,
    arg: Any?,
    buffer: ByteBuf,
    logger: LoggingHandlerShort
): String {
    val length = buffer.readableBytes()
    val rows = (length.toLong() + 15).div(16).toInt()
    val prefix = "${ctx.channel()} $eventName: " +
        (if (arg is ByteBufHolder) "$arg, " else "") + "${length}B"
    // Only split the description; generate the displayed payload rows below.
    val prefixLines = prefix.split("\r\n", "\n", "\r")
    val count = rows + 4 + prefixLines.size
    if (count.toLong() - logger.maxHeadingLines - logger.maxTrailingLines <= logger.linesCutThreshold) {
        return shortTextLog(prefix + "\n" + ByteBufUtil.prettyHexDump(buffer), logger)
    }
    return shortLogLines(count, logger) { index ->
        val tableIndex = index - prefixLines.size
        val text = when {
            tableIndex < 0 -> prefixLines[index]
            tableIndex == 0 -> "         +-------------------------------------------------+"
            tableIndex == 1 -> "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |"
            tableIndex == 2 || tableIndex == rows + 3 -> "+--------+-------------------------------------------------+----------------+"
            else -> {
                val offset = (tableIndex - 3) * 16
                val row = ByteBufUtil.prettyHexDump(buffer, buffer.readerIndex() + offset, minOf(16, length - offset))
                    .split("\r\n", "\n", "\r")[3]
                "|" + offset.toString(16).padStart(8, '0') + row.substring(9)
            }
        }
        ShortLogLine(text)
    }
}

package io.libp2p.etc.util.netty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LogLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class LoggingHandlerShortTest {
  @RepeatedTest(10)
  void outboundByteBufReadsOnlyDisplayedRows() {
    checkDisplayedRows(false, false);
  }

  @RepeatedTest(10)
  void inboundByteBufReadsOnlyDisplayedRows() {
    checkDisplayedRows(true, false);
  }

  @RepeatedTest(10)
  void outboundHolderReadsOnlyDisplayedRows() {
    checkDisplayedRows(false, true);
  }

  @RepeatedTest(10)
  void inboundHolderReadsOnlyDisplayedRows() {
    checkDisplayedRows(true, true);
  }

  @Test
  void keepsExactHexRowsWithANonzeroReaderIndex() {
    String name = "short-hex-" + UUID.randomUUID();
    try (CapturedLog captured = new CapturedLog(name)) {
      LoggingHandlerShort logger = new LoggingHandlerShort(name, LogLevel.INFO);
      logger.setMaxHeadingLines(5);
      logger.setMaxTrailingLines(2);
      logger.setLinesCutThreshold(1);
      ByteBuf buffer = new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, 4099, 4099);
      buffer.writeZero(4099).readerIndex(3);
      EmbeddedChannel channel = new EmbeddedChannel(logger);
      try {
        String prefix = channel + " WRITE: 4096B\n";
        channel.writeOutbound(buffer);
        assertSame(buffer, channel.readOutbound());
        assertEquals(
            prefix
                + "         +-------------------------------------------------+\n"
                + "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |\n"
                + "+--------+-------------------------------------------------+----------------+\n"
                + "|00000000| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|\n"
                + "\n............. more 254 lines .............\n\n"
                + "|00000ff0| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|\n"
                + "+--------+-------------------------------------------------+----------------+",
            captured.write());
      } finally {
        channel.finishAndReleaseAll();
        if (buffer.refCnt() > 0) buffer.release();
      }
    }
  }

  @Test
  void keepsCrLfAndTrailingEmptyLineSemantics() {
    String name = "short-text-" + UUID.randomUUID();
    try (CapturedLog captured = new CapturedLog(name)) {
      LoggingHandlerShort logger = new LoggingHandlerShort(name, LogLevel.INFO);
      logger.setMaxHeadingLines(1);
      logger.setMaxTrailingLines(1);
      logger.setLinesCutThreshold(1);
      EmbeddedChannel channel = new EmbeddedChannel(logger);
      try {
        String prefix = channel + " WRITE: first";
        String text = "first\r\nsecond\rthird\nfourth\n";
        channel.writeOutbound(text);
        assertSame(text, channel.readOutbound());
        assertEquals(prefix + "\n\n............. more 3 lines .............\n\n", captured.write());
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Test
  void countsHolderDescriptionLinesBeforeSelectingPayloadRows() {
    String name = "short-holder-" + UUID.randomUUID();
    try (CapturedLog captured = new CapturedLog(name)) {
      LoggingHandlerShort logger = new LoggingHandlerShort(name, LogLevel.INFO);
      logger.setMaxHeadingLines(5);
      logger.setMaxTrailingLines(2);
      logger.setLinesCutThreshold(1);
      ByteBuf buffer = new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, 4096, 4096);
      buffer.writeZero(4096);
      DefaultByteBufHolder holder =
          new DefaultByteBufHolder(buffer) {
            @Override
            public String toString() {
              return "holder-first\r\nholder-second";
            }
          };
      EmbeddedChannel channel = new EmbeddedChannel(logger);
      try {
        String prefix = channel + " WRITE: holder-first\nholder-second, 4096B\n";
        channel.writeOutbound(holder);
        assertSame(holder, channel.readOutbound());
        assertEquals(
            prefix
                + "         +-------------------------------------------------+\n"
                + "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |\n"
                + "+--------+-------------------------------------------------+----------------+\n"
                + "\n............. more 255 lines .............\n\n"
                + "|00000ff0| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|\n"
                + "+--------+-------------------------------------------------+----------------+",
            captured.write());
      } finally {
        channel.finishAndReleaseAll();
        if (buffer.refCnt() > 0) buffer.release();
      }
    }
  }

  @Test
  void characterCutsKeepOverlappingHeadAndTail() {
    String name = "short-overlap-" + UUID.randomUUID();
    try (CapturedLog captured = new CapturedLog(name)) {
      LoggingHandlerShort logger = new LoggingHandlerShort(name, LogLevel.INFO);
      logger.setMaxLineHeadingChars(10000);
      logger.setMaxLineTrailingChars(2);
      logger.setCharsCutThreshold(-10000);
      EmbeddedChannel channel = new EmbeddedChannel(logger);
      try {
        String full = channel + " WRITE: abc";
        assertDoesNotThrow(() -> channel.writeOutbound("abc"));
        assertEquals("abc", channel.readOutbound());
        assertEquals(
            full + " ... more " + (full.length() - 10002) + " chars ... bc", captured.write());
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  @Test
  void keepsPartialLastHexRow() {
    String name = "short-partial-" + UUID.randomUUID();
    try (CapturedLog captured = new CapturedLog(name)) {
      LoggingHandlerShort logger = new LoggingHandlerShort(name, LogLevel.INFO);
      logger.setMaxHeadingLines(5);
      logger.setMaxTrailingLines(2);
      logger.setLinesCutThreshold(1);
      ByteBuf buffer = new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, 129, 129);
      buffer.writeZero(129);
      EmbeddedChannel channel = new EmbeddedChannel(logger);
      try {
        String prefix = channel + " WRITE: 129B\n";
        channel.writeOutbound(buffer);
        assertSame(buffer, channel.readOutbound());
        assertEquals(
            prefix
                + "         +-------------------------------------------------+\n"
                + "         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |\n"
                + "+--------+-------------------------------------------------+----------------+\n"
                + "|00000000| 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 |................|\n"
                + "\n............. more 7 lines .............\n\n"
                + "|00000080| 00"
                + "   ".repeat(15)
                + " |."
                + " ".repeat(15)
                + "|\n"
                + "+--------+-------------------------------------------------+----------------+",
            captured.write());
      } finally {
        channel.finishAndReleaseAll();
        if (buffer.refCnt() > 0) buffer.release();
      }
    }
  }

  @Test
  void holderDescriptionCanOmitAllPayloadRows() {
    String name = "short-description-" + UUID.randomUUID();
    try (CapturedLog captured = new CapturedLog(name)) {
      LoggingHandlerShort logger = new LoggingHandlerShort(name, LogLevel.INFO);
      logger.setMaxHeadingLines(5);
      logger.setMaxTrailingLines(0);
      logger.setLinesCutThreshold(1);
      CountingBuffer buffer = new CountingBuffer(16);
      buffer.writeZero(16);
      DefaultByteBufHolder holder =
          new DefaultByteBufHolder(buffer) {
            @Override
            public String toString() {
              return "description\n".repeat(6);
            }
          };
      EmbeddedChannel channel = new EmbeddedChannel(logger);
      try {
        channel.writeOutbound(holder);
        assertSame(holder, channel.readOutbound());
        assertEquals(0, buffer.byteReads, "All payload rows are omitted by the description lines");
      } finally {
        channel.finishAndReleaseAll();
        if (buffer.refCnt() > 0) buffer.release();
      }
    }
  }

  private static final class CapturedLog implements AutoCloseable {
    private final List<String> messages = new ArrayList<>();
    private final Logger logger;
    private final AbstractAppender appender;

    CapturedLog(String name) {
      logger = (Logger) LogManager.getLogger(name);
      appender =
          new AbstractAppender(
              name, null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
              messages.add(event.getMessage().getFormattedMessage());
            }
          };
      appender.start();
      logger.addAppender(appender);
      logger.setAdditive(false);
    }

    String write() {
      return messages.stream()
          .filter(message -> message.contains(" WRITE: "))
          .findFirst()
          .orElseThrow(() -> new AssertionError("No write log was emitted"));
    }

    @Override
    public void close() {
      logger.removeAppender(appender);
      logger.setAdditive(true);
      appender.stop();
    }
  }

  private void checkDisplayedRows(boolean inbound, boolean holder) {
    LoggingHandlerShort logger = new LoggingHandlerShort("short-log-test", LogLevel.INFO);
    logger.setMaxHeadingLines(5);
    logger.setMaxTrailingLines(2);
    logger.setLinesCutThreshold(1);
    // The first four lines are headings; the last is the table footer.
    int displayedPayloadBytes = 2 * 16;
    int allowedByteReads = 2 * displayedPayloadBytes; // Hex and ASCII columns.
    CountingBuffer buffer = new CountingBuffer(4096);
    buffer.writeZero(4096);
    Object message = holder ? new DefaultByteBufHolder(buffer) : buffer;
    EmbeddedChannel channel = new EmbeddedChannel(logger);
    try {
      int readerIndex = buffer.readerIndex();
      int writerIndex = buffer.writerIndex();
      if (inbound) {
        channel.writeInbound(message);
        assertSame(message, channel.readInbound());
      } else {
        channel.writeOutbound(message);
        assertSame(message, channel.readOutbound());
      }
      assertEquals(readerIndex, buffer.readerIndex());
      assertEquals(writerIndex, buffer.writerIndex());
      assertEquals(1, buffer.refCnt());
      assertTrue(
          buffer.byteReads <= allowedByteReads,
          "Formatting read "
              + buffer.byteReads
              + " payload bytes, but only "
              + displayedPayloadBytes
              + " bytes are displayed; omitted rows must not be formatted");
      for (int index = 0; index < writerIndex; index++) {
        assertEquals(0, buffer.getByte(index), "Payload changed at byte " + index);
      }
    } finally {
      channel.finishAndReleaseAll();
      if (buffer.refCnt() > 0) buffer.release();
    }
  }

  private static final class CountingBuffer extends UnpooledHeapByteBuf {
    int byteReads;

    CountingBuffer(int size) {
      super(UnpooledByteBufAllocator.DEFAULT, size, size);
    }

    @Override
    protected byte _getByte(int index) {
      byteReads++;
      return super._getByte(index);
    }
  }
}

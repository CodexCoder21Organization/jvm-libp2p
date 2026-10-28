package io.libp2p.etc.util.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LogLevel;
import org.junit.jupiter.api.RepeatedTest;

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
      buffer.release();
      channel.finishAndReleaseAll();
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

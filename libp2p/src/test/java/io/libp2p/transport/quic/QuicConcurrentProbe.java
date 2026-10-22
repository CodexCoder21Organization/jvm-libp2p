package io.libp2p.transport.quic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class QuicConcurrentProbe {
  @Test
  public void concurrentConnectionExceptionCallers() throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(4);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Future<?>> work = new ArrayList<>();
    try {
      for (int caller = 0; caller < 4; caller++) {
        work.add(callers.submit(() -> {
          for (int iteration = 0; iteration < 8; iteration++) {
            try { new QuicServerTestJava().connectionLevelQuicExceptionIsHandledAndCleansUpOutboundResources(); }
            catch (Throwable failure) { failures.add(failure); }
          }
        }));
      }
      for (Future<?> item : work) item.get(240, TimeUnit.SECONDS);
      for (Throwable failure : failures) failure.printStackTrace();
      System.out.println("QUIC_CALLERS completed=32 failures=" + failures.size());
      assertThat(failures).isEmpty();
    } finally {
      callers.shutdownNow();
      assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}

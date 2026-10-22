package io.libp2p.pubsub;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class GossipApiConcurrentProbe {
  @Test
  public void concurrentFastMessageIdCallers() throws Exception {
    ExecutorService callers = Executors.newFixedThreadPool(8);
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    List<Future<?>> work = new ArrayList<>();
    try {
      for (int caller = 0; caller < 8; caller++) {
        work.add(callers.submit(() -> {
          for (int iteration = 0; iteration < 32; iteration++) {
            try { new GossipApiTest().testFastMessageId(); }
            catch (Throwable failure) { failures.add(failure); }
          }
        }));
      }
      for (Future<?> item : work) item.get(90, TimeUnit.SECONDS);
      for (Throwable failure : failures) failure.printStackTrace();
      System.out.println("FAST_ID_CALLERS completed=256 failures=" + failures.size());
      assertThat(failures).isEmpty();
    } finally {
      callers.shutdownNow();
      assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}

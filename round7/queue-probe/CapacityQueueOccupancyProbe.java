import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.*;
import java.util.concurrent.*;

public final class CapacityQueueOccupancyProbe {
  public static void main(String[] args) throws Exception {
    MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(
        1, new DefaultThreadFactory("queue-occupancy-probe", true), NioIoHandler.newFactory());
    EventLoop loop = group.next();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    CompletableFuture<Void> queuedBarrier = new CompletableFuture<>();
    try {
      loop.execute(() -> {
        entered.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Release missing");
        } catch (InterruptedException failure) {
          throw new RuntimeException(failure);
        }
      });
      if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owner hold missing");
      // A normal queued completion barrier is enough; no work or delay is manufactured.
      loop.execute(() -> queuedBarrier.complete(null));
      int acceptedFillers = 0;
      RejectedExecutionException rejection = null;
      for (int i = 0; i < 2048; i++) {
        try {
          loop.execute(() -> {});
          acceptedFillers++;
        } catch (RejectedExecutionException failure) {
          rejection = failure;
          break;
        }
      }
      int pending = ((SingleThreadEventExecutor) loop).pendingTasks();
      System.out.println("acceptedFillers=" + acceptedFillers + " requestedFillers=2048"
          + " queuedCompletionBarriers=1 pendingTasks=" + pending
          + " ownerShuttingDown=" + loop.isShuttingDown());
      if (rejection == null || acceptedFillers != 2047 || pending != 2048 || loop.isShuttingDown())
        throw new AssertionError("One queued completion must reject the fixture's 2048th filler");
      release.countDown();
      queuedBarrier.get(5, TimeUnit.SECONDS);
      loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      group.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
    }
  }
}

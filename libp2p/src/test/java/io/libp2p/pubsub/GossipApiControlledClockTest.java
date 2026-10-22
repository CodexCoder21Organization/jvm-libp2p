package io.libp2p.pubsub;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

public class GossipApiControlledClockTest {
  @Test
  public void fastMessageIdScenarioDoesNotStartWallClockWorkers() throws Exception {
    Path recordingFile = Files.createTempFile("gossip-api-clock-", ".jfr");
    try (Recording recording = new Recording()) {
      recording.enable("jdk.ThreadStart").withoutStackTrace();
      recording.start();
      Exception scenarioFailure = null;
      try {
        new GossipApiTest().testFastMessageId();
      } catch (Exception failure) {
        scenarioFailure = failure;
      } finally {
        recording.stop();
      }
      recording.dump(recordingFile);
      List<String> workers = new ArrayList<>();
      for (RecordedEvent event : RecordingFile.readAllEvents(recordingFile)) {
        if (event.getEventType().getName().equals("jdk.ThreadStart")) {
          String name = event.getThread("thread").getJavaName();
          if (name.startsWith("GossipRouter-event-thread-")) workers.add(name);
        }
      }
      assertThat(workers)
          .as("the fast-message-ID scenario must use its controlled executor")
          .isEmpty();
      assertThat(scenarioFailure).isNull();
    } finally {
      Files.deleteIfExists(recordingFile);
    }
  }
}

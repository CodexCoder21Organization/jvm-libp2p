package io.libp2p.security.noise;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test-only agent observing real class definitions without changing their bytes. */
public final class NoiseClassLoadObserver {
  private static final Object LOCK = new Object();
  private static boolean attached;
  private static boolean preparationObserved;
  private static List<String> recording;
  private static Throwable observationFailure;

  public static void premain(String arguments, Instrumentation instrumentation) {
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> redefined,
              ProtectionDomain domain,
              byte[] bytes) {
            try {
              synchronized (LOCK) {
                if ("com/southernstorm/noise/protocol/HandshakeState".equals(name)) {
                  preparationObserved = true;
                }
                if (recording != null && name != null) {
                  recording.add(name.replace('/', '.'));
                }
              }
            } catch (Throwable failure) {
              // The JVM ignores transformer exceptions. Retain the cause so begin/end
              // fail the probe instead of reporting a possibly incomplete observation.
              synchronized (LOCK) {
                if (observationFailure == null) {
                  observationFailure = failure;
                } else if (observationFailure != failure) {
                  observationFailure.addSuppressed(failure);
                }
              }
            }
            return null;
          }
        });
    synchronized (LOCK) {
      attached = true;
    }
  }

  public static void begin() {
    List<String> next = new ArrayList<>();
    synchronized (LOCK) {
      if (observationFailure != null) {
        throw new IllegalStateException("Class-load observation failed", observationFailure);
      }
      if (!attached || !preparationObserved) {
        throw new IllegalStateException(
            "Class-load observer did not record Noise preparation before the first stream");
      }
      if (recording != null) {
        throw new IllegalStateException("Class-load observation is already running");
      }
      recording = next;
    }
  }

  public static void close() {
    synchronized (LOCK) {
      recording = null;
    }
  }

  public static List<String> end() {
    List<String> completed;
    synchronized (LOCK) {
      if (recording == null) {
        throw new IllegalStateException("Class-load observation is not running");
      }
      completed = recording;
      recording = null;
      if (observationFailure != null) {
        throw new IllegalStateException("Class-load observation failed", observationFailure);
      }
    }
    return Collections.unmodifiableList(completed);
  }
}

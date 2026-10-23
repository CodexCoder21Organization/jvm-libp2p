package io.libp2p.crypto;

import java.security.Provider;
import java.security.SecureRandomSpi;

/** A JCA provider that makes accidental use of the platform-default SecureRandom fail loudly. */
public final class PoisonedDefaultSecureRandomProvider extends Provider {
  private static final long serialVersionUID = 1L;

  public PoisonedDefaultSecureRandomProvider() {
    super(
        "PoisonedDefaultSecureRandom",
        "1.0",
        "Rejects default SecureRandom use in regression tests");
    put("SecureRandom.PoisonedDefault", PoisonedSecureRandomSpi.class.getName());
  }

  public static final class PoisonedSecureRandomSpi extends SecureRandomSpi {
    private static final long serialVersionUID = 1L;
    private static final String MESSAGE =
        "default new SecureRandom() was used on a startup/key-generation path; "
            + "use nonBlockingSecureRandom() — see SecureRandom.kt";

    @Override
    protected void engineSetSeed(byte[] seed) {
      throw new IllegalStateException(MESSAGE);
    }

    @Override
    protected void engineNextBytes(byte[] bytes) {
      throw new IllegalStateException(MESSAGE);
    }

    @Override
    protected byte[] engineGenerateSeed(int numBytes) {
      throw new IllegalStateException(MESSAGE);
    }
  }
}

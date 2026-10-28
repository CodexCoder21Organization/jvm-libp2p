package io.libp2p.tools;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;

/** Keeps an old fixture endpoint unavailable without requiring it to be free first. */
public final class LegacyPortGuard {
  private LegacyPortGuard() {}

  public static ServerSocket holdIfFree(int port) throws IOException {
    try {
      ServerSocket socket = new ServerSocket(port);
      System.out.println("Legacy TCP port " + port + ": held by this guard");
      return socket;
    } catch (BindException failure) {
      // Only an existing owner satisfies the guard's precondition. Other bind failures must fail.
      if (!"Address already in use".equals(failure.getMessage())
          && !"Address already in use: bind".equals(failure.getMessage())) {
        throw failure;
      }
      System.out.println("Legacy TCP port " + port + ": already held by another owner");
      return null;
    }
  }
}

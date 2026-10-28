package io.libp2p.tools.p2pd;

import com.google.protobuf.ByteString;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.Protocol;
import io.libp2p.tools.p2pd.libp2pj.Muxer.MuxerAdress;
import io.libp2p.tools.p2pd.libp2pj.StreamHandler;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import p2pd.pb.P2Pd;

class P2PDPortAllocationTest {
  @Test
  void callbackDoesNotUseCounterSelectedPort() throws Exception {
    try (ServerSocket occupied = new ServerSocket(46667)) {
      checkCallback(false);
    }
  }

  @Test
  void callbackAdvertisesTheEndpointItOwns() throws Exception {
    try (ServerSocket occupied = new ServerSocket(46666)) {
      Thread wrongOwner = new Thread(() -> {
        try (Socket connection = occupied.accept()) {
          P2Pd.StreamInfo.parseDelimitedFrom(connection.getInputStream());
          connection.getOutputStream().write("wrongown".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          connection.getOutputStream().flush();
          connection.getInputStream().read();
        } catch (java.net.SocketException failure) {
          // The corrected fixture never contacts this endpoint; closing the guard ends accept().
          if (!occupied.isClosed()) throw new AssertionError(failure);
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      }, "legacy-callback-owner");
      wrongOwner.setDaemon(true);
      wrongOwner.start();
      try {
        checkCallback(false);
      } finally {
        occupied.close();
        wrongOwner.join(5000);
        Assertions.assertFalse(wrongOwner.isAlive(), "Legacy endpoint owner must terminate");
      }
    }
  }

  @Test
  void failedRegistrationReleasesTheCallbackListener() throws Exception {
    checkCallback(true);
  }

  private void checkCallback(boolean reject) throws Exception {
    // A local wire peer implements STREAM_HANDLER registration. No external p2pd is required.
    try (ServerSocket control = new ServerSocket(0)) {
      CompletableFuture<Multiaddr> registered = new CompletableFuture<>();
      CompletableFuture<Void> peerDone = new CompletableFuture<>();
      Thread peer = new Thread(() -> {
        try (Socket connection = control.accept()) {
          P2Pd.Request request = P2Pd.Request.parseDelimitedFrom(connection.getInputStream());
          Assertions.assertEquals(P2Pd.Request.Type.STREAM_HANDLER, request.getType());
          registered.complete(Multiaddr.deserialize(request.getStreamHandler().getAddr().toByteArray()));
          P2Pd.Response.Builder response = P2Pd.Response.newBuilder()
              .setType(reject ? P2Pd.Response.Type.ERROR : P2Pd.Response.Type.OK);
          if (reject) response.setError(P2Pd.ErrorResponse.newBuilder().setMsg("registration rejected"));
          response.build().writeDelimitedTo(connection.getOutputStream());
          peerDone.complete(null);
        } catch (Throwable failure) {
          registered.completeExceptionally(failure);
          peerDone.completeExceptionally(failure);
        }
      }, "p2pd-registration-peer");
      peer.setDaemon(true);
      peer.start();
      P2PDHost host = new P2PDHost(new InetSocketAddress("127.0.0.1", control.getLocalPort()));
      CompletableFuture<Void> callback = new CompletableFuture<>();
      Closeable listener = null;
      try {
        CompletableFuture<Closeable> registration = host.listen(
            MuxerAdress.listenAddress("/port-isolation/1"),
            () -> new StreamHandler<MuxerAdress>() {
              @Override
              public void onCreate(io.libp2p.tools.p2pd.libp2pj.Stream<MuxerAdress> stream) {
                stream.write(java.nio.ByteBuffer.wrap("callback".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                stream.flush();
                callback.complete(null);
              }
              @Override
              public void onRead(java.nio.ByteBuffer data) {}
              @Override
              public void onClose() {}
              @Override
              public void onError(Throwable failure) { callback.completeExceptionally(failure); }
            });
        if (reject) {
          Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
              () -> registration.get(5, TimeUnit.SECONDS));
        } else {
          listener = registration.get(5, TimeUnit.SECONDS);
        }
        Multiaddr address = registered.get(5, TimeUnit.SECONDS);
        int port = Integer.parseInt(address.getFirstComponent(Protocol.TCP).getStringValue());
        Assertions.assertNotEquals(0, port);
        if (reject) {
          try (ServerSocket released = new ServerSocket(port)) {
            Assertions.assertEquals(port, released.getLocalPort());
          }
        } else {
          try (Socket incoming = new Socket("127.0.0.1", port)) {
            incoming.setSoTimeout(5000);
            Assertions.assertEquals(port, incoming.getPort(), "Advertised port must equal the actual callback connection's server port");
            P2Pd.StreamInfo.newBuilder().setPeer(ByteString.copyFrom(new byte[32]))
                .setAddr(ByteString.copyFrom(address.serialize())).setProto("/port-isolation/1")
                .build().writeDelimitedTo(incoming.getOutputStream());
            Assertions.assertEquals("callback", new String(incoming.getInputStream().readNBytes(8),
                java.nio.charset.StandardCharsets.UTF_8), "Advertised endpoint must reach this host's callback listener");
            callback.get(5, TimeUnit.SECONDS);
          }
        }
        peerDone.get(5, TimeUnit.SECONDS);
      } finally {
        if (listener != null) listener.close();
        host.close();
        control.close();
        peer.join(5000);
        Assertions.assertFalse(peer.isAlive(), "Registration peer must terminate");
      }
    }
  }
}

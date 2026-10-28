package io.libp2p.pubsub;

import static io.libp2p.tools.StubsKt.peerHandlerStub;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.libp2p.core.pubsub.ValidationResult;
import io.libp2p.etc.types.WBytes;
import io.libp2p.etc.util.P2PService;
import io.libp2p.pubsub.gossip.GossipParams;
import io.libp2p.pubsub.gossip.GossipParamsKt;
import io.libp2p.pubsub.gossip.GossipRouter;
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pubsub.pb.Rpc;

public class GossipApiTest {

  @Test
  public void createGossipTest() {
    GossipParams gossipParams = GossipParams.builder().D(10).DHigh(20).build();
    GossipRouterBuilder routerBuilder = new GossipRouterBuilder();
    routerBuilder.setParams(gossipParams);
    GossipRouter router = routerBuilder.build();
    assertThat(router.getParams().getD()).isEqualTo(10);
    assertThat(router.getParams().getDHigh()).isEqualTo(20);
    assertThat(router.getParams().getDScore()).isEqualTo(GossipParamsKt.defaultDScore(10));
  }

  @Test
  public void testFastMessageId() throws Exception {
    List<TestPubsubMessage> createdMessages = new ArrayList<>();

    GossipRouterBuilder routerBuilder = new GossipRouterBuilder();
    routerBuilder.setSeenCache(new FastIdSeenCache<>(msg -> msg.getProtobufMessage().getData()));
    routerBuilder.setMessageFactory(
        m -> {
          TestPubsubMessage message = new TestPubsubMessage(m);
          createdMessages.add(message);
          return message;
        });
    GossipRouter router = routerBuilder.build();
    router.subscribe("topic");

    BlockingQueue<PubsubMessage> messages = new LinkedBlockingQueue<>();
    router.initHandler(
        m -> {
          messages.add(m);
          return CompletableFuture.completedFuture(ValidationResult.Valid);
        });

    P2PService.PeerHandler peerHandler = peerHandlerStub(router);

    List<TestPubsubMessage> delivered = new ArrayList<>();
    List<WBytes> canonicalIds = new ArrayList<>();
    List<Integer> createdCounts = new ArrayList<>();
    Rpc.RPC firstDelivery = newMessage("Hello-1");
    Rpc.RPC duplicateDelivery = newMessage("Hello-1");

    try {
      // An empty delivery queue does not acknowledge a queued inbound task.
      // Inspect each message only after onInbound returns on the router event thread.
      router
          .submitOnEventThread(
              () -> {
                router.onInbound(peerHandler, firstDelivery);
                TestPubsubMessage message1 = (TestPubsubMessage) messages.poll();

                delivered.add(message1);
                canonicalIds.add(message1 == null ? null : message1.canonicalId);
                createdCounts.add(createdMessages.size());
                createdMessages.clear();
                return null;
              })
          .thenCompose(
              unused ->
                  router.submitOnEventThread(
                      () -> {
                        router.onInbound(peerHandler, duplicateDelivery);
                        TestPubsubMessage message2 = (TestPubsubMessage) messages.poll();

                        delivered.add(message2);
                        createdCounts.add(createdMessages.size());
                        canonicalIds.add(
                            createdMessages.isEmpty() ? null : createdMessages.get(0).canonicalId);
                        createdMessages.clear();
                        return null;
                      }))
          .get(1, TimeUnit.SECONDS);
      // Assertion-library setup is outside the router completion wait.
      assertThat(delivered.get(0)).isNotNull();
      assertThat(canonicalIds.get(0)).isNotNull();
      assertThat(createdCounts.get(0)).isEqualTo(1);
      assertThat(delivered.get(1)).isNull();
      assertThat(createdCounts.get(1)).isEqualTo(1);
      // The duplicate is filtered by fastId without calculating its canonicalId.
      assertThat(canonicalIds.get(1)).isNull();
    } finally {
      routerBuilder.getScheduledAsyncExecutor().shutdownNow();
    }
  }

  private static Rpc.RPC newMessage(String msg) {
    return Rpc.RPC
        .newBuilder()
        .addPublish(
            Rpc.Message.newBuilder()
                .addTopicIDs("topic")
                .setData(ByteString.copyFrom(msg, StandardCharsets.US_ASCII)))
        .build();
  }

  private static class TestPubsubMessage implements PubsubMessage {
    final Rpc.Message message;
    Function<Rpc.Message, WBytes> canonicalIdCalculator =
        m -> new WBytes(("canon-" + m.getData().toString()).getBytes());
    WBytes canonicalId = null;

    public TestPubsubMessage(Rpc.Message message) {
      this.message = message;
    }

    @NotNull
    @Override
    public Rpc.Message getProtobufMessage() {
      return message;
    }

    @NotNull
    @Override
    public WBytes getMessageId() {
      if (canonicalId == null) {
        canonicalId = canonicalIdCalculator.apply(getProtobufMessage());
      }
      return canonicalId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      TestPubsubMessage that = (TestPubsubMessage) o;
      return message.equals(that.message);
    }

    @Override
    public int hashCode() {
      // Equal protobuf messages have equal data; hashing needs no descriptor table.
      return message.getData().hashCode();
    }
  }
}

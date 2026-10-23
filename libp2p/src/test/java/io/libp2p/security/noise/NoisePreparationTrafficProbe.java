package io.libp2p.security.noise;

import io.libp2p.core.*;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.dsl.Builder;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.protocol.Ping;
import io.libp2p.protocol.PingController;
import io.libp2p.security.plaintext.PlaintextInsecureChannel;
import io.libp2p.transport.implementation.P2PChannelOverNetty;
import io.netty.channel.EventLoop;
import kotlin.Unit;
import java.security.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class NoisePreparationTrafficProbe {
    private static final CountDownLatch entered = new CountDownLatch(1);
    private static final CountDownLatch release = new CountDownLatch(1);
    private static final AtomicBoolean firstDigest = new AtomicBoolean(true);
    private static final AtomicReference<Thread> preparationThread = new AtomicReference<>();
    private static final AtomicBoolean onLiveLoop = new AtomicBoolean();
    private static volatile EventLoop liveLoop;

    private static final class Digest extends MessageDigest {
        private final MessageDigest delegate;
        Digest() throws NoSuchAlgorithmException, NoSuchProviderException {
            super("SHA-256");
            delegate = MessageDigest.getInstance("SHA-256", "SUN");
            if (firstDigest.compareAndSet(true, false)) {
                preparationThread.set(Thread.currentThread());
                onLiveLoop.set(liveLoop.inEventLoop());
                new Exception("First prepared SHA-256 allocation").printStackTrace(System.out);
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new ProviderException("Probe release was not received within 10 seconds");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProviderException("Probe digest allocation was interrupted", e);
                }
            }
        }
        @Override protected int engineGetDigestLength() { return 32; }
        @Override protected void engineUpdate(byte x) { delegate.update(x); }
        @Override protected void engineUpdate(byte[] b, int o, int n) { delegate.update(b, o, n); }
        @Override protected byte[] engineDigest() { return delegate.digest(); }
        @Override protected void engineReset() { delegate.reset(); }
    }

    private static Host host(boolean plaintext, boolean listen) {
        Builder builder = new Builder();
        builder.identity(i -> { i.random(KeyType.ED25519); return Unit.INSTANCE; });
        builder.protocols(p -> { p.add(new Ping()); return Unit.INSTANCE; });
        if (plaintext) builder.secureChannels(s -> { s.add((key, muxers) -> new PlaintextInsecureChannel(key)); return Unit.INSTANCE; });
        if (listen) builder.network(n -> { n.listen("/ip4/127.0.0.1/tcp/0"); return Unit.INSTANCE; });
        return builder.build(Builder.Defaults.Standard);
    }

    public static void main(String[] args) throws Exception {
        Host client = null;
        Host server = null;
        AtomicReference<Host> noiseHost = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Host> building = null;
        CompletableFuture<Void> starting = null;
        Provider provider = new Provider("NoisePreparationGatedSHA256", "1.0", "Real SHA-256 with bounded initialization gate") {
            {
                putService(new Service(this, "MessageDigest", "SHA-256", Digest.class.getName(), null, null) {
                    @Override public Object newInstance(Object parameter) throws NoSuchAlgorithmException {
                        try { return new Digest(); }
                        catch (NoSuchProviderException e) { throw new NoSuchAlgorithmException("SUN SHA-256 is required by this isolated probe", e); }
                    }
                });
            }
        };
        try {
            server = host(true, true);
            client = host(true, false);
            server.start().get(10, TimeUnit.SECONDS);
            client.start().get(10, TimeUnit.SECONDS);
            StreamPromise<PingController> stream = client.newStream(List.of("/ipfs/ping/1.0.0"), server.getPeerId(), server.listenAddresses().toArray(new Multiaddr[0]));
            PingController ping = stream.getController().get(10, TimeUnit.SECONDS);
            ping.ping().get(10, TimeUnit.SECONDS);
            liveLoop = ((P2PChannelOverNetty)client.getNetwork().getConnections().get(0)).getNettyChannel().eventLoop();
            Security.insertProviderAt(provider, 1);
            Callable<Host> createNoise = () -> { Host h = host(false, true); noiseHost.set(h); return h; };
            boolean inline = args.length > 0 && args[0].equals("inline");
            building = inline ? liveLoop.submit(createNoise) : executor.submit(createNoise);
            if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("Noise preparation did not request the scoped digest");
            try {
                CompletableFuture<Void> liveMarker = new CompletableFuture<>();
                liveLoop.execute(() -> liveMarker.complete(null));
                liveMarker.get(1, TimeUnit.SECONDS);
                for (int i = 0; i < 3; i++) ping.ping().get(1, TimeUnit.SECONDS);
                if (onLiveLoop.get()) throw new AssertionError("Noise preparation ran on the live I/O worker");
                if (inline) {
                    Host pending = building.get(1, TimeUnit.SECONDS);
                    starting = pending.start();
                    if (starting.isDone()) throw new AssertionError("Noise start completed before preparation release");
                    if (!pending.listenAddresses().isEmpty()) throw new AssertionError("Noise listener published before preparation release");
                } else if (building.isDone()) {
                    throw new AssertionError("Noise builder returned before preparation release");
                }
            } catch (TimeoutException failure) {
                throw new AssertionError("Live plaintext Ping or event-loop callback stalled during Noise preparation", failure);
            } finally {
                release.countDown();
            }
            Host prepared = building.get(10, TimeUnit.SECONDS);
            if (starting == null) starting = prepared.start();
            starting.get(10, TimeUnit.SECONDS);
            ping.ping().get(10, TimeUnit.SECONDS);
            System.out.println("PLAINTEXT_PING_COMPLETED_WHILE_NOISE_PREPARATION_HELD=" + !onLiveLoop.get());
        } finally {
            release.countDown();
            if (building != null) building.get(10, TimeUnit.SECONDS);
            Security.removeProvider(provider.getName());
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            try { if (noiseHost.get() != null) noiseHost.get().stop().get(10, TimeUnit.SECONDS); }
            finally {
                try { if (client != null) client.stop().get(10, TimeUnit.SECONDS); }
                finally { if (server != null) server.stop().get(10, TimeUnit.SECONDS); }
            }
        }
    }
}

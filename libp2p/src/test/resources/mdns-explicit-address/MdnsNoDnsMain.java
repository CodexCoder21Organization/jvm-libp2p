package mdnsprobe;

import io.libp2p.core.Host;
import io.libp2p.core.dsl.Builder;
import io.libp2p.discovery.MDnsDiscovery;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;

public final class MdnsNoDnsMain {
    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("mdnsprobe.mode");
        Builder builder = new Builder();
        builder.network(network -> {
            network.listen("/ip4/127.0.0.1/tcp/0");
            return Unit.INSTANCE;
        });
        Host host = builder.build(Builder.Defaults.Standard);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            if (mode.equals("forward-start")) {
                host.start().get(5, TimeUnit.SECONDS);
                ControlledDns.checkingMdns = true;
                MDnsDiscovery discovery = new MDnsDiscovery(
                        host, "_mdns-explicit-address-test._udp.local.", 120,
                        InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
                try {
                    discovery.start().get(5, TimeUnit.SECONDS);
                    if (ControlledDns.forwardCalls.get() != 0 || ControlledDns.reverseCalls.get() != 0) {
                        throw new AssertionError("mDNS start consulted system DNS");
                    }
                } finally {
                    discovery.stop().get(5, TimeUnit.SECONDS);
                }
            } else {
                ControlledDns.checkingMdns = true;
                List<Future<?>> results = new ArrayList<>();
                for (int call = 0; call < 10; call++) {
                    results.add(callers.submit(() -> {
                        byte[] bytes = mode.endsWith("ipv6")
                                ? new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
                                : new byte[] {127, 0, 0, 1};
                        try {
                            new MDnsDiscovery(host, "_mdns-explicit-address-test._udp.local.",
                                    120, InetAddress.getByAddress(bytes));
                        } catch (java.net.UnknownHostException error) {
                            throw new IllegalStateException(error);
                        }
                    }));
                }
                for (Future<?> result : results) {
                    result.get(5, TimeUnit.SECONDS);
                }
            }
            if (ControlledDns.forwardCalls.get() != 0 || ControlledDns.reverseCalls.get() != 0) {
                throw new AssertionError("Explicit-address mDNS consulted DNS: forward="
                        + ControlledDns.forwardCalls + ", reverse=" + ControlledDns.reverseCalls);
            }
            System.out.println("MDNS_NO_DNS_OK " + mode + " forward=0 reverse=0");
            try {
                InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 99}).getHostName();
                throw new AssertionError("The controlled DNS provider was not installed");
            } catch (AssertionError expected) {
                if (!"Explicit-address mDNS must not perform reverse DNS".equals(expected.getMessage())) {
                    throw expected;
                }
            }
            if (ControlledDns.reverseCalls.get() != 1) {
                throw new AssertionError("Expected one resolver control query after mDNS: "
                        + ControlledDns.reverseCalls);
            }
            System.out.println("MDNS_DNS_PROVIDER_OK");
        } finally {
            callers.shutdownNow();
            if (!callers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("mDNS constructor callers did not terminate");
            }
            host.stop().get(5, TimeUnit.SECONDS);
        }
    }
}

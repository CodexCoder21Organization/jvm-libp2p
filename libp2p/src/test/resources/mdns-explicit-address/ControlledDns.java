package mdnsprobe;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class ControlledDns extends InetAddressResolverProvider {
    static final AtomicInteger forwardCalls = new AtomicInteger();
    static final AtomicInteger reverseCalls = new AtomicInteger();
    static volatile boolean checkingMdns;

    @Override public String name() { return "mdns-explicit-address-test"; }

    @Override public InetAddressResolver get(Configuration configuration) {
        return new InetAddressResolver() {
            @Override public Stream<InetAddress> lookupByName(String host, LookupPolicy policy)
                    throws UnknownHostException {
                String mode = System.getProperty("mdnsprobe.mode");
                if (!checkingMdns && mode.startsWith("forward")) {
                    // Logging may ask for a hostname while the real host is being built. Do not
                    // seed a successful local-host cache for the forward-lookup scenario.
                    throw new UnknownHostException("Hostname is unavailable during host setup");
                }
                if (checkingMdns) {
                    forwardCalls.incrementAndGet();
                }
                if (checkingMdns && mode.startsWith("forward")) {
                    throw new AssertionError("Explicit-address mDNS must not resolve the machine hostname");
                }
                return Stream.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
            }

            @Override public String lookupByAddress(byte[] address) {
                if (checkingMdns) {
                    reverseCalls.incrementAndGet();
                    throw new AssertionError("Explicit-address mDNS must not perform reverse DNS");
                }
                return "mdns-probe";
            }
        };
    }
}

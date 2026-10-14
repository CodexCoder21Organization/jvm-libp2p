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

    @Override public String name() { return "mdns-explicit-address-test"; }

    @Override public InetAddressResolver get(Configuration configuration) {
        return new InetAddressResolver() {
            @Override public Stream<InetAddress> lookupByName(String host, LookupPolicy policy)
                    throws UnknownHostException {
                forwardCalls.incrementAndGet();
                if (System.getProperty("mdnsprobe.mode").startsWith("forward")) {
                    throw new AssertionError("Explicit-address mDNS must not resolve the machine hostname");
                }
                return Stream.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
            }

            @Override public String lookupByAddress(byte[] address) {
                reverseCalls.incrementAndGet();
                throw new AssertionError("Explicit-address mDNS must not perform reverse DNS");
            }
        };
    }
}

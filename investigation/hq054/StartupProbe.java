import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.security.Security;
public final class StartupProbe {
    public static void main(String[] args) throws Exception {
        long mainEntry = System.nanoTime();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        System.out.println("MAIN_ENTERED uptimeMillis=" + uptime);
        long begin = System.nanoTime();
        String provider = Security.getProviders()[0].getName();
        System.out.println("REGISTRY_READY firstProvider=" + provider + " elapsedNanos=" + (System.nanoTime() - begin));
        System.out.println("MAIN_DONE elapsedNanos=" + (System.nanoTime() - mainEntry));
    }
}

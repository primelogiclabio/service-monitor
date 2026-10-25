package io.primelogiclab.svcmon.hub.checks;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.Status;
import org.springframework.stereotype.Component;

/**
 * ICMP ping through the operating system's ping command. Java's InetAddress.isReachable falls back to
 * TCP port 7 without privileges, so it is not a reliable ping. The host is validated before it reaches the
 * command line so it can never be read as an option.
 */
@Component
public class PingChecker implements Checker {

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.:%_-]*[A-Za-z0-9])?$");
    private static final Pattern TIME = Pattern.compile("(?i)(?:time|zeit|temps)\\s*[=<]\\s*([0-9]+(?:[.,][0-9]+)?)");

    private enum Os { WINDOWS, MAC, OTHER }

    private static final Os OS = detectOs();

    @Override
    public CheckType type() {
        return CheckType.PING;
    }

    public static boolean validHost(String host) {
        return host != null && host.length() <= 253 && HOST.matcher(host).matches();
    }

    @Override
    public CheckResult check(CheckDef def) {
        String host = def.target() == null ? null : def.target().trim();
        if (!validHost(host)) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, "Invalid host");
        }
        List<String> cmd = command(host, def.timeoutMs());
        long start = System.nanoTime();
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(def.timeoutMs() + 3000L, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return CheckResult.of(def.id(), Status.DOWN, null, null, "Timed out after " + def.timeoutMs() + " ms");
            }
            String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset());
            double wall = (System.nanoTime() - start) / 1_000_000.0;
            boolean replied = p.exitValue() == 0;
            if (OS == Os.WINDOWS) {
                // Windows returns 0 for "Destination host unreachable" replies from a router; a real reply has TTL=.
                replied = replied && out.toUpperCase(Locale.ROOT).contains("TTL=");
            }
            if (!replied) {
                return CheckResult.of(def.id(), Status.DOWN, null, null, "No reply from " + host);
            }
            Double rtt = parseRtt(out);
            return CheckResult.of(def.id(), Status.UP, rtt != null ? rtt : wall, null, "Reply from " + host);
        } catch (IOException e) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, "ping command failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return CheckResult.of(def.id(), Status.DOWN, null, null, "Interrupted");
        }
    }

    static Double parseRtt(String output) {
        Matcher m = TIME.matcher(output);
        if (m.find()) {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        }
        return null;
    }

    private static List<String> command(String host, int timeoutMs) {
        return switch (OS) {
            case WINDOWS -> List.of("ping", "-n", "1", "-w", String.valueOf(timeoutMs), host);
            case MAC -> List.of("ping", "-c", "1", "-W", String.valueOf(timeoutMs), host);
            default -> List.of("ping", "-c", "1", "-W", String.valueOf(Math.max(1, (timeoutMs + 999) / 1000)), host);
        };
    }

    private static Os detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return Os.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return Os.MAC;
        }
        return Os.OTHER;
    }
}

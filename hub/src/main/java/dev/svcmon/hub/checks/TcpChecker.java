package dev.svcmon.hub.checks;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckResult;
import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.Status;
import org.springframework.stereotype.Component;

/** TCP connect check. Target is "host:port" (IPv6 as "[::1]:80"). */
@Component
public class TcpChecker implements Checker {

    @Override
    public CheckType type() {
        return CheckType.TCP;
    }

    @Override
    public CheckResult check(CheckDef def) {
        HostPort hp = HostPort.parse(def.target());
        if (hp == null) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, "Target must be host:port");
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hp.host(), hp.port()), def.timeoutMs());
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            return CheckResult.of(def.id(), Status.UP, ms, null, "Connected to " + hp.host() + ":" + hp.port());
        } catch (SocketTimeoutException e) {
            return CheckResult.of(def.id(), Status.DOWN, null, null, "Timed out after " + def.timeoutMs() + " ms");
        } catch (IOException e) {
            String m = e.getMessage();
            return CheckResult.of(def.id(), Status.DOWN, null, null,
                    e.getClass().getSimpleName() + (m == null ? "" : ": " + m));
        }
    }

    public record HostPort(String host, int port) {

        public static HostPort parse(String target) {
            if (target == null) {
                return null;
            }
            String t = target.trim();
            String host;
            String portStr;
            if (t.startsWith("[")) {
                int end = t.indexOf(']');
                if (end < 0 || end + 2 > t.length() || t.charAt(end + 1) != ':') {
                    return null;
                }
                host = t.substring(1, end);
                portStr = t.substring(end + 2);
            } else {
                int colon = t.lastIndexOf(':');
                if (colon <= 0) {
                    return null;
                }
                host = t.substring(0, colon);
                portStr = t.substring(colon + 1);
            }
            try {
                int port = Integer.parseInt(portStr);
                if (host.isBlank() || port < 1 || port > 65535) {
                    return null;
                }
                return new HostPort(host, port);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}

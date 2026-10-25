package io.primelogiclab.svcmon.hub.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.RunFrom;
import io.primelogiclab.svcmon.hub.model.Status;
import org.junit.jupiter.api.Test;

class TcpCheckerTest {

    private final TcpChecker checker = new TcpChecker();

    private static CheckDef def(String target) {
        return new CheckDef("t", "t", CheckType.TCP, target, Map.of(), 60, 2000, 1, RunFrom.HUB, null, true, 0);
    }

    @Test
    void openPortIsUp() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            var r = checker.check(def("127.0.0.1:" + s.getLocalPort()));
            assertEquals(Status.UP, r.status());
            assertNotNull(r.latencyMs());
        }
    }

    @Test
    void closedPortIsDown() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertEquals(Status.DOWN, checker.check(def("127.0.0.1:" + port)).status());
    }

    @Test
    void malformedTargetIsDown() {
        assertEquals(Status.DOWN, checker.check(def("nonsense")).status());
        assertEquals(Status.DOWN, checker.check(def("host:99999")).status());
    }

    @Test
    void hostPortParsing() {
        assertEquals(new TcpChecker.HostPort("example.com", 443), TcpChecker.HostPort.parse("example.com:443"));
        assertEquals(new TcpChecker.HostPort("::1", 80), TcpChecker.HostPort.parse("[::1]:80"));
        assertNull(TcpChecker.HostPort.parse(":80"));
        assertNull(TcpChecker.HostPort.parse("host:0"));
        assertNull(TcpChecker.HostPort.parse("host:abc"));
        assertNull(TcpChecker.HostPort.parse(null));
    }
}

package dev.svcmon.hub.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.Map;

import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckResult;
import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.RunFrom;
import dev.svcmon.hub.model.Status;
import org.junit.jupiter.api.Test;

class PingCheckerTest {

    private final PingChecker checker = new PingChecker();

    private static CheckDef def(String host, int timeoutMs) {
        return new CheckDef("t", "t", CheckType.PING, host, Map.of(), 60, timeoutMs, 1, RunFrom.HUB, null, true, 0);
    }

    @Test
    void hostValidationRejectsOptionsAndShellCharacters() {
        assertTrue(PingChecker.validHost("example.com"));
        assertTrue(PingChecker.validHost("10.0.0.1"));
        assertTrue(PingChecker.validHost("fe80::1"));
        assertFalse(PingChecker.validHost("-f"));
        assertFalse(PingChecker.validHost("-c 1000 host"));
        assertFalse(PingChecker.validHost("host; rm -rf /"));
        assertFalse(PingChecker.validHost("a b"));
        assertFalse(PingChecker.validHost(""));
        assertFalse(PingChecker.validHost(null));
    }

    @Test
    void invalidHostNeverReachesTheCommandLine() {
        CheckResult r = checker.check(def("-f", 1000));
        assertEquals(Status.DOWN, r.status());
        assertEquals("Invalid host", r.message());
    }

    @Test
    void roundTripTimeParsing() {
        assertEquals(1.0, PingChecker.parseRtt("Reply from 127.0.0.1: bytes=32 time<1ms TTL=128"));
        assertEquals(0.045, PingChecker.parseRtt("64 bytes from 127.0.0.1: icmp_seq=1 ttl=64 time=0.045 ms"));
        assertEquals(12.0, PingChecker.parseRtt("Antwort von 10.0.0.1: Bytes=32 Zeit=12ms TTL=64"));
        assertNull(PingChecker.parseRtt("no timing here"));
    }

    @Test
    void loopbackAnswers() {
        CheckResult r = checker.check(def("127.0.0.1", 3000));
        assumeFalse(r.message() != null && r.message().startsWith("ping command failed"),
                "no ping command on this machine");
        assertEquals(Status.UP, r.status(), r.message());
        assertNotNull(r.latencyMs());
    }

    @Test
    void unroutableDocumentationAddressIsDown() {
        CheckResult r = checker.check(def("192.0.2.1", 1000));
        assumeFalse(r.message() != null && r.message().startsWith("ping command failed"),
                "no ping command on this machine");
        assertEquals(Status.DOWN, r.status(), r.message());
    }
}

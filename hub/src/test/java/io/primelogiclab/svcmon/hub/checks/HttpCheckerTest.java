package io.primelogiclab.svcmon.hub.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckResult;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.RunFrom;
import io.primelogiclab.svcmon.hub.model.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HttpCheckerTest {

    private static HttpServer server;
    private static String base;
    private final HttpChecker checker = new HttpChecker();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> reply(ex, 200, "hello world"));
        server.createContext("/boom", ex -> reply(ex, 500, "broken"));
        server.createContext("/redir", ex -> {
            ex.getResponseHeaders().add("Location", "/ok");
            reply(ex, 302, "");
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "late");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static CheckDef def(String url, Map<String, Object> config, int timeoutMs) {
        return new CheckDef("t", "t", CheckType.HTTP, url, config, 60, timeoutMs, 1, RunFrom.HUB, null, true, 0);
    }

    @Test
    void okResponseIsUpWithLatency() {
        CheckResult r = checker.check(def(base + "/ok", Map.of(), 3000));
        assertEquals(Status.UP, r.status());
        assertEquals("HTTP 200", r.message());
        assertTrue(r.latencyMs() != null && r.latencyMs() >= 0);
    }

    @Test
    void serverErrorIsDownUnlessExpected() {
        assertEquals(Status.DOWN, checker.check(def(base + "/boom", Map.of(), 3000)).status());
        assertEquals(Status.UP, checker.check(def(base + "/boom", Map.of("expectStatus", "500"), 3000)).status());
    }

    @Test
    void keywordMustBePresent() {
        assertEquals(Status.UP, checker.check(def(base + "/ok", Map.of("keyword", "world"), 3000)).status());
        CheckResult miss = checker.check(def(base + "/ok", Map.of("keyword", "absent"), 3000));
        assertEquals(Status.DOWN, miss.status());
        assertTrue(miss.message().contains("keyword"));
    }

    @Test
    void redirectsFollowedOnlyWhenEnabled() {
        assertEquals(Status.UP, checker.check(def(base + "/redir", Map.of(), 3000)).status());
        assertEquals(Status.DOWN,
                checker.check(def(base + "/redir", Map.of("followRedirects", false), 3000)).status());
        assertEquals(Status.UP, checker.check(
                def(base + "/redir", Map.of("followRedirects", false, "expectStatus", "302"), 3000)).status());
    }

    @Test
    void slowResponseTimesOut() {
        CheckResult r = checker.check(def(base + "/slow", Map.of(), 300));
        assertEquals(Status.DOWN, r.status());
        assertTrue(r.message().startsWith("Timed out"), r.message());
    }

    @Test
    void refusedConnectionIsDown() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertEquals(Status.DOWN, checker.check(def("http://127.0.0.1:" + closedPort + "/", Map.of(), 2000)).status());
    }

    @Test
    void onlyHttpSchemesAreAllowed() {
        assertEquals(Status.DOWN, checker.check(def("ftp://example.com/file", Map.of(), 1000)).status());
        assertEquals(Status.DOWN, checker.check(def("file:///etc/passwd", Map.of(), 1000)).status());
    }

    @Test
    void statusSpecParsing() {
        assertTrue(HttpChecker.statusMatches("200-299", 204));
        assertFalse(HttpChecker.statusMatches("200-299", 301));
        assertTrue(HttpChecker.statusMatches("200, 301-302,404", 302));
        assertTrue(HttpChecker.statusMatches("200,404", 404));
        assertFalse(HttpChecker.statusMatches("abc", 200));
    }
}

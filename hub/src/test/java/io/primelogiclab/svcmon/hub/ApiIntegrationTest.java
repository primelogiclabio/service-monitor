package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Drives the real hub over HTTP: auth scopes, checks, agent enrollment and the roll-up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    private static final String ADMIN = "test-admin-token-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-it");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.agent-stale-seconds", () -> "3");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    record Resp(int status, Map<String, Object> map, List<Object> list) {
        @SuppressWarnings("unchecked")
        Map<String, Object> obj(String key) {
            return (Map<String, Object>) map.get(key);
        }
    }

    @SuppressWarnings("unchecked")
    private Resp call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String text = r.body();
        Map<String, Object> map = null;
        List<Object> list = null;
        if (text != null && text.startsWith("{")) {
            map = json.readValue(text, Map.class);
        } else if (text != null && text.startsWith("[")) {
            list = json.readValue(text, List.class);
        }
        return new Resp(r.statusCode(), map, list);
    }

    private static String closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "127.0.0.1:" + s.getLocalPort();
        }
    }

    @Test
    void healthIsPublicButEverythingElseNeedsAToken() throws Exception {
        assertEquals(200, call("GET", "/api/v1/health", null, null).status());
        assertEquals(401, call("GET", "/api/v1/status", null, null).status());
        assertEquals(401, call("GET", "/api/v1/status", "not-a-real-token-0000000000000000", null).status());
        Resp hub = call("GET", "/api/v1/hub", ADMIN, null);
        assertEquals(200, hub.status());
        assertNotNull(hub.map().get("hubId"));
    }

    private HttpResponse<String> raw(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void uiAndCorsAreOffByDefault() throws Exception {
        // No svcmon.ui-dir: nothing is served at the root.
        assertEquals(404, raw("GET", "/", Map.of()).statusCode());

        // No svcmon.cors-allowed-origins: no CORS headers, even when an Origin is sent.
        String origin = "http://ui.example.test:5173";
        HttpResponse<String> cross = raw("GET", "/api/v1/health", Map.of("Origin", origin));
        assertEquals(200, cross.statusCode());
        assertTrue(cross.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

        // A preflight is not turned away for lacking a token, but it is not approved either.
        HttpResponse<String> preflight = raw("OPTIONS", "/api/v1/status",
                Map.of("Origin", origin, "Access-Control-Request-Method", "GET"));
        assertNotEquals(401, preflight.statusCode());
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void readTokensCanReadButNotWrite() throws Exception {
        Resp created = call("POST", "/api/v1/tokens", ADMIN, "{\"name\":\"cli\",\"scope\":\"READ\"}");
        assertEquals(201, created.status());
        String read = (String) created.map().get("token");

        assertEquals(200, call("GET", "/api/v1/status", read, null).status());
        assertEquals(403, call("POST", "/api/v1/checks", read,
                "{\"name\":\"x\",\"type\":\"TCP\",\"target\":\"127.0.0.1:1\"}").status());
        assertEquals(403, call("GET", "/api/v1/tokens", read, null).status());
    }

    @Test
    void hubChecksRunAndRollUp() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            String openTarget = "127.0.0.1:" + listening.getLocalPort();
            Resp up = call("POST", "/api/v1/checks", ADMIN,
                    "{\"name\":\"open port\",\"type\":\"TCP\",\"target\":\"" + openTarget + "\"}");
            assertEquals(201, up.status());
            String upId = (String) up.map().get("id");
            assertEquals("UP", call("POST", "/api/v1/checks/" + upId + "/run", ADMIN, null).map().get("status"));

            // Two failures are needed before the check is reported DOWN. It is disabled so the hub's scheduler
            // never runs it; a disabled hub check can still be run by hand and still shows in the status.
            Resp flaky = call("POST", "/api/v1/checks", ADMIN, "{\"name\":\"closed port\",\"type\":\"TCP\","
                    + "\"target\":\"" + closedPort() + "\",\"failuresBeforeDown\":2,\"enabled\":false}");
            String flakyId = (String) flaky.map().get("id");
            call("POST", "/api/v1/checks/" + flakyId + "/run", ADMIN, null);
            assertEquals("UNKNOWN", stateOf(flakyId));
            call("POST", "/api/v1/checks/" + flakyId + "/run", ADMIN, null);
            assertEquals("DOWN", stateOf(flakyId));
            assertEquals("UP", stateOf(upId));

            // The hub's scheduler starts a new enabled check within about 2 seconds and would add results of its
            // own; this check is created disabled so only the two runs above count. Waiting past that window
            // makes the test fail every time if the check were scheduled, instead of once in a while.
            Thread.sleep(2500);
            Resp history = call("GET", "/api/v1/checks/" + flakyId + "/results?limit=10", ADMIN, null);
            assertEquals(2, history.list().size());
        }
    }

    private String stateOf(String checkId) throws Exception {
        Resp status = call("GET", "/api/v1/status", ADMIN, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hubChecks = (List<Map<String, Object>>) status.map().get("hubChecks");
        return (String) hubChecks.stream().filter(c -> checkId.equals(c.get("id"))).findFirst().orElseThrow().get("status");
    }

    @Test
    void checkValidationRejectsBadInput() throws Exception {
        assertEquals(400, call("POST", "/api/v1/checks", ADMIN,
                "{\"name\":\"p\",\"type\":\"PING\",\"target\":\"-f\"}").status());
        assertEquals(400, call("POST", "/api/v1/checks", ADMIN,
                "{\"name\":\"c\",\"type\":\"CPU\",\"runFrom\":\"HUB\"}").status());
        assertEquals(400, call("POST", "/api/v1/checks", ADMIN,
                "{\"name\":\"h\",\"type\":\"HTTP\",\"target\":\"ftp://x/y\"}").status());
        assertEquals(400, call("POST", "/api/v1/checks", ADMIN,
                "{\"name\":\"s\",\"type\":\"SERVICE\",\"target\":\"w3svc\"}").status());
        assertEquals(400, call("POST", "/api/v1/checks", ADMIN, "not json").status());
        assertEquals(404, call("GET", "/api/v1/checks/does-not-exist", ADMIN, null).status());
    }

    @Test
    void containerChecksAreValidatedAndHandedToTheAgent() throws Exception {
        Resp enroll = call("POST", "/api/v1/enrollment-tokens", ADMIN, "{\"ttlMinutes\":5}");
        Resp reg = call("POST", "/api/v1/agent/register", (String) enroll.map().get("token"),
                "{\"name\":\"docker01\",\"cluster\":\"prod\"}");
        assertEquals(201, reg.status());
        String agentToken = (String) reg.map().get("token");
        String on = "\"serverId\":\"" + reg.map().get("serverId") + "\"";
        String head = "{\"name\":\"c\",\"type\":\"CONTAINER\"," + on + ",";

        // Each of these has a server, so only the target or the config can be what is refused.
        for (String bad : List.of(
                "\"config\":{}",
                "\"target\":\"../etc\"",
                "\"target\":\"two words\"",
                "\"target\":\"-lead\"",
                "\"target\":\"web\",\"runFrom\":\"HUB\"",
                "\"target\":\"web\",\"config\":{\"socket\":\"relative.sock\"}",
                "\"target\":\"web\",\"config\":{\"socket\":\"/\"}",
                "\"target\":\"web\",\"config\":{\"socket\":\"npipe:///var/run/docker.sock\"}",
                "\"target\":\"web\",\"config\":{\"socket\":\"//./pipe/\"}",
                "\"target\":\"web\",\"config\":{\"socket\":5}",
                "\"target\":\"web\",\"config\":{\"sokcet\":\"/var/run/docker.sock\"}")) {
            assertEquals(400, call("POST", "/api/v1/checks", ADMIN, head + bad + "}").status(), bad);
        }
        Resp typo = call("POST", "/api/v1/checks", ADMIN, head + "\"target\":\"web\",\"config\":{\"sokcet\":\"/x\"}}");
        assertTrue(String.valueOf(typo.map().get("message")).contains("sokcet"), "a typo should be named");

        Resp web = call("POST", "/api/v1/checks", ADMIN, head + "\"target\":\"web\"}");
        assertEquals(201, web.status());
        assertEquals("AGENT", web.map().get("runFrom"));
        assertEquals(201, call("POST", "/api/v1/checks", ADMIN, head
                + "\"target\":\"db-1.prod\",\"config\":{\"socket\":\"/run/podman/podman.sock\"}}").status());
        assertEquals(201, call("POST", "/api/v1/checks", ADMIN, head
                + "\"target\":\"cache\",\"config\":{\"socket\":\"unix:///var/run/docker.sock\"}}").status());

        Resp cfg = call("GET", "/api/v1/agent/config", agentToken, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assigned = (List<Map<String, Object>>) (List<?>) cfg.map().get("checks");
        assertEquals(3, assigned.size());
        assertTrue(assigned.stream().allMatch(c -> "CONTAINER".equals(c.get("type"))));
        assertEquals(List.of("cache", "db-1.prod", "web"),
                assigned.stream().map(c -> (String) c.get("target")).sorted().toList());

        // Docker Desktop on Windows listens on a named pipe, written in any of its usual forms.
        for (String pipe : List.of("npipe:////./pipe/docker_engine", "//./pipe/dockerDesktopLinuxEngine",
                "\\\\\\\\.\\\\pipe\\\\podman-machine-default")) {
            assertEquals(201, call("POST", "/api/v1/checks", ADMIN, head
                    + "\"target\":\"pipe-test\",\"config\":{\"socket\":\"" + pipe + "\"}}").status(), pipe);
        }
    }

    @Test
    void scheduledChecksRunOnTheirOwn() throws Exception {
        try (ServerSocket listening = new ServerSocket(0)) {
            Resp created = call("POST", "/api/v1/checks", ADMIN, "{\"name\":\"auto\",\"type\":\"TCP\","
                    + "\"target\":\"127.0.0.1:" + listening.getLocalPort() + "\",\"intervalSeconds\":5}");
            String id = (String) created.map().get("id");
            long deadline = System.currentTimeMillis() + 12_000;
            int count = 0;
            while (System.currentTimeMillis() < deadline && count == 0) {
                Thread.sleep(500);
                count = call("GET", "/api/v1/checks/" + id + "/results", ADMIN, null).list().size();
            }
            assertTrue(count >= 1, "scheduler should have produced a result");
            call("DELETE", "/api/v1/checks/" + id, ADMIN, null);
        }
    }

    @Test
    void agentEnrollsReportsAndGoesOffline() throws Exception {
        Resp enroll = call("POST", "/api/v1/enrollment-tokens", ADMIN, "{\"ttlMinutes\":5}");
        assertEquals(201, enroll.status());
        String enrollToken = (String) enroll.map().get("token");

        String registerBody = "{\"name\":\"web01\",\"cluster\":\"prod\",\"os\":\"Windows Server 2022\",\"agentVersion\":\"0.1\"}";
        assertEquals(401, call("POST", "/api/v1/agent/register", null, registerBody).status());
        assertEquals(403, call("POST", "/api/v1/agent/register", ADMIN, registerBody).status(),
                "an admin token is valid but is not an enrollment token");

        Resp reg = call("POST", "/api/v1/agent/register", enrollToken, registerBody);
        assertEquals(201, reg.status());
        String agentToken = (String) reg.map().get("token");
        String serverId = (String) reg.map().get("serverId");
        assertEquals(401, call("POST", "/api/v1/agent/register", enrollToken,
                "{\"name\":\"web02\"}").status(), "enrollment tokens are single use");

        // Nothing is monitored until the admin lists checks.
        Resp cfg = call("GET", "/api/v1/agent/config", agentToken, null);
        assertEquals(200, cfg.status());
        assertTrue(((List<?>) cfg.map().get("checks")).isEmpty());

        Resp cpu = call("POST", "/api/v1/checks", ADMIN, "{\"name\":\"cpu\",\"type\":\"CPU\","
                + "\"serverId\":\"" + serverId + "\",\"config\":{\"warn\":80,\"crit\":95}}");
        assertEquals(201, cpu.status());
        assertEquals("AGENT", cpu.map().get("runFrom"));
        String cpuId = (String) cpu.map().get("id");
        Resp svc = call("POST", "/api/v1/checks", ADMIN, "{\"name\":\"IIS\",\"type\":\"SERVICE\","
                + "\"target\":\"W3SVC\",\"serverId\":\"" + serverId + "\"}");
        String svcId = (String) svc.map().get("id");

        cfg = call("GET", "/api/v1/agent/config", agentToken, null);
        assertEquals(2, ((List<?>) cfg.map().get("checks")).size());

        // Agent tokens cannot read the dashboard or admin endpoints.
        assertEquals(403, call("GET", "/api/v1/status", agentToken, null).status());
        assertEquals(403, call("POST", "/api/v1/checks", agentToken, "{}").status());

        Resp report = call("POST", "/api/v1/agent/report", agentToken, "{\"results\":["
                + "{\"checkId\":\"" + cpuId + "\",\"status\":\"WARN\",\"value\":91.5,\"message\":\"CPU 91.5%\"},"
                + "{\"checkId\":\"" + svcId + "\",\"status\":\"UP\",\"message\":\"Running\"},"
                + "{\"checkId\":\"not-mine\",\"status\":\"UP\"}]}");
        assertEquals(200, report.status());
        assertEquals(2, ((Number) report.map().get("accepted")).intValue());
        assertEquals(1, ((Number) report.map().get("rejected")).intValue());

        Map<String, Object> server = findServer("web01");
        assertEquals(Boolean.TRUE, server.get("agentOnline"));
        assertEquals("WARN", server.get("status"));
        assertEquals("prod", server.get("cluster"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) server.get("checks");
        Map<String, Object> cpuView = checks.stream().filter(c -> cpuId.equals(c.get("id"))).findFirst().orElseThrow();
        assertEquals(91.5, ((Number) cpuView.get("value")).doubleValue());

        // Silence for longer than agent-stale-seconds (3 here) marks the server offline.
        Thread.sleep(3500);
        server = findServer("web01");
        assertEquals(Boolean.FALSE, server.get("agentOnline"));
        assertEquals("DOWN", server.get("status"));

        // A heartbeat brings it back.
        assertEquals(200, call("POST", "/api/v1/agent/report", agentToken, "{\"results\":[]}").status());
        assertEquals(Boolean.TRUE, findServer("web01").get("agentOnline"));

        // Admin can move it to another cluster, and deleting it removes its checks.
        Resp moved = call("PATCH", "/api/v1/servers/" + serverId, ADMIN, "{\"cluster\":\"staging\"}");
        assertEquals("staging", moved.map().get("cluster"));
        assertEquals(204, call("DELETE", "/api/v1/servers/" + serverId, ADMIN, null).status());
        assertEquals(404, call("GET", "/api/v1/checks/" + cpuId, ADMIN, null).status());
        assertFalse(call("GET", "/api/v1/servers", ADMIN, null).list().toString().contains("web01"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findServer(String name) throws Exception {
        Resp status = call("GET", "/api/v1/status", ADMIN, null);
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) status.map().get("clusters");
        return clusters.stream()
                .flatMap(c -> ((List<Map<String, Object>>) c.get("servers")).stream())
                .filter(s -> name.equals(s.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("server " + name + " not in status"));
    }
}

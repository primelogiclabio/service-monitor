package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** svcmon.maintenance.agent-allowed: false leaves maintenance windows to admins. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaintenanceAgentsOffTest {

    private static final String ADMIN = "maint-off-admin-token-0123456789abcdef0123456789";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path root = Files.createTempDirectory("svcmon-maintenance-off");
        registry.add("svcmon.data-dir", () -> root.resolve("data").toString());
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.maintenance.agent-allowed", () -> "false");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, String token, Object body, int expect) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expect, r.statusCode(), r.body());
        return r.body().startsWith("{") ? json.readValue(r.body(), Map.class) : Map.of();
    }

    @Test
    void agentsMayNotOpenOneButMayEndOne() throws Exception {
        String enroll = (String) call("POST", "/api/v1/enrollment-tokens", ADMIN, Map.of("ttlMinutes", 5), 201).get("token");
        Map<String, Object> reg = call("POST", "/api/v1/agent/register", enroll, Map.of("name", "off-agent"), 201);
        String agent = (String) reg.get("token");

        Map<String, Object> refused = call("POST", "/api/v1/agent/maintenance", agent, Map.of("minutes", 10), 403);
        assertEquals("agent_maintenance_off", refused.get("error"));
        assertTrue(String.valueOf(refused.get("message")).contains("svcmon.maintenance.agent-allowed"), refused.toString());

        // An admin still can; ending only lets alerts out again, so the agent may do that.
        call("POST", "/api/v1/servers/" + reg.get("serverId") + "/maintenance", ADMIN, Map.of("minutes", 10), 200);
        call("DELETE", "/api/v1/agent/maintenance", agent, null, 204);
    }
}

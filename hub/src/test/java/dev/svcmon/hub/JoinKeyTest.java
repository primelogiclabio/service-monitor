package dev.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.svcmon.hub.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Join keys over real HTTP: reusable registration, cluster pinning, revoke and expiry, and what they cannot do. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JoinKeyTest {

    private static final String ADMIN = "test-admin-token-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-join");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    TokenService tokens;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    record Resp(int status, String body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map(JsonMapper json) {
            return json.readValue(body, Map.class);
        }
    }

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
        return new Resp(r.statusCode(), r.body());
    }

    private Map<String, Object> createKey(String body) throws Exception {
        Resp r = call("POST", "/api/v1/join-keys", ADMIN, body);
        assertEquals(201, r.status(), r.body());
        return r.map(json);
    }

    private Resp register(String key, String name, String cluster) throws Exception {
        String c = cluster == null ? "" : ",\"cluster\":\"" + cluster + "\"";
        return call("POST", "/api/v1/agent/register", key, "{\"name\":\"" + name + "\",\"os\":\"Windows\"" + c + "}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> keyView(String id) throws Exception {
        List<Map<String, Object>> list = json.readValue(call("GET", "/api/v1/join-keys", ADMIN, null).body(), List.class);
        return list.stream().filter(k -> id.equals(k.get("id"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> server(String name) throws Exception {
        List<Map<String, Object>> list = json.readValue(call("GET", "/api/v1/servers", ADMIN, null).body(), List.class);
        return list.stream().filter(s -> name.equals(s.get("name"))).findFirst().orElse(null);
    }

    @Test
    void oneKeyRegistersManyServersAndCountsThem() throws Exception {
        Map<String, Object> key = createKey("{\"name\":\"fleet\"}");
        String raw = (String) key.get("token");
        assertTrue(raw.startsWith("svm_j_"));
        assertEquals("JOIN", key.get("scope"));
        assertNull(key.get("expiresAt"), "no expiry unless asked for");

        assertEquals(201, register(raw, "app01", null).status());
        Resp second = register(raw, "app02", "prod");
        assertEquals(201, second.status());
        String agentToken = (String) second.map(json).get("token");
        assertEquals(200, call("GET", "/api/v1/agent/config", agentToken, null).status());

        Map<String, Object> view = keyView((String) key.get("id"));
        assertEquals(2, ((Number) view.get("useCount")).intValue());
        assertTrue(view.get("usedAt") instanceof Number);
        assertFalse(view.containsKey("token"), "the key itself is never listed");
        assertFalse(call("GET", "/api/v1/join-keys", ADMIN, null).body().contains(raw));
        assertEquals("prod", server("app02").get("cluster"));
    }

    @Test
    void aKeyWithAClusterPutsEveryServerThere() throws Exception {
        String raw = (String) createKey("{\"name\":\"staging\",\"cluster\":\"staging\"}").get("token");

        assertEquals(201, register(raw, "stage01", null).status());
        assertEquals("staging", server("stage01").get("cluster"));
        assertEquals(201, register(raw, "stage02", "staging").status());

        Resp other = register(raw, "stage03", "prod");
        assertEquals(403, other.status());
        assertTrue(other.body().contains("staging"));
        assertNull(server("stage03"));
    }

    @Test
    void aTakenNameIsStillRefused() throws Exception {
        String raw = (String) createKey("{\"name\":\"names\"}").get("token");
        assertEquals(201, register(raw, "dup01", null).status());
        assertEquals(409, register(raw, "dup01", null).status());
    }

    @Test
    void aRevokedKeyStopsButItsServersKeepWorking() throws Exception {
        Map<String, Object> key = createKey("{\"name\":\"short\"}");
        String raw = (String) key.get("token");
        String agentToken = (String) register(raw, "rev01", null).map(json).get("token");

        assertEquals(204, call("DELETE", "/api/v1/join-keys/" + key.get("id"), ADMIN, null).status());
        assertEquals(401, register(raw, "rev02", null).status());
        assertEquals(200, call("GET", "/api/v1/agent/config", agentToken, null).status());
        assertEquals(404, call("DELETE", "/api/v1/join-keys/" + key.get("id"), ADMIN, null).status());
    }

    @Test
    void anExpiredKeyIsRefused() throws Exception {
        String raw = tokens.issueJoinKey("old", null, System.currentTimeMillis() - 1000).raw();
        assertEquals(401, register(raw, "late01", null).status());

        Map<String, Object> key = createKey("{\"name\":\"month\",\"expiresInDays\":30}");
        assertTrue(((Number) key.get("expiresAt")).longValue() > System.currentTimeMillis());
    }

    @Test
    void aJoinKeyCanOnlyRegister() throws Exception {
        String raw = (String) createKey("{\"name\":\"narrow\"}").get("token");
        assertEquals(403, call("GET", "/api/v1/status", raw, null).status());
        assertEquals(403, call("GET", "/api/v1/join-keys", raw, null).status());
        assertEquals(403, call("POST", "/api/v1/join-keys", raw, "{\"name\":\"more\"}").status());
        assertEquals(403, call("GET", "/api/v1/agent/config", raw, null).status());
    }

    @Test
    void onlyAdminsManageJoinKeysAndBadInputIsRefused() throws Exception {
        String read = (String) json.readValue(call("POST", "/api/v1/tokens", ADMIN,
                "{\"name\":\"viewer\",\"scope\":\"READ\"}").body(), Map.class).get("token");
        assertEquals(403, call("GET", "/api/v1/join-keys", read, null).status());
        assertEquals(403, call("POST", "/api/v1/join-keys", read, "{\"name\":\"x\"}").status());

        assertEquals(400, call("POST", "/api/v1/join-keys", ADMIN, "{}").status());
        assertEquals(400, call("POST", "/api/v1/join-keys", ADMIN, "{\"name\":\"x\",\"expiresInDays\":0}").status());
        assertEquals(400, call("POST", "/api/v1/join-keys", ADMIN, "{\"name\":\"x\",\"cluster\":\"" + "c".repeat(101) + "\"}").status());

        // Another kind of token is not a join key, so it cannot be revoked here.
        String enrollId = (String) json.readValue(call("POST", "/api/v1/enrollment-tokens", ADMIN, "{}").body(), Map.class).get("id");
        assertEquals(404, call("DELETE", "/api/v1/join-keys/" + enrollId, ADMIN, null).status());
    }

    @Test
    void enrollmentTokensStaySingleUse() throws Exception {
        String once = (String) json.readValue(call("POST", "/api/v1/enrollment-tokens", ADMIN, "{}").body(), Map.class).get("token");
        assertEquals(201, register(once, "once01", null).status());
        assertEquals(401, register(once, "once02", null).status());
    }
}

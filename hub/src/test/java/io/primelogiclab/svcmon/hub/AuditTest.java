package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.TimeUnit;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.store.AuditRepo;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real hub writes the audit: sign-ins with the reason they failed, every person's change by its route, agents
 * only when they register, and never a password, token or request body. Only administrators may read it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditTest {

    private static final String ADMIN_TOKEN = "audit-test-admin-token-0123456789abcdef01234567";
    private static final String PASSWORD = "a first password here";
    private static final String NEW_PASSWORD = "correct horse battery staple";
    private static final String SECRET = "sekrit-header-value-42";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-audit");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN_TOKEN);
        registry.add("svcmon.users.initial-admin-password", () -> PASSWORD);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    AuditRepo repo;

    @Autowired
    AuditService audit;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static String admin;
    private static String temporary;
    private static String daveId;

    private HttpResponse<String> send(String method, String path, String cookie, String body, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1");
        if (cookie != null) {
            b.header("Cookie", "svcmon=" + cookie);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withToken(String method, String path, String token, String body) throws Exception {
        return send(method, path, null, body, "Authorization", "Bearer " + token);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    private static String cookieOf(HttpResponse<String> r) {
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring("svcmon=".length(), c.indexOf(';'));
    }

    /** Signs in and replaces the password someone else set; returns the new cookie. */
    private String signInFirstTime(String user, String password) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/session", null,
                json.writeValueAsString(Map.of("username", user, "password", password)));
        assertEquals(200, r.statusCode(), r.body());
        return cookieOf(send("PUT", "/api/v1/me/password", cookieOf(r),
                json.writeValueAsString(Map.of("currentPassword", password, "newPassword", NEW_PASSWORD))));
    }

    /** The audit's raw answer, as an administrator's API token reads it. */
    private HttpResponse<String> auditBody(String query) throws Exception {
        HttpResponse<String> r = withToken("GET", "/api/v1/audit" + query, ADMIN_TOKEN, null);
        assertEquals(200, r.statusCode(), r.body());
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(String query) throws Exception {
        return (List<Map<String, Object>>) map(auditBody(query)).get("events");
    }

    private static Map<String, Object> find(List<Map<String, Object>> events, String key, Object value) {
        return events.stream().filter(e -> value.equals(e.get(key))).findFirst().orElse(null);
    }

    @Test
    @Order(1)
    void signInsAreRecordedWithWhyTheyFailedButNeverWhatWasTyped() throws Exception {
        assertEquals(401, send("POST", "/api/v1/session", null,
                "{\"username\":\"my-password-typed-here\",\"password\":\"x\"}").statusCode());
        assertEquals(401, send("POST", "/api/v1/session", null,
                "{\"username\":\"admin\",\"password\":\"a wrong password\"}").statusCode());
        admin = signInFirstTime("admin", PASSWORD);

        List<Map<String, Object>> signIns = events("?category=sign_in");
        assertEquals(List.of("password_changed:ok:admin", "sign_in:ok:admin", "sign_in:wrong_password:admin",
                        "sign_in:unknown_user:unknown"),
                signIns.stream().map(e -> e.get("kind") + ":" + e.get("outcome") + ":" + e.get("actor")).toList());
        Map<String, Object> ok = signIns.get(1);
        assertEquals("user", ok.get("actorKind"));
        assertEquals("127.0.0.1", ok.get("address"));
        assertEquals("inside", ok.get("network"));
        assertNotNull(ok.get("at"));

        String body = auditBody("").body();
        for (String never : List.of("my-password-typed-here", "a wrong password", PASSWORD, NEW_PASSWORD, admin)) {
            assertFalse(body.contains(never), "the audit holds no " + never);
        }
    }

    @Test
    @Order(2)
    void changesAreRecordedByTheirRouteAndNeverByTheirBody() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/checks", admin, "{\"name\":\"site\",\"type\":\"HTTP\","
                + "\"target\":\"https://example.test/\",\"config\":{\"headers\":{\"Authorization\":\"" + SECRET + "\"}},"
                + "\"enabled\":false}");
        assertEquals(201, created.statusCode(), created.body());
        String id = (String) map(created).get("id");
        assertEquals(204, send("DELETE", "/api/v1/checks/" + id, admin, null).statusCode());
        assertEquals(404, send("DELETE", "/api/v1/checks/" + id, admin, null).statusCode());

        List<Map<String, Object>> changes = events("?category=change");
        assertEquals(List.of("DELETE /api/v1/checks/{id}:not_found:" + id, "DELETE /api/v1/checks/{id}:ok:" + id,
                        "POST /api/v1/checks:ok:null"),
                changes.stream().limit(3).map(e -> e.get("action") + ":" + e.get("outcome") + ":" + e.get("target"))
                        .toList());
        assertEquals("admin", changes.get(0).get("actor"));
        assertFalse(auditBody("").body().contains(SECRET), "a request body never goes to the audit");
        assertNull(find(changes, "action", "POST /api/v1/session"), "signing in is not a change");
    }

    @Test
    @Order(3)
    void userChangesNameTheUserAndNeverTheTemporaryPassword() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/users", admin, "{\"username\":\"dave\",\"role\":\"READ\"}");
        assertEquals(201, created.statusCode(), created.body());
        daveId = (String) ((Map<?, ?>) map(created).get("user")).get("id");
        HttpResponse<String> reset = send("POST", "/api/v1/users/" + daveId + "/password-reset", admin, null);
        assertEquals(200, reset.statusCode());
        temporary = (String) map(reset).get("temporaryPassword");

        List<Map<String, Object>> changes = events("?category=change");
        Map<String, Object> add = find(changes, "action", "POST /api/v1/users");
        assertEquals("dave", add.get("target"));
        assertEquals("role READ", add.get("detail"));
        assertNull(find(changes, "action", "POST /api/v1/users/{id}/password-reset"), "a sign-in event, not twice");
        Map<String, Object> resetEvent = find(events("?category=sign_in"), "kind", "password_reset");
        assertEquals("admin", resetEvent.get("actor"));
        assertEquals("dave", resetEvent.get("target"));

        String body = auditBody("").body();
        assertFalse(body.contains(temporary));
        assertFalse(body.contains((String) map(created).get("temporaryPassword")));
    }

    @Test
    @Order(4)
    void onlyAdministratorsReadTheAuditAndARefusedChangeIsInIt() throws Exception {
        String dave = signInFirstTime("dave", temporary);
        assertEquals(403, send("GET", "/api/v1/audit", dave, null).statusCode());
        assertEquals(403, send("POST", "/api/v1/checks", dave, "{\"name\":\"x\",\"type\":\"TCP\",\"target\":\"h:1\"}")
                .statusCode());

        Map<String, Object> refused = events("?category=change").get(0);
        assertEquals("POST /api/v1/checks", refused.get("action"));
        assertEquals("forbidden", refused.get("outcome"));
        assertEquals("dave", refused.get("actor"));
    }

    @Test
    @Order(5)
    void aRefusalFromOutsideIsRecordedOncePerAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(403, send("GET", "/api/v1/status", null, null, "X-Forwarded-For", "203.0.113.9").statusCode());
        }
        List<Map<String, Object>> refused = events("?category=sign_in").stream()
                .filter(e -> "refused".equals(e.get("kind"))).toList();
        assertEquals(1, refused.size(), refused.toString());
        assertEquals("outside_not_allowed", refused.get(0).get("outcome"));
        assertEquals("203.0.113.9", refused.get(0).get("address"));
        assertEquals("outside", refused.get(0).get("network"));
        assertEquals("GET /api/v1/status", refused.get(0).get("action"));
    }

    @Test
    @Order(6)
    void agentsAppearOnlyWhenTheyRegister() throws Exception {
        HttpResponse<String> enroll = withToken("POST", "/api/v1/enrollment-tokens", ADMIN_TOKEN,
                "{\"name\":\"web-token\",\"ttlMinutes\":5}");
        String enrollToken = (String) map(enroll).get("token");
        HttpResponse<String> reg = withToken("POST", "/api/v1/agent/register", enrollToken, "{\"name\":\"web09\"}");
        assertEquals(201, reg.statusCode(), reg.body());
        String agentToken = (String) map(reg).get("token");
        assertEquals(200, withToken("GET", "/api/v1/agent/config", agentToken, null).statusCode());
        assertEquals(200, withToken("POST", "/api/v1/agent/report", agentToken, "{\"results\":[]}").statusCode());

        List<Map<String, Object>> changes = events("?category=change");
        Map<String, Object> joined = changes.get(0);
        assertEquals("agent_registered", joined.get("kind"));
        assertEquals("web09", joined.get("target"));
        assertEquals("web-token", joined.get("actor"));
        assertEquals("enrollment_token", joined.get("actorKind"));
        Map<String, Object> issued = changes.get(1);
        assertEquals("POST /api/v1/enrollment-tokens", issued.get("action"));
        assertEquals("bootstrap", issued.get("actor"), "an API token is named by its name");
        assertEquals("token", issued.get("actorKind"));
        String body = auditBody("").body();
        assertFalse(body.contains("/agent/report") || body.contains("/agent/config"));
        assertFalse(body.contains(enrollToken) || body.contains(agentToken) || body.contains(ADMIN_TOKEN));
    }

    @Test
    @Order(7)
    void theAuditIsReadPageByPageNewestFirst() throws Exception {
        Map<String, Object> first = map(auditBody("?limit=2"));
        List<?> page = (List<?>) first.get("events");
        assertEquals(2, page.size());
        long next = ((Number) first.get("next")).longValue();
        List<Map<String, Object>> older = events("?limit=500&before=" + next);
        assertFalse(older.isEmpty());
        assertTrue(older.stream().allMatch(e -> ((Number) e.get("id")).longValue() < next));
        assertNull(map(auditBody("?limit=500")).get("next"), "the last page says so");

        assertEquals(400, withToken("GET", "/api/v1/audit?category=everything", ADMIN_TOKEN, null).statusCode());
        assertEquals(400, withToken("GET", "/api/v1/audit?limit=501", ADMIN_TOKEN, null).statusCode());
    }

    @Test
    @Order(8)
    void oldEventsArePurgedAfterTheirCategorysDays() {
        long now = System.currentTimeMillis();
        long day = TimeUnit.DAYS.toMillis(1);
        repo.insert(AuditService.event(now - 31 * day, AuditService.SIGN_IN, "sign_in", "ok", "old", "user", null, null,
                null, null, null));
        repo.insert(AuditService.event(now - 31 * day, AuditService.CHANGE, "api_call", "ok", "kept", "user", null, null,
                null, null, null));
        repo.insert(AuditService.event(now - 366 * day, AuditService.CHANGE, "api_call", "ok", "ancient", "user", null,
                null, null, null, null));
        assertEquals(2, audit.purge());
        List<String> actors = repo.find(null, null, 500).stream().map(e -> e.actor()).toList();
        assertTrue(actors.contains("kept"));
        assertFalse(actors.contains("old") || actors.contains("ancient"), actors.toString());
    }
}

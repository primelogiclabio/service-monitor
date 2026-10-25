package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** The real hub with its local admin account: password sign-in gives an ADMIN token, and nothing weaker gets one. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalAdminLoginTest {

    private static final String ADMIN = "local-admin-test-token-0123456789abcdef0123456789";
    private static final String PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-local-admin");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.local-admin.password", () -> PASSWORD);
        registry.add("svcmon.local-admin.username", () -> "root-admin");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    private HttpResponse<String> login(String user, String password) throws Exception {
        return send("POST", "/api/v1/login", null, json.writeValueAsString(Map.of("username", user, "password", password)));
    }

    @Test
    @Order(1)
    void theAccountSignsInAndGetsAnAdminTokenThatWorksEverywhereATokenDoes() throws Exception {
        HttpResponse<String> r = login("root-admin", PASSWORD);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
        Map<String, Object> session = map(r);
        String token = (String) session.get("token");
        assertNotNull(token);
        assertEquals("ADMIN", session.get("scope"));
        assertEquals("local-login", session.get("name"));
        long expiresAt = ((Number) session.get("expiresAt")).longValue();
        assertTrue(expiresAt > System.currentTimeMillis() + 11 * 3_600_000L && expiresAt < System.currentTimeMillis() + 13 * 3_600_000L,
                "twelve hours by default");

        assertEquals(200, send("GET", "/api/v1/tokens", token, null).statusCode(), "admin-only calls work");
        HttpResponse<String> me = send("GET", "/api/v1/me", token, null);
        assertTrue(me.body().contains("\"name\":\"local-login\"") && me.body().contains("\"scope\":\"ADMIN\""), me.body());
        assertFalse(send("GET", "/api/v1/tokens", ADMIN, null).body().contains(PASSWORD), "no list ever holds a password");
    }

    @Test
    @Order(2)
    void aWrongNameAndAWrongPasswordGetTheSameAnswerAndNeverTheSecret() throws Exception {
        HttpResponse<String> wrongName = login("admin", PASSWORD);
        HttpResponse<String> wrongPassword = login("root-admin", PASSWORD + "x");
        assertEquals(401, wrongName.statusCode());
        assertEquals(401, wrongPassword.statusCode());
        assertEquals(wrongName.body(), wrongPassword.body(), "nothing says which part was wrong");
        assertFalse(wrongPassword.body().contains(PASSWORD));
        assertEquals(401, login("root-admin", "").statusCode());
        assertEquals(401, login("root-admin", "x".repeat(500)).statusCode(), "an oversized password is refused unhashed");
        // Four wrong attempts in a row; a fifth would start the wait (see LocalAdminThrottleTest).
    }

    @Test
    @Order(3)
    void theSignInNeedsAJsonBodyAndOnlyPost() throws Exception {
        assertEquals(400, send("POST", "/api/v1/login", null, null).statusCode());
        assertEquals(400, send("POST", "/api/v1/login", null, "not json").statusCode());
        assertEquals(401, send("GET", "/api/v1/login", null, null).statusCode(), "other methods still need a token");
        assertEquals(401, send("PUT", "/api/v1/login", null, "{}").statusCode());
    }

    @Test
    @Order(4)
    void onlyAnAdministratorCanChangeThePasswordAndItHasToBeLongEnough() throws Exception {
        String body = "{\"newPassword\":\"a much longer new password\"}";
        assertEquals(401, send("PUT", "/api/v1/local-admin/password", null, body).statusCode());

        String read = (String) map(send("POST", "/api/v1/tokens", ADMIN, "{\"name\":\"viewer\",\"scope\":\"READ\"}")).get("token");
        assertEquals(403, send("PUT", "/api/v1/local-admin/password", read, body).statusCode());

        assertEquals(400, send("PUT", "/api/v1/local-admin/password", ADMIN, "{\"newPassword\":\"short\"}").statusCode());
        assertEquals(400, send("PUT", "/api/v1/local-admin/password", ADMIN, "{}").statusCode());
        assertEquals(200, login("root-admin", PASSWORD).statusCode(), "a refused change leaves the password alone");
    }

    @Test
    @Order(5)
    void changingThePasswordEndsEverySignInMadeWithTheOldOne() throws Exception {
        String old = (String) map(login("root-admin", PASSWORD)).get("token");
        assertEquals(200, send("GET", "/api/v1/status", old, null).statusCode());

        assertEquals(204, send("PUT", "/api/v1/local-admin/password", ADMIN, "{\"newPassword\":\"a much longer new password\"}")
                .statusCode());

        assertEquals(401, send("GET", "/api/v1/status", old, null).statusCode(), "the old sign-in is over");
        assertEquals(401, login("root-admin", PASSWORD).statusCode(), "the old password no longer works");
        HttpResponse<String> fresh = login("root-admin", "a much longer new password");
        assertEquals(200, fresh.statusCode(), fresh.body());
        assertEquals(200, send("GET", "/api/v1/status", ADMIN, null).statusCode(), "other tokens are not affected");
    }
}

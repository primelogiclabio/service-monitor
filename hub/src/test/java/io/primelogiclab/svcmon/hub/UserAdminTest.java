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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an administrator does to other users: read-only users can look but not change anything, a reset or a
 * disabled account ends that user's sign-ins at once, and nobody can remove the last administrator or lock
 * themselves out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserAdminTest {

    private static final String ADMIN_TOKEN = "user-admin-test-token-0123456789abcdef0123456";
    private static final String PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-user-admin");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN_TOKEN);
        registry.add("svcmon.users.initial-admin-username", () -> "root");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static String rootCookie;
    private static String rootId;

    private HttpResponse<String> send(String method, String path, String cookie, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        b.header("Content-Type", "application/json").header("X-Svcmon-Request", "1");
        if (cookie == null) {
            b.header("Authorization", "Bearer " + ADMIN_TOKEN);
        } else {
            b.header("Cookie", "svcmon=" + cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    /** Signs in, changing a temporary password to {@link #PASSWORD} on the way, and returns the cookie. */
    private String signIn(String name, String password) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", name, "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), name + ": " + r.body());
        String cookie = UserSignInTest.cookieFrom(r, "svcmon");
        if (Boolean.TRUE.equals(map(r).get("mustChangePassword"))) {
            HttpResponse<String> changed = send("PUT", "/api/v1/me/password", cookie,
                    Map.of("currentPassword", password, "newPassword", PASSWORD));
            assertEquals(204, changed.statusCode(), changed.body());
            cookie = UserSignInTest.cookieFrom(changed, "svcmon");
        }
        return cookie;
    }

    @BeforeEach
    void root() throws Exception {
        if (rootCookie == null) {
            rootCookie = signIn("root", "admin");
            rootId = (String) ((Map<?, ?>) map(send("GET", "/api/v1/session", rootCookie, null)).get("user")).get("id");
        }
    }

    private String create(String name, String role) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/users", rootCookie, Map.of("username", name, "role", role));
        assertEquals(201, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
        return (String) map(r).get("temporaryPassword");
    }

    private String idOf(String name) throws Exception {
        List<?> users = json.readValue(send("GET", "/api/v1/users", rootCookie, null).body(), List.class);
        return users.stream().map(u -> (Map<?, ?>) u).filter(u -> name.equals(u.get("username")))
                .map(u -> (String) u.get("id")).findFirst().orElseThrow();
    }

    @Test
    void aReadOnlyUserLooksButChangesNothing() throws Exception {
        String viewer = signIn("viewer", create("viewer", "READ"));
        assertEquals(200, send("GET", "/api/v1/status", viewer, null).statusCode());
        assertEquals(200, send("GET", "/api/v1/servers", viewer, null).statusCode());
        assertEquals(403, send("POST", "/api/v1/checks", viewer, Map.of("type", "HTTP", "target", "http://x")).statusCode());
        assertEquals(403, send("GET", "/api/v1/users", viewer, null).statusCode(), "the user list is for administrators");
        assertEquals(403, send("GET", "/api/v1/tokens", viewer, null).statusCode());

        List<?> mine = json.readValue(send("GET", "/api/v1/sessions", viewer, null).body(), List.class);
        assertFalse(mine.isEmpty());
        assertTrue(mine.stream().allMatch(s -> "viewer".equals(((Map<?, ?>) s).get("user"))), "only their own");
        List<?> all = json.readValue(send("GET", "/api/v1/sessions", rootCookie, null).body(), List.class);
        assertTrue(all.size() > mine.size(), "an administrator sees everyone's");
    }

    @Test
    void namesAreUniqueWhateverTheCaseAndMustBePlain() throws Exception {
        create("Erin", "READ");
        HttpResponse<String> again = send("POST", "/api/v1/users", rootCookie, Map.of("username", "erin", "role", "ADMIN"));
        assertEquals(409, again.statusCode());
        assertTrue(again.body().contains("username_taken"));
        for (String bad : new String[] {"", "has space", "x".repeat(65), "new\nline", "<b>"}) {
            assertEquals(400, send("POST", "/api/v1/users", rootCookie, Map.of("username", bad, "role", "READ")).statusCode(), bad);
        }
        assertEquals(400, send("POST", "/api/v1/users", rootCookie, Map.of("username", "frank", "role", "OWNER")).statusCode());
    }

    @Test
    void aResetOrADisabledAccountEndsThatUsersSignInsAtOnce() throws Exception {
        String gina = signIn("gina", create("gina", "READ"));
        String id = idOf("gina");
        HttpResponse<String> reset = send("POST", "/api/v1/users/" + id + "/password-reset", rootCookie, null);
        assertEquals(200, reset.statusCode(), reset.body());
        assertEquals(401, send("GET", "/api/v1/status", gina, null).statusCode(), "her sign-in ended");
        gina = signIn("gina", (String) map(reset).get("temporaryPassword"));
        assertEquals(200, send("GET", "/api/v1/status", gina, null).statusCode());

        assertEquals(200, send("PATCH", "/api/v1/users/" + id, rootCookie, Map.of("disabled", true)).statusCode());
        assertEquals(401, send("GET", "/api/v1/status", gina, null).statusCode());
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", "gina", "password", PASSWORD))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, refused.statusCode(), "a disabled account gets the same answer as a wrong password");
    }

    @Test
    void aRoleChangeTakesEffectAtOnce() throws Exception {
        String hal = signIn("hal", create("hal", "READ"));
        String id = idOf("hal");
        HttpResponse<String> promoted = send("PATCH", "/api/v1/users/" + id, rootCookie, Map.of("role", "ADMIN"));
        assertEquals("ADMIN", map(promoted).get("role"));
        assertEquals(401, send("GET", "/api/v1/status", hal, null).statusCode(), "signs in again with the new role");
        hal = signIn("hal", PASSWORD);
        assertEquals(200, send("GET", "/api/v1/users", hal, null).statusCode());
        assertEquals(204, send("DELETE", "/api/v1/users/" + id, rootCookie, null).statusCode());
    }

    @Test
    void nobodyRemovesTheLastAdministratorOrLocksThemselvesOut() throws Exception {
        for (Map<String, Object> change : List.<Map<String, Object>>of(Map.of("role", "READ"), Map.of("disabled", true))) {
            HttpResponse<String> own = send("PATCH", "/api/v1/users/" + rootId, rootCookie, change);
            assertEquals(409, own.statusCode(), change.toString());
            assertTrue(own.body().contains("own_account"), own.body());
            // An API token is not a user, so the last-administrator rule is what stops it.
            HttpResponse<String> byToken = send("PATCH", "/api/v1/users/" + rootId, null, change);
            assertEquals(409, byToken.statusCode(), change.toString());
            assertTrue(byToken.body().contains("last_admin"), byToken.body());
        }
        assertTrue(send("DELETE", "/api/v1/users/" + rootId, null, null).body().contains("last_admin"));
        assertTrue(send("POST", "/api/v1/users/" + rootId + "/password-reset", rootCookie, null).body().contains("own_account"));
        assertEquals(404, send("DELETE", "/api/v1/users/no-such-id", rootCookie, null).statusCode());
        assertEquals(200, send("GET", "/api/v1/status", rootCookie, null).statusCode(), "still signed in");
    }

    @Test
    void anAdministratorEndsSomeoneElsesSessionAndAUserOnlyTheirOwn() throws Exception {
        String ivy = signIn("ivy", create("ivy", "READ"));
        List<?> all = json.readValue(send("GET", "/api/v1/sessions", rootCookie, null).body(), List.class);
        String ivys = all.stream().map(s -> (Map<?, ?>) s).filter(s -> "ivy".equals(s.get("user")))
                .map(s -> (String) s.get("id")).findFirst().orElseThrow();
        String roots = all.stream().map(s -> (Map<?, ?>) s).filter(s -> Boolean.TRUE.equals(s.get("current")))
                .map(s -> (String) s.get("id")).findFirst().orElseThrow();
        assertEquals(404, send("DELETE", "/api/v1/sessions/" + roots, ivy, null).statusCode(), "not someone else's");
        assertEquals(204, send("DELETE", "/api/v1/sessions/" + ivys, rootCookie, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/status", ivy, null).statusCode());
        assertNotNull(rootCookie);
    }
}

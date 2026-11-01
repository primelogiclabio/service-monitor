package io.primelogiclab.svcmon.hub;

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

import io.primelogiclab.svcmon.hub.security.TokenService;
import io.primelogiclab.svcmon.hub.store.TokenRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * A token a person made acts for them: it is suspended while they are disabled, reads only while they are read-only,
 * and goes when they are deleted. What the hub made, and agents' credentials, act for nobody.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenOwnerTest {

    private static final String ADMIN = "owner-test-admin-token-0123456789abcdef01234567";
    private static final String NEW_PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-owner");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    TokenRepo repo;

    @Autowired
    TokenService tokens;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    /** A signed-in administrator: their user id and session cookie. */
    private record Person(String id, String cookie) {
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withToken(String method, String path, String token, String body) throws Exception {
        return send(method, path, body, "Authorization", "Bearer " + token);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> r, int status) {
        assertEquals(status, r.statusCode(), r.body());
        return json.readValue(r.body(), Map.class);
    }

    private static String cookieOf(HttpResponse<String> r) {
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring("svcmon=".length(), c.indexOf(';'));
    }

    /** Makes an administrator with the hub's token and signs them in, past the change of their temporary password. */
    @SuppressWarnings("unchecked")
    private Person admin(String name) throws Exception {
        Map<String, Object> made = map(withToken("POST", "/api/v1/users", ADMIN,
                json.writeValueAsString(Map.of("username", name, "role", "ADMIN"))), 201);
        String id = (String) ((Map<String, Object>) made.get("user")).get("id");
        String temporary = (String) made.get("temporaryPassword");
        HttpResponse<String> in = send("POST", "/api/v1/session",
                json.writeValueAsString(Map.of("username", name, "password", temporary)));
        assertEquals(200, in.statusCode(), in.body());
        String cookie = cookieOf(send("PUT", "/api/v1/me/password", json.writeValueAsString(Map.of(
                "currentPassword", temporary, "newPassword", NEW_PASSWORD)), "Cookie", "svcmon=" + cookieOf(in)));
        return new Person(id, cookie);
    }

    /** A token or join key made in the dashboard by this person; returns the whole answer (id and raw token). */
    private Map<String, Object> make(Person p, String path, String body) throws Exception {
        return map(send("POST", path, body, "Cookie", "svcmon=" + p.cookie()), 201);
    }

    private Map<String, Object> make(String token, String path, String body) throws Exception {
        return map(withToken("POST", path, token, body), 201);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listed(String id) throws Exception {
        List<Map<String, Object>> all = json.readValue(withToken("GET", "/api/v1/tokens", ADMIN, null).body(), List.class);
        return all.stream().filter(t -> id.equals(t.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listedByName(String name) throws Exception {
        List<Map<String, Object>> all = json.readValue(withToken("GET", "/api/v1/tokens", ADMIN, null).body(), List.class);
        return all.stream().filter(t -> name.equals(t.get("name"))).findFirst().orElseThrow();
    }

    private void patchUser(Person p, String body) throws Exception {
        map(withToken("PATCH", "/api/v1/users/" + p.id(), ADMIN, body), 200);
    }

    private int register(String key, String server) throws Exception {
        return withToken("POST", "/api/v1/agent/register", key, "{\"name\":\"" + server + "\"}").statusCode();
    }

    @Test
    void aTokenActsForThePersonWhoMadeItAndWhatTheyMadeWithIt() throws Exception {
        Person alice = admin("alice");
        Map<String, Object> cli = make(alice, "/api/v1/tokens", "{\"name\":\"alice-cli\",\"scope\":\"ADMIN\"}");
        assertEquals(alice.id(), listed((String) cli.get("id")).get("ownerId"));
        String ci = (String) make((String) cli.get("token"), "/api/v1/tokens", "{\"name\":\"alice-ci\",\"scope\":\"READ\"}")
                .get("id");
        assertEquals(alice.id(), listed(ci).get("ownerId"), "made with her token, so it acts for her too");
        Map<String, Object> key = make(alice, "/api/v1/join-keys", "{\"name\":\"alice-key\"}");
        assertEquals(alice.id(), listed((String) key.get("id")).get("ownerId"));
        String once = (String) make(alice, "/api/v1/enrollment-tokens", "{\"name\":\"alice-once\"}").get("id");
        assertEquals(alice.id(), listed(once).get("ownerId"));

        String hubs = (String) make(ADMIN, "/api/v1/tokens", "{\"name\":\"hub-ci\",\"scope\":\"READ\"}").get("id");
        assertNull(listed(hubs).get("ownerId"), "made with the hub's token, so it acts for nobody");
        assertNull(listedByName("bootstrap").get("ownerId"));
        assertEquals(201, register((String) key.get("token"), "alice-app01"));
        assertNull(listedByName("agent:alice-app01").get("ownerId"), "a server's credential is never a person's");
        assertNull(listed(ci).get("limit"), "works fully");
    }

    @Test
    void disablingAUserSuspendsTheirTokensUntilTheyAreEnabledAgain() throws Exception {
        Person bob = admin("bob");
        Map<String, Object> cli = make(bob, "/api/v1/tokens", "{\"name\":\"bob-cli\",\"scope\":\"ADMIN\"}");
        String raw = (String) cli.get("token");
        String key = (String) make(bob, "/api/v1/join-keys", "{\"name\":\"bob-key\"}").get("token");
        assertTrue(tokens.isActive(repo.find((String) cli.get("id")).orElseThrow()));

        patchUser(bob, "{\"disabled\":true}");
        HttpResponse<String> refused = withToken("GET", "/api/v1/status", raw, null);
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("token_suspended"), refused.body());
        assertEquals(403, register(key, "bob-app01"));
        assertEquals("suspended", listed((String) cli.get("id")).get("limit"));
        assertFalse(tokens.isActive(repo.find((String) cli.get("id")).orElseThrow()), "a live view with it closes");

        patchUser(bob, "{\"disabled\":false}");
        assertEquals(200, withToken("GET", "/api/v1/status", raw, null).statusCode());
        assertEquals(201, register(key, "bob-app01"));
        assertNull(listed((String) cli.get("id")).get("limit"));
    }

    @Test
    void aReadOnlyUsersAdminTokenOnlyReadsAndTheirJoinKeyStops() throws Exception {
        Person carol = admin("carol");
        Map<String, Object> cli = make(carol, "/api/v1/tokens", "{\"name\":\"carol-cli\",\"scope\":\"ADMIN\"}");
        String raw = (String) cli.get("token");
        Map<String, Object> key = make(carol, "/api/v1/join-keys", "{\"name\":\"carol-key\"}");

        patchUser(carol, "{\"role\":\"READ\"}");
        assertEquals(200, withToken("GET", "/api/v1/status", raw, null).statusCode());
        assertEquals("READ", map(withToken("GET", "/api/v1/me", raw, null), 200).get("scope"));
        HttpResponse<String> write = withToken("POST", "/api/v1/tokens", raw, "{\"name\":\"more\",\"scope\":\"ADMIN\"}");
        assertEquals(403, write.statusCode());
        assertTrue(write.body().contains("read-only"), write.body());
        assertEquals(403, register((String) key.get("token"), "carol-app01"), "a read-only user cannot add servers");
        assertEquals("read_only", listed((String) cli.get("id")).get("limit"));
        assertEquals("suspended", listed((String) key.get("id")).get("limit"));

        patchUser(carol, "{\"role\":\"ADMIN\"}");
        make(raw, "/api/v1/tokens", "{\"name\":\"more\",\"scope\":\"READ\"}");
        assertEquals(201, register((String) key.get("token"), "carol-app01"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingAUserRevokesTheirTokensButNotTheHubsOrTheServersTheyAdded() throws Exception {
        Person dave = admin("dave");
        String cli = (String) make(dave, "/api/v1/tokens", "{\"name\":\"dave-cli\",\"scope\":\"ADMIN\"}").get("token");
        String ci = (String) make(cli, "/api/v1/tokens", "{\"name\":\"dave-ci\",\"scope\":\"READ\"}").get("token");
        String key = (String) make(dave, "/api/v1/join-keys", "{\"name\":\"dave-key\"}").get("token");
        String agent = (String) map(withToken("POST", "/api/v1/agent/register", key, "{\"name\":\"dave-app01\"}"), 201)
                .get("token");
        String hubs = (String) make(ADMIN, "/api/v1/tokens", "{\"name\":\"dave-era\",\"scope\":\"READ\"}").get("token");

        assertEquals(204, withToken("DELETE", "/api/v1/users/" + dave.id(), ADMIN, null).statusCode());
        for (String gone : new String[] {cli, ci}) {
            assertEquals(401, withToken("GET", "/api/v1/status", gone, null).statusCode());
        }
        assertEquals(401, register(key, "dave-app02"));
        assertEquals(200, withToken("GET", "/api/v1/status", hubs, null).statusCode());
        assertEquals(200, withToken("GET", "/api/v1/agent/config", agent, null).statusCode(), "the server keeps reporting");

        List<Map<String, Object>> events = (List<Map<String, Object>>) map(withToken("GET", "/api/v1/audit?category=change",
                ADMIN, null), 200).get("events");
        Map<String, Object> deleted = events.stream().filter(e -> "dave".equals(e.get("target"))
                && String.valueOf(e.get("action")).startsWith("DELETE")).findFirst().orElseThrow();
        assertEquals("3 token(s) revoked", deleted.get("detail"));
    }

    @Test
    void aTokenThatActsForYouCannotDeleteYou() throws Exception {
        Person erin = admin("erin");
        String cli = (String) make(erin, "/api/v1/tokens", "{\"name\":\"erin-cli\",\"scope\":\"ADMIN\"}").get("token");
        HttpResponse<String> own = withToken("DELETE", "/api/v1/users/" + erin.id(), cli, null);
        assertEquals(409, own.statusCode());
        assertTrue(own.body().contains("own_account"), own.body());
    }
}

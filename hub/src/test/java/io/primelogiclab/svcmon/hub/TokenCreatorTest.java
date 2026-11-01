package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.store.TokenRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Every token remembers who made it: a person, an API token, the key an agent joined with, or the hub itself. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenCreatorTest {

    private static final String ADMIN = "creator-test-admin-token-0123456789abcdef0123456";
    private static final String PASSWORD = "a first password here";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-creator");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.users.initial-admin-password", () -> PASSWORD);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    TokenRepo repo;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

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
    private Map<String, Object> made(HttpResponse<String> r) {
        assertEquals(201, r.statusCode(), r.body());
        return json.readValue(r.body(), Map.class);
    }

    /** The token list's entry with this id, as an administrator reads it. */
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

    private static String madeBy(Map<String, Object> token) {
        return token.get("createdByKind") + ":" + token.get("createdBy");
    }

    private static String cookieOf(HttpResponse<String> r) {
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring("svcmon=".length(), c.indexOf(';'));
    }

    @Test
    void theHubMadeTheBootstrapToken() throws Exception {
        assertEquals("hub:hub", madeBy(listedByName("bootstrap")));
    }

    @Test
    void anApiTokenIsNamedAsTheMakerOfWhatItMakes() throws Exception {
        String id = (String) made(withToken("POST", "/api/v1/tokens", ADMIN, "{\"name\":\"ci\",\"scope\":\"READ\"}")).get("id");
        assertEquals("token:bootstrap", madeBy(listed(id)));
    }

    @Test
    void aSignedInPersonIsNamedAsTheMaker() throws Exception {
        HttpResponse<String> in = send("POST", "/api/v1/session",
                json.writeValueAsString(Map.of("username", "admin", "password", PASSWORD)));
        assertEquals(200, in.statusCode(), in.body());
        String cookie = cookieOf(send("PUT", "/api/v1/me/password", json.writeValueAsString(Map.of(
                "currentPassword", PASSWORD, "newPassword", "correct horse battery staple")), "Cookie", "svcmon=" + cookieOf(in)));

        String id = (String) made(send("POST", "/api/v1/tokens", "{\"name\":\"ops laptop\",\"scope\":\"ADMIN\"}",
                "Cookie", "svcmon=" + cookie)).get("id");
        assertEquals("user:admin", madeBy(listed(id)));
        String key = (String) made(send("POST", "/api/v1/join-keys", "{\"name\":\"by-hand\"}", "Cookie", "svcmon=" + cookie))
                .get("id");
        assertEquals("user:admin", madeBy(listed(key)));
    }

    @Test
    void anAgentsCredentialNamesTheKeyItJoinedWith() throws Exception {
        String joinKey = (String) made(withToken("POST", "/api/v1/join-keys", ADMIN, "{\"name\":\"fleet\"}")).get("token");
        made(withToken("POST", "/api/v1/agent/register", joinKey, "{\"name\":\"app01\"}"));
        assertEquals("join_key:fleet", madeBy(listedByName("agent:app01")));

        String once = (String) made(withToken("POST", "/api/v1/enrollment-tokens", ADMIN, "{\"name\":\"web-token\"}"))
                .get("token");
        made(withToken("POST", "/api/v1/agent/register", once, "{\"name\":\"web01\"}"));
        assertEquals("enrollment_token:web-token", madeBy(listedByName("agent:web01")));
    }

    @Test
    void aTokenFromBeforeTheHubRecordedItHasNoMaker() throws Exception {
        repo.insert(new TokenRecord("old-token-id", "from 0.2.3", Scope.READ, null, "0".repeat(64),
                System.currentTimeMillis(), null, null, null, 0, null, null, null));
        Map<String, Object> old = listed("old-token-id");
        assertNull(old.get("createdBy"));
        assertNull(old.get("createdByKind"));
    }
}

package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import io.primelogiclab.svcmon.hub.security.JwtFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real hub with JWT sign-in on, against a pretend OpenID Connect provider on a local port that serves a
 * discovery document and keys: administrators get in without any hub token, everyone else and every forgery does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtIntegrationTest {

    private static final String ADMIN = "jwt-test-admin-token-0123456789abcdef0123456789";

    private static HttpServer provider;
    private static JwtFixture keys;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        provider = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        String issuer = base + "/realms/test";
        keys = new JwtFixture(issuer);
        serve("/realms/test/.well-known/openid-configuration",
                "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + base + "/realms/test/protocol/openid-connect/certs\"}");
        serve("/realms/test/protocol/openid-connect/certs", keys.jwksJson());
        provider.start();

        Path dir = Files.createTempDirectory("svcmon-jwt");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.jwt.issuer", () -> issuer);
        registry.add("svcmon.jwt.audience", () -> JwtFixture.AUDIENCE);
        registry.add("svcmon.jwt.admin-role", () -> JwtFixture.ADMIN_ROLE);
    }

    private static void serve(String path, String body) {
        provider.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @AfterAll
    static void stopProvider() {
        provider.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, Map<String, String> headers, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private static String roles(String... roles) {
        return keys.sign(keys.claims().claim("realm_access", Map.of("roles", List.of(roles))).build());
    }

    @Test
    void anAdministratorUsesTheApiWithTheirJwtAndNoHubToken() throws Exception {
        Map<String, String> alice = bearer(keys.admin());

        assertEquals(200, send("GET", "/api/v1/status", alice, null).statusCode());
        assertEquals(200, send("GET", "/api/v1/tokens", alice, null).statusCode(), "admin-only reads work too");

        HttpResponse<String> me = send("GET", "/api/v1/me", alice, null);
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("\"method\":\"jwt\"") && me.body().contains("\"name\":\"alice\"")
                && me.body().contains("\"scope\":\"ADMIN\""), me.body());

        HttpResponse<String> created = send("POST", "/api/v1/checks", alice,
                "{\"name\":\"jwt-check\",\"type\":\"TCP\",\"target\":\"127.0.0.1:1\",\"enabled\":false}");
        assertEquals(201, created.statusCode(), created.body());
    }

    @Test
    void aSignedInUserWhoIsNotAnAdministratorGetsNothing() throws Exception {
        for (String path : new String[] {"/api/v1/status", "/api/v1/me", "/api/v1/tokens"}) {
            HttpResponse<String> r = send("GET", path, bearer(roles("offline_access", "staff")), null);
            assertEquals(403, r.statusCode(), path);
            assertFalse(r.body().contains("alice"), "the answer does not echo the name");
        }
    }

    @Test
    void forgedExpiredAndMisaddressedTokensAreUnauthorized() throws Exception {
        long now = System.currentTimeMillis();
        JwtFixture impostor = new JwtFixture(keys.issuer);
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("signed by someone else", impostor.admin());
        bad.put("expired", keys.sign(keys.claims().expirationTime(new Date(now - 600_000)).build()));
        bad.put("for another API", keys.sign(keys.claims().audience("https://golf.example.test/api").build()));
        bad.put("from another issuer", keys.sign(keys.claims().issuer("https://evil.example.test/realms/test").build()));
        for (var e : bad.entrySet()) {
            HttpResponse<String> r = send("GET", "/api/v1/status", bearer(e.getValue()), null);
            assertEquals(401, r.statusCode(), e.getKey());
            assertFalse(r.body().contains(e.getValue()), "a token is never echoed");
        }
    }

    @Test
    void aJwtCannotPassAsAnAgent() throws Exception {
        assertEquals(403, send("GET", "/api/v1/agent/config", bearer(keys.admin()), null).statusCode());
        assertEquals(403, send("POST", "/api/v1/agent/register", bearer(keys.admin()), "{}").statusCode());
    }

    @Test
    void theHubsOwnTokensKeepWorkingForTheCliAndAgents() throws Exception {
        HttpResponse<String> me = send("GET", "/api/v1/me", bearer(ADMIN), null);
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("\"method\":\"token\"") && me.body().contains("\"scope\":\"ADMIN\""), me.body());
        assertEquals(401, send("GET", "/api/v1/status", Map.of(), null).statusCode());
        assertEquals(200, send("GET", "/api/v1/health", Map.of(), null).statusCode(), "the health probe stays open");
    }
}

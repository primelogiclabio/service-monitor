package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The hub with a UI folder and CORS origins configured: static files, API precedence, and browser access. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UiHostingTest {

    private static final String ADMIN = "ui-hosting-admin-token-0123456789abcdef0123456789";
    private static final String ALLOWED = "http://ui.example.test:5173";
    private static final String ALSO_ALLOWED = "https://monitor.example.test";
    private static final String DENIED = "http://evil.example.test";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path root = Files.createTempDirectory("svcmon-ui-hosting");
        Path ui = Files.createDirectories(root.resolve("dist"));
        Files.writeString(ui.resolve("index.html"), "<!doctype html><title>UI test page</title>");
        Files.createDirectories(ui.resolve("assets"));
        Files.writeString(ui.resolve("assets").resolve("app-abc123.js"), "console.log('ui');");
        Files.writeString(root.resolve("secret.txt"), "top secret");

        registry.add("svcmon.data-dir", () -> root.resolve("data").toString());
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.ui-dir", ui::toString);
        registry.add("svcmon.cors-allowed-origins", () -> ALLOWED + "," + ALSO_ALLOWED);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    private static Map<String, String> auth() {
        return Map.of("Authorization", "Bearer " + ADMIN);
    }

    // ---- static UI ----

    @Test
    void rootServesIndexWithoutCaching() throws Exception {
        HttpResponse<String> r = send("GET", "/", Map.of());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("UI test page"));
        assertTrue(header(r, "Cache-Control").contains("no-cache"), header(r, "Cache-Control"));

        assertEquals(200, send("GET", "/index.html", Map.of()).statusCode());
    }

    @Test
    void hashedAssetsAreCachedForever() throws Exception {
        HttpResponse<String> r = send("GET", "/assets/app-abc123.js", Map.of());
        assertEquals(200, r.statusCode());
        String cache = header(r, "Cache-Control").toLowerCase(Locale.ROOT);
        assertTrue(cache.contains("immutable") && cache.contains("max-age=31536000"), cache);
    }

    @Test
    void missingFilesAreNotFoundAndNothingEscapesTheFolder() throws Exception {
        assertEquals(404, send("GET", "/missing.html", Map.of()).statusCode());
        for (String path : new String[] {"/%2e%2e/secret.txt", "/assets/%2e%2e/%2e%2e/secret.txt", "/..%5csecret.txt"}) {
            HttpResponse<String> r = send("GET", path, Map.of());
            assertNotEquals(200, r.statusCode(), path);
            assertFalse(r.body().contains("top secret"), path);
        }
    }

    @Test
    void apiRoutesStillWinOverFiles() throws Exception {
        HttpResponse<String> health = send("GET", "/api/v1/health", Map.of());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"ok\""));

        HttpResponse<String> unknown = send("GET", "/api/v1/nope", auth());
        assertEquals(404, unknown.statusCode());
        assertFalse(unknown.body().contains("UI test page"), "an unknown API path must not fall back to the UI");
    }

    @Test
    void everyResponseCarriesBrowserHardeningHeaders() throws Exception {
        for (String path : new String[] {"/", "/api/v1/health", "/api/v1/status"}) {
            HttpResponse<String> r = send("GET", path, Map.of());
            assertEquals("nosniff", header(r, "X-Content-Type-Options"), path);
            assertEquals("DENY", header(r, "X-Frame-Options"), path);
            assertEquals("no-referrer", header(r, "Referrer-Policy"), path);
        }
    }

    // ---- CORS ----

    @Test
    void preflightFromAnAllowedOriginIsAnsweredWithoutAToken() throws Exception {
        for (String origin : new String[] {ALLOWED, ALSO_ALLOWED}) {
            HttpResponse<String> r = send("OPTIONS", "/api/v1/status", Map.of(
                    "Origin", origin,
                    "Access-Control-Request-Method", "GET",
                    "Access-Control-Request-Headers", "authorization"));
            assertEquals(200, r.statusCode(), origin);
            assertEquals(origin, header(r, "Access-Control-Allow-Origin"));
            assertTrue(header(r, "Access-Control-Allow-Headers").toLowerCase(Locale.ROOT).contains("authorization"));
            assertTrue(header(r, "Access-Control-Allow-Methods").contains("GET"));
            assertEquals(null, header(r, "Access-Control-Allow-Credentials"), "cookies are never allowed");
            assertEquals("3600", header(r, "Access-Control-Max-Age"));
        }
    }

    @Test
    void preflightFromAnotherOriginIsRefused() throws Exception {
        HttpResponse<String> r = send("OPTIONS", "/api/v1/status", Map.of(
                "Origin", DENIED,
                "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "authorization"));
        assertEquals(403, r.statusCode());
        assertEquals(null, header(r, "Access-Control-Allow-Origin"));
    }

    @Test
    void preflightCannotAskForHeadersOrMethodsOutsideTheList() throws Exception {
        HttpResponse<String> badHeader = send("OPTIONS", "/api/v1/status", Map.of(
                "Origin", ALLOWED, "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "x-custom"));
        assertEquals(403, badHeader.statusCode());
        HttpResponse<String> badMethod = send("OPTIONS", "/api/v1/status", Map.of(
                "Origin", ALLOWED, "Access-Control-Request-Method", "TRACE"));
        assertEquals(403, badMethod.statusCode());
    }

    @Test
    void allowedOriginsCanReadResponsesIncludingAuthErrors() throws Exception {
        HttpResponse<String> ok = send("GET", "/api/v1/status", Map.of("Origin", ALLOWED, "Authorization", "Bearer " + ADMIN));
        assertEquals(200, ok.statusCode());
        assertEquals(ALLOWED, header(ok, "Access-Control-Allow-Origin"));

        // The page must be able to read a 401 to tell the user the token is wrong.
        HttpResponse<String> unauthorized = send("GET", "/api/v1/status", Map.of("Origin", ALLOWED));
        assertEquals(401, unauthorized.statusCode());
        assertEquals(ALLOWED, header(unauthorized, "Access-Control-Allow-Origin"));

        HttpResponse<String> wrongScope = send("GET", "/api/v1/agent/config", Map.of("Origin", ALLOWED, "Authorization", "Bearer " + ADMIN));
        assertEquals(403, wrongScope.statusCode());
        assertEquals(ALLOWED, header(wrongScope, "Access-Control-Allow-Origin"));
    }

    @Test
    void otherOriginsAreRejectedButPlainClientsAreUnaffected() throws Exception {
        HttpResponse<String> cross = send("GET", "/api/v1/health", Map.of("Origin", DENIED));
        assertEquals(403, cross.statusCode());
        assertEquals(null, header(cross, "Access-Control-Allow-Origin"));

        // No Origin header: the CLI, agents and scripts.
        HttpResponse<String> plain = send("GET", "/api/v1/status", auth());
        assertEquals(200, plain.statusCode());
        assertEquals(null, header(plain, "Access-Control-Allow-Origin"));
    }
}

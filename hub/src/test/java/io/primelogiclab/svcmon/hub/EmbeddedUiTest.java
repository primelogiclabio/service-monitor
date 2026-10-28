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
import tools.jackson.databind.json.JsonMapper;

/**
 * A hub whose jar carries the dashboard (the test classpath has one under test-ui/) and no ui-dir: one address
 * serves the page and the API, and the page's config.json comes from the hub's own settings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmbeddedUiTest {

    private static final String ADMIN = "embedded-ui-admin-token-0123456789abcdef0123456789";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path root = Files.createTempDirectory("svcmon-embedded-ui");
        registry.add("svcmon.data-dir", () -> root.resolve("data").toString());
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.embedded-ui", () -> "classpath:/test-ui/");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theDashboardInsideTheJarIsServedAtTheRoot() throws Exception {
        HttpResponse<String> r = get("/", Map.of());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Jar UI test page"), r.body());
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-cache"));
        assertEquals(200, get("/index.html", Map.of()).statusCode());
    }

    @Test
    void hashedAssetsAreCachedForever() throws Exception {
        HttpResponse<String> r = get("/assets/app-abc123.js", Map.of());
        assertEquals(200, r.statusCode());
        String cache = r.headers().firstValue("Cache-Control").orElse("").toLowerCase(Locale.ROOT);
        assertTrue(cache.contains("immutable") && cache.contains("max-age=31536000"), cache);
    }

    @Test
    void apiRoutesAndHardeningHeadersAreUnchanged() throws Exception {
        HttpResponse<String> health = get("/api/v1/health", Map.of());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"ok\""));
        HttpResponse<String> unknown = get("/api/v1/nope", Map.of("Authorization", "Bearer " + ADMIN));
        assertEquals(404, unknown.statusCode());
        assertFalse(unknown.body().contains("Jar UI test page"), "an unknown API path must not fall back to the page");
        assertEquals(401, get("/api/v1/status", Map.of()).statusCode(), "the page is public, the API is not");
        for (String path : new String[] {"/", "/config.json"}) {
            HttpResponse<String> r = get(path, Map.of());
            assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null), path);
            assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(null), path);
        }
    }

    @Test
    void nothingEscapesTheEmbeddedFolder() throws Exception {
        assertEquals(404, get("/missing.html", Map.of()).statusCode());
        for (String path : new String[] {"/%2e%2e/application.yml", "/assets/%2e%2e/%2e%2e/application.yml", "/..%5capplication.yml"}) {
            HttpResponse<String> r = get(path, Map.of());
            assertNotEquals(200, r.statusCode(), path);
            assertFalse(r.body().contains("svcmon-hub"), path);
        }
    }

    @Test
    void configJsonIsEmptySinceTheDashboardAlwaysSignsInWithHubAccounts() throws Exception {
        HttpResponse<String> r = get("/config.json", Map.of());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-cache"));
        assertEquals(Map.of(), json.readValue(r.body(), Map.class));
    }
}

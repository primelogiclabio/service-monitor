package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import io.primelogiclab.svcmon.hub.secrets.SecretKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alert channel secrets and HTTP check header values are encrypted in the database, and nothing else changes: the API
 * masks them as before, alerts are delivered and signed with the real values, and a check sends its real header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecretsAtRestTest {

    private static final String ADMIN = "test-admin-token-0123456789abcdef0123456789abcdef";
    private static Path dir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        dir = Files.createTempDirectory("svcmon-secrets");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.embedded-ui", () -> "classpath:/no-dashboard-here/");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void secretsAreEncryptedInTheDatabaseAndWorkAsBefore() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var h = exchange.getRequestHeaders();
            seen.add(exchange.getRequestURI().getPath() + " auth=" + h.getFirst("Authorization") + " token="
                    + h.getFirst("X-Token") + " signed=" + ("sha256=" + hmac("signing-secret-555", body))
                    .equals(h.getFirst("X-Svcmon-Signature")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
            String base = "http://127.0.0.1:" + receiver.getAddress().getPort();
            String hook = post("/api/v1/alert-channels", Map.of("name", "hook", "type", "WEBHOOK", "config", Map.of(
                    "url", base + "/hook/path-secret-111", "headers", Map.of("X-Token", "header-secret-222"),
                    "signingSecret", "signing-secret-555")));
            post("/api/v1/alert-channels", Map.of("name", "chat", "type", "SLACK", "config",
                    Map.of("url", "https://hooks.slack.com/services/T0/B0/slack-secret-333")));
            post("/api/v1/alert-channels", Map.of("name", "mail", "type", "EMAIL", "config", Map.of(
                    "smtpHost", "smtp.example.com", "username", "alerts", "password", "smtp-secret-444",
                    "from", "svcmon@example.com", "to", List.of("ops@example.com"))));
            String check = post("/api/v1/checks", Map.of("name", "keyed", "type", "HTTP", "target", base + "/health",
                    "enabled", false, "config", Map.of("headers", Map.of("Authorization", "Bearer check-secret-666"))));

            List<String> stored = rawConfigs();
            String all = String.join("\n", stored);
            for (String secret : List.of("path-secret-111", "header-secret-222", "slack-secret-333", "smtp-secret-444",
                    "signing-secret-555", "check-secret-666")) {
                assertFalse(all.contains(secret), secret + " is stored as it is:\n" + all);
            }
            assertTrue(stored.stream().allMatch(c -> c.contains("enc:v1:")), all);
            assertTrue(all.contains("smtp.example.com") && all.contains("X-Token") && all.contains("Authorization"),
                    "only the values are encrypted: " + all);

            // Everything above the database sees the real values: the API masks them, alerts and checks use them.
            String answer = call("GET", "/api/v1/alert-channels", null).body() + call("GET", "/api/v1/checks", null).body();
            assertFalse(answer.contains("secret-") || answer.contains("enc:v1:"), answer);
            assertEquals(200, call("POST", "/api/v1/alert-channels/" + hook + "/test", null).statusCode());
            assertEquals(200, call("POST", "/api/v1/checks/" + check + "/run", null).statusCode());
            assertEquals(List.of("/hook/path-secret-111 auth=null token=header-secret-222 signed=true",
                    "/health auth=Bearer check-secret-666 token=null signed=false"), seen);
        } finally {
            receiver.stop(0);
        }
    }

    @Test
    void theKeyFileIsMadeInTheDataFolderForTheHubsAccountOnly() throws Exception {
        Path file = dir.resolve(SecretKeys.FILE);
        assertTrue(Files.exists(file));
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "POSIX permissions only");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    private List<String> rawConfigs() throws SQLException {
        List<String> configs = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("svcmon.db"));
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT config FROM alert_channels UNION ALL SELECT config FROM checks")) {
            while (rs.next()) {
                configs.add(rs.getString(1));
            }
        }
        return configs;
    }

    private String post(String path, Map<String, Object> body) throws Exception {
        HttpResponse<String> r = call("POST", path, json.writeValueAsString(body));
        assertEquals(201, r.statusCode(), r.body());
        return (String) json.readValue(r.body(), Map.class).get("id");
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + ADMIN);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

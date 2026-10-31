package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import io.primelogiclab.svcmon.hub.oidc.FakeProvider;
import io.primelogiclab.svcmon.hub.oidc.FileKey;
import io.primelogiclab.svcmon.hub.oidc.HubKey;
import io.primelogiclab.svcmon.hub.oidc.KeyFiles;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real hub signing in to the provider with a key pair from signing-keys-tool's files instead of one it makes:
 * the proof names the certificate, the key address carries it, and the hub makes no key of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderKeyFilesTest {

    private static final FakeProvider PROVIDER = FakeProvider.start();
    private static final String ADMIN_TOKEN = "provider-key-files-test-token-0123456789abcd";
    private static Path dir;
    private static KeyFiles.Pair pair;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        dir = Files.createTempDirectory("svcmon-provider-key-files");
        pair = KeyFiles.make(dir.resolve("keys"), "signing", 3072, 0, 1825);
        registry.add("svcmon.data-dir", () -> dir.resolve("data").toString());
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN_TOKEN);
        registry.add("svcmon.public-url", () -> "https://monitor.test");
        registry.add("svcmon.oidc.issuer", () -> PROVIDER.issuer);
        registry.add("svcmon.oidc.client-id", () -> "svcmon-files");
        registry.add("svcmon.oidc.roles-claim", () -> "roles");
        registry.add("svcmon.oidc.admin-role", () -> "Monitor.Admin");
        registry.add("svcmon.oidc.private-key-file", () -> pair.key().toString());
        registry.add("svcmon.oidc.certificate-file", () -> pair.certificate().toString());
    }

    @AfterAll
    static void stop() {
        PROVIDER.close();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHubSignsInWithTheGivenPairAndNamesItsCertificate() throws Exception {
        PROVIDER.hubJwks("http://127.0.0.1:" + port + "/api/v1/oidc/jwks");
        FileKey files = new FileKey(pair.key(), pair.certificate());

        JWKSet published = JWKSet.parse(get("/api/v1/oidc/jwks").body());
        assertEquals(files.current().getKeyID(), published.getKeys().get(0).getKeyID());
        assertFalse(published.getKeys().get(0).getX509CertChain().isEmpty(), "the certificate is published too");
        assertFalse(Files.exists(dir.resolve("data").resolve(HubKey.FILE)), "the hub makes no key of its own");

        HttpResponse<String> start = get("/api/v1/oidc/start");
        String to = start.headers().firstValue("Location").orElseThrow();
        String binding = start.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("svcmon-oidc="))
                .map(c -> c.substring("svcmon-oidc=".length(), c.indexOf(';'))).findFirst().orElseThrow();
        String code = PROVIDER.approve(URI.create(to), Map.of("sub", "sub-entra-1", "preferred_username", "dana",
                "roles", List.of("Monitor.Admin")));
        String state = FakeProvider.query(URI.create(to).getRawQuery()).get("state");
        HttpResponse<String> back = get("/api/v1/oidc/callback?code=" + code + "&state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8), "Cookie", "svcmon-oidc=" + binding);
        assertEquals("https://monitor.test/", back.headers().firstValue("Location").orElseThrow());
        assertEquals(files.current().getKeyID(), PROVIDER.lastAssertionKeyId);
        assertEquals(files.current().getX509CertSHA256Thumbprint().toString(), PROVIDER.lastAssertionThumbprint);

        Map<String, Object> view = json.readValue(get("/api/v1/oidc", "Authorization", "Bearer " + ADMIN_TOKEN).body(),
                Map.class);
        assertEquals("files", view.get("keySource"));
        assertEquals(pair.key().toString(), view.get("privateKeyFile"));
        assertTrue(((String) view.get("certificatePem")).startsWith("-----BEGIN CERTIFICATE-----"));
        assertNotNull(view.get("certificateExpiresAt"));
        assertEquals(files.expiresAt().toEpochMilli(), ((Number) view.get("certificateExpiresAt")).longValue());
        assertFalse(view.toString().contains("PRIVATE KEY"), "never the private key");
    }
}

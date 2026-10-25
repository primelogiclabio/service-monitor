package io.primelogiclab.svcmon.hub.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import com.sun.net.httpserver.HttpServer;
import io.primelogiclab.svcmon.hub.security.JwtFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The live view signed in with an OpenID Connect access token: on the upgrade request, where a reverse proxy adds
 * it from the user's session, or in the first frame. A provider on a local port serves the discovery document and keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveJwtTest {

    private static HttpServer provider;
    private static JwtFixture keys;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        String issuer = base + "/realms/test";
        keys = new JwtFixture(issuer);
        serve("/realms/test/.well-known/openid-configuration",
                "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + base + "/certs\"}");
        serve("/certs", keys.jwksJson());
        provider.start();

        Path dir = Files.createTempDirectory("svcmon-live-jwt");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> "live-jwt-admin-token-0123456789abcdef0123456789");
        registry.add("svcmon.jwt.issuer", () -> issuer);
        registry.add("svcmon.jwt.audience", () -> JwtFixture.AUDIENCE);
        registry.add("svcmon.jwt.admin-role", () -> JwtFixture.ADMIN_ROLE);
        registry.add("svcmon.cors-allowed-origins", () -> "https://dashboard.example.test");
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

    private static Map<String, String> handshake(String token, String... more) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        for (int i = 0; i < more.length; i += 2) {
            h.put(more[i], more[i + 1]);
        }
        return h;
    }

    private static String roles(String... roles) {
        return keys.sign(keys.claims().claim("realm_access", Map.of("roles", List.of(roles))).build());
    }

    private int refusedWith(Map<String, String> headers) {
        CompletionException e = assertThrows(CompletionException.class, () -> LiveClient.connect(port, headers));
        assertTrue(e.getCause() instanceof WebSocketHandshakeException, String.valueOf(e.getCause()));
        return ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode();
    }

    @Test
    void aJwtOnTheUpgradeRequestSignsInWithoutAFirstFrame() throws Exception {
        LiveClient live = LiveClient.connect(port, handshake(keys.admin(), "Sec-Fetch-Site", "same-origin"));
        try {
            String first = live.next(Duration.ofSeconds(5));
            assertNotNull(first, "the status arrives at once, with no auth frame sent");
            assertTrue(first.contains("\"type\":\"status\""), first);
        } finally {
            live.close();
        }
    }

    @Test
    void aJwtInTheFirstFrameWorksToo() throws Exception {
        LiveClient live = LiveClient.connect(port);
        try {
            live.auth(keys.admin());
            assertTrue(live.next(Duration.ofSeconds(5)).contains("\"type\":\"status\""));
        } finally {
            live.close();
        }
    }

    @Test
    void aFirstFrameJwtWithoutTheAdminRoleIsClosedAsForbidden() throws Exception {
        LiveClient live = LiveClient.connect(port);
        live.auth(roles("staff"));
        assertEquals(1008, live.awaitClose(Duration.ofSeconds(5)));
        assertEquals("forbidden", live.closeReason.get());
    }

    @Test
    void badTokensOnTheUpgradeRequestAreRefusedBeforeTheSocketOpens() {
        assertEquals(401, refusedWith(handshake(new JwtFixture(keys.issuer).admin())));
        assertEquals(403, refusedWith(handshake(roles("staff"))));
    }

    @Test
    void aPageOnAnotherSiteCannotOpenTheLiveViewWithTheUsersSignIn() {
        assertEquals(403, refusedWith(handshake(keys.admin(), "Sec-Fetch-Site", "cross-site",
                "Origin", "https://evil.example.test")));
        assertEquals(403, refusedWith(handshake(keys.admin(), "Origin", "https://evil.example.test")),
                "an Origin that is not this hub's own address is refused");
    }

    @Test
    void aBrowserThatSendsOnlyAnOriginIsJudgedByIt() throws Exception {
        // Chrome sends an Origin but no Sec-Fetch-Site on a WebSocket request. The test client's Host is 127.0.0.1:port.
        for (Map<String, String> ok : List.of(
                handshake(keys.admin(), "Origin", "http://127.0.0.1:" + port),
                handshake(keys.admin(), "Origin", "https://monitor.example.test", "X-Forwarded-Host", "monitor.example.test",
                        "X-Forwarded-Proto", "https"),
                handshake(keys.admin(), "Origin", "https://dashboard.example.test"))) {
            LiveClient live = LiveClient.connect(port, ok);
            try {
                assertNotNull(live.next(Duration.ofSeconds(5)), String.valueOf(ok));
            } finally {
                live.close();
            }
        }
        assertEquals(403, refusedWith(handshake(keys.admin(), "Origin", "https://evil.example.test",
                "X-Forwarded-Host", "monitor.example.test", "X-Forwarded-Proto", "https")));
        assertEquals(403, refusedWith(handshake(keys.admin(), "Sec-Fetch-Site", "cross-site",
                "Origin", "http://127.0.0.1:" + port)), "what the browser says about the page wins over the Origin");
    }

    @Test
    void aConnectionEndsWhenItsJwtExpiresSoTheDashboardReconnectsWithAFreshOne() throws Exception {
        String shortLived = keys.sign(keys.claims().expirationTime(new Date(System.currentTimeMillis() + 2_000)).build());
        LiveClient live = LiveClient.connect(port, handshake(shortLived));
        try {
            assertNotNull(live.next(Duration.ofSeconds(5)));
            assertEquals(1001, live.awaitClose(Duration.ofSeconds(10)), "going away, not a refusal");
            assertEquals("sign-in expired", live.closeReason.get());
        } finally {
            live.close();
        }
    }
}

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
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real hub with the default svcmon.access: people outside the internal network are refused, whatever they sign
 * in with, and the page is told why. The tests reach the hub from 127.0.0.1, a trusted proxy, so X-Forwarded-For
 * says where each request comes from, as a load balancer's would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NetworkAccessTest {

    private static final String ADMIN_TOKEN = "network-access-test-token-0123456789abcdef0123";
    private static final String PASSWORD = "a first password here";
    private static final String NEW_PASSWORD = "correct horse battery staple";
    static final String OUTSIDE = "203.0.113.7";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-network-access");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN_TOKEN);
        registry.add("svcmon.users.initial-admin-password", () -> PASSWORD);
        // A gateway inside the network that people outside come through.
        registry.add("svcmon.access.public-proxies", () -> "10.0.0.5");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static String cookie;

    private HttpResponse<String> send(String method, String path, String from, String body, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1");
        if (from != null) {
            b.header("X-Forwarded-For", from);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withCookie(String path, String from, String value) throws Exception {
        return send("GET", path, from, null, "Cookie", "svcmon=" + value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    private static String cookieOf(HttpResponse<String> r) {
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring("svcmon=".length(), c.indexOf(';'));
    }

    /** A user signed in from inside, past the forced password change. */
    private synchronized String insideCookie() throws Exception {
        if (cookie == null) {
            String first = cookieOf(send("POST", "/api/v1/session", null,
                    "{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"));
            cookie = cookieOf(send("PUT", "/api/v1/me/password", null,
                    "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}",
                    "Cookie", "svcmon=" + first));
        }
        return cookie;
    }

    private static void refused(HttpResponse<String> r, String code) {
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"error\":\"" + code + "\""), r.body());
    }

    @Test
    void theSignInPageIsToldItIsOutsideAndSigningInIsRefused() throws Exception {
        Map<String, Object> outside = map(send("GET", "/api/v1/session", OUTSIDE, null));
        assertEquals(Boolean.FALSE, outside.get("signedIn"));
        assertEquals("outside", outside.get("network"));
        assertEquals(OUTSIDE, outside.get("address"));
        assertEquals(Boolean.FALSE, outside.get("signInAllowed"));
        assertEquals("outside_not_allowed", outside.get("signInRefusal"));

        Map<String, Object> inside = map(send("GET", "/api/v1/session", null, null));
        assertEquals("inside", inside.get("network"));
        assertEquals("127.0.0.1", inside.get("address"));
        assertEquals(Boolean.TRUE, inside.get("signInAllowed"));
        assertNull(inside.get("signInRefusal"));

        insideCookie();
        HttpResponse<String> signIn = send("POST", "/api/v1/session", OUTSIDE,
                "{\"username\":\"admin\",\"password\":\"" + NEW_PASSWORD + "\"}");
        refused(signIn, "outside_not_allowed");
        assertTrue(signIn.headers().firstValue("Set-Cookie").isEmpty(), "no cookie for a refused sign-in");
        refused(send("GET", "/api/v1/status", OUTSIDE, null), "outside_not_allowed");
        assertEquals(200, send("GET", "/api/v1/health", OUTSIDE, null).statusCode(), "the load balancer's probe works");
    }

    @Test
    void aSignInFromInsideCannotBeUsedFromOutside() throws Exception {
        String mine = insideCookie();
        refused(withCookie("/api/v1/status", OUTSIDE, mine), "outside_not_allowed");
        assertEquals(Boolean.FALSE, map(withCookie("/api/v1/session", OUTSIDE, mine)).get("signedIn"),
                "the page shows the refusal, not a dashboard that cannot load");
        assertEquals(200, withCookie("/api/v1/status", null, mine).statusCode(), "back inside, the sign-in still works");
    }

    @Test
    void theClisTokensWorkFromInsideOnly() throws Exception {
        assertEquals(200, send("GET", "/api/v1/status", null, null, "Authorization", "Bearer " + ADMIN_TOKEN).statusCode());
        refused(send("GET", "/api/v1/status", OUTSIDE, null, "Authorization", "Bearer " + ADMIN_TOKEN),
                "outside_not_allowed");
        refused(send("POST", "/api/v1/tokens", OUTSIDE, "{\"name\":\"x\",\"scope\":\"READ\"}",
                "Authorization", "Bearer " + ADMIN_TOKEN), "outside_not_allowed");
        refused(send("GET", "/api/v1/status", OUTSIDE, null, "Authorization", "Bearer not-a-token"),
                "outside_not_allowed");
    }

    @Test
    void agentsAreNeverRefusedForWhereTheyAre() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/agent/register", OUTSIDE, "{}",
                "Authorization", "Bearer not-an-enrollment-token");
        assertEquals(401, r.statusCode(), "the token is checked as usual: " + r.body());
    }

    @Test
    void aForgedAddressCanOnlyMakeARequestLookOutside() throws Exception {
        // A person outside who sends X-Forwarded-For: 10.0.0.1 through a load balancer that appends their address.
        Map<String, Object> forged = map(send("GET", "/api/v1/session", "10.0.0.1, " + OUTSIDE, null));
        assertEquals("outside", forged.get("network"));
        assertEquals(OUTSIDE, forged.get("address"));
        // Someone inside who claims to be outside is believed, and only refuses themselves.
        assertEquals("outside", map(send("GET", "/api/v1/session", OUTSIDE, null)).get("network"));
    }

    @Test
    void aRequestThatShowsUpAsThePublicProxyIsOutside() throws Exception {
        Map<String, Object> viaProxy = map(send("GET", "/api/v1/session", "10.0.0.5", null));
        assertEquals("outside", viaProxy.get("network"), "a gateway that forgot X-Forwarded-For is refused, not let in");
        refused(withCookie("/api/v1/status", "10.0.0.5", insideCookie()), "outside_not_allowed");
        assertEquals("inside", map(send("GET", "/api/v1/session", "10.0.0.6", null)).get("network"));
    }

    @Test
    void theLiveViewIsNotOpenedFromOutside() throws Exception {
        var builder = HttpClient.newHttpClient().newWebSocketBuilder().header("X-Forwarded-For", OUTSIDE);
        try {
            builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/live"), new WebSocket.Listener() {
            }).orTimeout(10, TimeUnit.SECONDS).join();
            throw new AssertionError("the upgrade should have been refused");
        } catch (CompletionException e) {
            assertTrue(e.getCause() instanceof WebSocketHandshakeException, e.toString());
            HttpResponse<?> response = ((WebSocketHandshakeException) e.getCause()).getResponse();
            assertEquals(403, response.statusCode());
            assertFalse(String.valueOf(response.body()).contains("status"), "no roll-up was sent");
        }
    }
}

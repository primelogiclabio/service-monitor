package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.live.LiveClient;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real hub with svcmon.access.allow-outside: true, as behind a gateway's sign-in: people outside may sign in,
 * over HTTPS only and never with the initial password, while the CLI's tokens still work from inside only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutsideAccessTest {

    private static final String ADMIN_TOKEN = "outside-access-test-token-0123456789abcdef0123";
    private static final String PASSWORD = "a first password here";
    private static final String NEW_PASSWORD = "correct horse battery staple";
    private static final String OUTSIDE = NetworkAccessTest.OUTSIDE;
    private static final String[] HTTPS = {"X-Forwarded-Proto", "https"};

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-outside-access");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN_TOKEN);
        registry.add("svcmon.users.initial-admin-password", () -> PASSWORD);
        registry.add("svcmon.access.allow-outside", () -> "true");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    /** The plain-HTTP cookie of a sign-in made inside, and the __Host- one of a sign-in made outside over HTTPS. */
    private static String insideCookie;
    private static String outsideCookie;

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

    private HttpResponse<String> signIn(String password, String from, String... headers) throws Exception {
        return send("POST", "/api/v1/session", from, "{\"username\":\"admin\",\"password\":\"" + password + "\"}", headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    private static String cookieOf(HttpResponse<String> r, String name) {
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(c.startsWith(name + "="), c);
        return c.substring(name.length() + 1, c.indexOf(';'));
    }

    private static void refused(HttpResponse<String> r, String code) {
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"error\":\"" + code + "\""), r.body());
    }

    @Test
    @Order(1)
    void theInitialPasswordNeverWorksFromOutside() throws Exception {
        refused(signIn(PASSWORD, OUTSIDE, HTTPS), "initial_password_inside_only");

        String first = cookieOf(signIn(PASSWORD, null), "svcmon");
        insideCookie = cookieOf(send("PUT", "/api/v1/me/password", null,
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}",
                "Cookie", "svcmon=" + first), "svcmon");
    }

    @Test
    @Order(2)
    void fromOutsideSigningInWorksOverHttpsOnly() throws Exception {
        Map<String, Object> plain = map(send("GET", "/api/v1/session", OUTSIDE, null));
        assertEquals(Boolean.FALSE, plain.get("signInAllowed"));
        assertEquals("insecure_transport", plain.get("signInRefusal"));
        refused(signIn(NEW_PASSWORD, OUTSIDE), "insecure_transport");

        assertEquals(Boolean.TRUE, map(send("GET", "/api/v1/session", OUTSIDE, null, HTTPS)).get("signInAllowed"));
        HttpResponse<String> r = signIn(NEW_PASSWORD, OUTSIDE, HTTPS);
        assertEquals(200, r.statusCode(), r.body());
        outsideCookie = cookieOf(r, "__Host-svcmon");
        assertEquals("outside", map(r).get("network"));

        assertEquals(200, send("GET", "/api/v1/status", OUTSIDE, null, "Cookie", "__Host-svcmon=" + outsideCookie,
                HTTPS[0], HTTPS[1]).statusCode());
        refused(send("GET", "/api/v1/status", OUTSIDE, null, "Cookie", "svcmon=" + insideCookie), "insecure_transport");
    }

    @Test
    @Order(3)
    void theAccessSettingsShowHowTheHubSeesThisBrowser() throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/access", OUTSIDE, null, "Cookie", "__Host-svcmon=" + outsideCookie,
                HTTPS[0], HTTPS[1]);
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> access = map(r);
        assertEquals(Boolean.TRUE, access.get("allowOutside"));
        assertEquals(List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"),
                access.get("internalNetworks"));
        assertEquals(List.of(), access.get("publicProxies"));
        assertEquals("outside", access.get("network"));
        assertEquals(OUTSIDE, access.get("address"));
        assertEquals(Boolean.TRUE, access.get("secure"));
        assertEquals(401, send("GET", "/api/v1/access", null, null).statusCode(), "only for people signed in");
    }

    @Test
    @Order(4)
    void theClisTokensStillWorkFromInsideOnly() throws Exception {
        refused(send("GET", "/api/v1/status", OUTSIDE, null, "Authorization", "Bearer " + ADMIN_TOKEN, HTTPS[0], HTTPS[1]),
                "outside_not_allowed");
        assertEquals(200, send("GET", "/api/v1/status", null, null, "Authorization", "Bearer " + ADMIN_TOKEN).statusCode());
        assertEquals(401, send("GET", "/api/v1/status", OUTSIDE, null, HTTPS).statusCode(),
                "without any credential the answer is to sign in");
    }

    @Test
    @Order(5)
    void theLiveViewFromOutsideTakesATicketOverHttpsButNoToken() throws Exception {
        LiveClient token = LiveClient.connect(port, Map.of("X-Forwarded-For", OUTSIDE, "X-Forwarded-Proto", "https"));
        token.auth(ADMIN_TOKEN);
        assertEquals(1008, token.awaitClose(Duration.ofSeconds(5)));
        assertEquals("outside_not_allowed", token.closeReason());

        LiveClient plain = LiveClient.connect(port, Map.of("X-Forwarded-For", OUTSIDE));
        plain.ticket(ticket());
        assertEquals(1008, plain.awaitClose(Duration.ofSeconds(5)));
        assertEquals("insecure_transport", plain.closeReason());

        LiveClient secure = LiveClient.connect(port, Map.of("X-Forwarded-For", OUTSIDE, "X-Forwarded-Proto", "https"));
        secure.ticket(ticket());
        String first = secure.next(Duration.ofSeconds(5));
        assertNotNull(first, "the roll-up arrives");
        assertTrue(first.contains("\"type\":\"status\""), first);
        secure.close();
    }

    private String ticket() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/session/live-ticket", null, null, "Cookie", "svcmon=" + insideCookie);
        assertEquals(200, r.statusCode(), r.body());
        return (String) map(r).get("ticket");
    }
}

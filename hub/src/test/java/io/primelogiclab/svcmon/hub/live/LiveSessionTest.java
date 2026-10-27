package io.primelogiclab.svcmon.hub.live;

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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** A signed-in user's page opens the live view with a one-time ticket, and it closes when they sign out. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveSessionTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-live-session");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> "live-session-test-token-0123456789abcdef0123");
        registry.add("svcmon.users.initial-admin-password", () -> "a first password here");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> send(String method, String path, String cookie, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1");
        if (cookie != null) {
            b.header("Cookie", "svcmon=" + cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<String> r) {
        String c = r.headers().firstValue("Set-Cookie").orElseThrow();
        return c.substring("svcmon=".length(), c.indexOf(';'));
    }

    private String ticket(String cookie) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/session/live-ticket", cookie, null);
        assertEquals(200, r.statusCode(), r.body());
        return (String) json.readValue(r.body(), Map.class).get("ticket");
    }

    @Test
    void aTicketOpensTheLiveViewOnceAndSigningOutClosesIt() throws Exception {
        String cookie = cookie(send("POST", "/api/v1/session", null,
                "{\"username\":\"admin\",\"password\":\"a first password here\"}"));
        cookie = cookie(send("PUT", "/api/v1/me/password", cookie,
                "{\"currentPassword\":\"a first password here\",\"newPassword\":\"correct horse battery staple\"}"));
        String ticket = ticket(cookie);

        LiveClient live = LiveClient.connect(port);
        live.send("{\"type\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        String first = live.next(Duration.ofSeconds(5));
        assertNotNull(first, "the roll-up arrives");
        assertTrue(first.contains("\"type\":\"status\""), first);

        LiveClient again = LiveClient.connect(port);
        again.send("{\"type\":\"auth\",\"ticket\":\"" + ticket + "\"}");
        assertEquals(1008, again.awaitClose(Duration.ofSeconds(5)), "a ticket works once");

        assertEquals(204, send("DELETE", "/api/v1/session", cookie, null).statusCode());
        assertEquals(1008, live.awaitClose(Duration.ofSeconds(5)), "signing out ends the live view");
    }
}

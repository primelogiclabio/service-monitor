package dev.svcmon.hub.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** Drives the real hub's live WebSocket: sign-in by first frame, pushed changes, and every way a client is cut off. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveStatusTest {

    private static final String ADMIN = "test-admin-token-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-live");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + ADMIN);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.body() != null && r.body().startsWith("{") ? json.readValue(r.body(), Map.class) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String frame) {
        return json.readValue(frame, Map.class);
    }

    private String newToken(String scope) throws Exception {
        return (String) call("POST", "/api/v1/tokens", "{\"name\":\"live-" + scope + "\",\"scope\":\"" + scope + "\"}")
                .get("token");
    }

    @Test
    void anAdminTokenInTheFirstFrameGetsTheStatusAndThenEveryChange() throws Exception {
        LiveClient live = LiveClient.connect(port);
        try {
            live.auth(ADMIN);
            Map<String, Object> first = parse(live.next(Duration.ofSeconds(5)));
            assertEquals("status", first.get("type"));
            assertNotNull(((Map<?, ?>) first.get("data")).get("summary"));

            call("POST", "/api/v1/checks",
                    "{\"name\":\"pushed-check\",\"type\":\"TCP\",\"target\":\"127.0.0.1:1\",\"enabled\":false}");

            String pushed = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (pushed == null && System.currentTimeMillis() < deadline) {
                String frame = live.next(Duration.ofSeconds(1));
                if (frame != null && frame.contains("pushed-check")) {
                    pushed = frame;
                }
            }
            assertNotNull(pushed, "the new check should be pushed without asking");
        } finally {
            live.close();
        }
    }

    @Test
    void nothingIsPushedWhileNothingChanges() throws Exception {
        LiveClient live = LiveClient.connect(port);
        try {
            live.auth(ADMIN);
            assertNotNull(live.next(Duration.ofSeconds(5)));
            assertNull(live.next(Duration.ofMillis(3500)));
        } finally {
            live.close();
        }
    }

    @Test
    void aReadTokenIsEnoughAndPingIsAnswered() throws Exception {
        LiveClient live = LiveClient.connect(port);
        try {
            live.auth(newToken("READ"));
            assertEquals("status", parse(live.next(Duration.ofSeconds(5))).get("type"));

            live.send("{\"type\":\"ping\"}");
            assertEquals("pong", parse(live.next(Duration.ofSeconds(5))).get("type"));
        } finally {
            live.close();
        }
    }

    @Test
    void aWrongTokenIsRefusedWithoutEchoingIt() throws Exception {
        LiveClient live = LiveClient.connect(port);
        String wrong = "svm_a_this-is-not-a-real-token-at-all";
        live.auth(wrong);

        assertEquals(1008, live.awaitClose(Duration.ofSeconds(5)));
        assertFalse(live.closeReason.get().contains(wrong));
        assertNull(live.next(Duration.ofMillis(200)), "no status before signing in");
    }

    @Test
    void aTokenWithoutReadAccessIsRefused() throws Exception {
        String enroll = (String) call("POST", "/api/v1/enrollment-tokens", "{\"ttlMinutes\":5}").get("token");
        LiveClient live = LiveClient.connect(port);
        live.auth(enroll);

        assertEquals(1008, live.awaitClose(Duration.ofSeconds(5)));
        assertNull(live.next(Duration.ofMillis(200)));
    }

    @Test
    void anythingButAuthFirstIsRefused() throws Exception {
        LiveClient live = LiveClient.connect(port);
        live.send("{\"type\":\"ping\"}");

        assertEquals(1008, live.awaitClose(Duration.ofSeconds(5)));
    }

    @Test
    void garbageIsRefused() throws Exception {
        LiveClient live = LiveClient.connect(port);
        live.send("this is not json");

        assertEquals(1007, live.awaitClose(Duration.ofSeconds(5)));
    }

    @Test
    void aRevokedTokenIsDroppedWhileConnected() throws Exception {
        Map<String, Object> created = call("POST", "/api/v1/tokens", "{\"name\":\"short-lived\",\"scope\":\"READ\"}");
        LiveClient live = LiveClient.connect(port);
        live.auth((String) created.get("token"));
        assertEquals("status", parse(live.next(Duration.ofSeconds(5))).get("type"));

        call("DELETE", "/api/v1/tokens/" + created.get("id"), null);

        assertEquals(1008, live.awaitClose(Duration.ofSeconds(5)));
    }

    @Test
    void aConnectionThatNeverSignsInIsClosed() throws Exception {
        LiveClient live = LiveClient.connect(port);

        // The hub waits 5 seconds for the first frame; the timer checks once a second.
        assertEquals(1008, live.awaitClose(Duration.ofSeconds(12)));
        assertTrue(live.closeReason.get().contains("timeout"));
    }
}

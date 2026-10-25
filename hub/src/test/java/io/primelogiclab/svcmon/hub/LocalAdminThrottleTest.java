package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Guessing the local admin password gets a few tries and then a wait, even for the right password. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalAdminThrottleTest {

    private static final String PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-local-admin-throttle");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> "throttle-test-token-0123456789abcdef0123456789");
        registry.add("svcmon.local-admin.password", () -> PASSWORD);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> login(String password) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"" + password + "\"}"))
                .build();
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void afterAFewWrongPasswordsEveryAttemptIsRefusedUntilTheWaitIsOver() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login("wrong password " + i).statusCode(), "attempt " + i);
        }
        HttpResponse<String> held = login("wrong password again");
        assertEquals(429, held.statusCode());
        long wait = Long.parseLong(held.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(wait > 0 && wait <= 900, "Retry-After " + wait);
        assertTrue(held.body().contains("try again"), held.body());

        HttpResponse<String> right = login(PASSWORD);
        assertEquals(429, right.statusCode(), "the right password does not get in while held");
        assertFalse(right.body().contains("token"));
    }
}

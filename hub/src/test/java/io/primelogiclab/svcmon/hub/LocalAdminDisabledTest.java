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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** svcmon.local-admin.enabled=false: no account is made, no password printed, and the endpoints say it is off. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalAdminDisabledTest {

    private static final String ADMIN = "disabled-test-token-0123456789abcdef0123456789";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-local-admin-off");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.local-admin.enabled", () -> "false");
    }

    @Value("${local.server.port}")
    int port;

    private HttpResponse<String> send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        b.header("Content-Type", "application/json");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void nothingIsCreatedPrintedOrAccepted(CapturedOutput output) throws Exception {
        assertFalse(output.getAll().contains("Local admin account (shown once"), "no password banner");
        assertTrue(output.getAll().contains("local admin account is turned off"));

        assertEquals(404, send("POST", "/api/v1/login", null, "{\"username\":\"admin\",\"password\":\"anything at all here\"}")
                .statusCode());
        assertEquals(404, send("PUT", "/api/v1/local-admin/password", ADMIN, "{\"newPassword\":\"a long enough password\"}")
                .statusCode());
        assertEquals(200, send("GET", "/api/v1/status", ADMIN, "").statusCode(), "tokens are unaffected");
    }
}

package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With no password configured the first start makes a random one and prints it once, like the first admin token. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalAdminFirstStartTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-local-admin-first");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> "first-start-token-0123456789abcdef0123456789");
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void aGeneratedPasswordIsPrintedOnceAndWorks(CapturedOutput output) throws Exception {
        String log = output.getAll();
        Matcher m = Pattern.compile("user:\\s+admin\\s+password:\\s+(\\S+)").matcher(log);
        assertTrue(m.find(), "the banner names the account and its password: " + log);
        String password = m.group(1);
        assertEquals(24, password.length());
        assertEquals(1, log.split(Pattern.quote(password), -1).length - 1, "the password appears exactly once");

        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"" + password + "\"}"))
                .build();
        HttpResponse<String> reply = HttpClient.newHttpClient().send(r, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reply.statusCode(), reply.body());
    }
}

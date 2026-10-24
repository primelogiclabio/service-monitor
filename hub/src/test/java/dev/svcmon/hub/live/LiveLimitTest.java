package dev.svcmon.hub.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The live view's connection cap, in its own hub so other tests' sockets do not count against it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveLimitTest {

    private static final String ADMIN = "test-admin-token-0123456789abcdef0123456789abcdef";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-live-limit");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.live-max-sessions", () -> "2");
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void connectionsOverTheCapAreToldToComeBackLater() throws Exception {
        LiveClient first = LiveClient.connect(port);
        LiveClient second = LiveClient.connect(port);
        try {
            first.auth(ADMIN);
            second.auth(ADMIN);
            assertNotNull(first.next(Duration.ofSeconds(5)));
            assertNotNull(second.next(Duration.ofSeconds(5)));

            LiveClient third = LiveClient.connect(port);
            assertEquals(1013, third.awaitClose(Duration.ofSeconds(5)));
        } finally {
            first.close();
            second.close();
        }
    }
}

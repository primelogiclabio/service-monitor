package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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

/** A real hub start writes its address and what it watches to the log, and keeps the admin token out of it. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StartupLogTest {

    private static final String ADMIN = "startup-log-admin-token-0123456789abcdef0123456789";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-startup-log");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
    }

    @Value("${local.server.port}")
    int port;

    /** Filled in from the pom by Maven, so the hub reports the version it was built as. */
    @Value("${svcmon.version}")
    String version;

    @Test
    void logsAddressAndWatchedThingsOnceReady(CapturedOutput output) {
        String log = output.getAll();

        assertFalse(version.contains("@"), "the pom version was not filled in: " + version);
        assertTrue(log.contains("Service Monitor hub " + version + " is ready"), log);
        assertTrue(log.contains("http://localhost:" + port), log);
        assertTrue(log.contains("nothing yet"), log);
        assertFalse(log.contains(ADMIN), "the admin token must never be logged");
    }
}

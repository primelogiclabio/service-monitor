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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import io.primelogiclab.svcmon.hub.security.PasswordHasher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A hub from before users, with a local admin account and one of its sign-ins, is started on this version: the
 * account becomes the administrator 'admin' with the same password, and the old sign-in tokens are gone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyLocalAdminUpgradeTest {

    private static final String OLD_PASSWORD = "the local admin's own password";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException, SQLException {
        Path dir = Files.createTempDirectory("svcmon-legacy");
        String url = "jdbc:sqlite:" + dir.resolve("svcmon.db");
        Flyway.configure().dataSource(url, null, null).target("4").load().migrate();
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO hub_meta (key, value) VALUES (?, ?)")) {
                s.setString(1, "local-admin.password-hash");
                s.setString(2, new PasswordHasher().hash(OLD_PASSWORD));
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO tokens (id, name, scope, token_hash, created_at, "
                    + "expires_at) VALUES (?, ?, 'ADMIN', ?, 0, ?)")) {
                s.setString(1, "t1");
                s.setString(2, "local-login");
                s.setString(3, "hash-of-a-sign-in");
                s.setLong(4, Long.MAX_VALUE);
                s.executeUpdate();
                s.setString(1, "t2");
                s.setString(2, "initial-admin");
                s.setString(3, "hash-of-the-first-admin-token");
                s.setObject(4, null);
                s.executeUpdate();
            }
        }
        registry.add("svcmon.data-dir", dir::toString);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theLocalAdminBecomesAUserWithItsPasswordAndItsOldSignInsAreGone() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"" + OLD_PASSWORD + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"mustChangePassword\":false"), "a password the admin chose is kept: " + r.body());

        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM tokens WHERE name = 'local-login'").query(Long.class).single());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM tokens WHERE name = 'initial-admin'").query(Long.class).single(),
                "other tokens are kept");
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM hub_meta WHERE key = 'local-admin.password-hash'").query(Long.class)
                .single(), "the hash now lives only with the user");
    }
}

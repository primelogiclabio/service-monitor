package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.secrets.SecretKeys;
import io.primelogiclab.svcmon.hub.store.AlertChannelRepo;
import io.primelogiclab.svcmon.hub.store.CheckRepo;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Secrets stored before the hub encrypted them are encrypted at its first start and read back the same, and a hub
 * whose key file is missing or wrong refuses to start rather than lose them, without showing one.
 */
class SecretsUpgradeTest {

    private static final List<String> SECRETS = List.of("path-secret-1", "signing-secret-2", "header-secret-3",
            "check-secret-4");

    @TempDir
    Path dir;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void secretsFromBeforeAreEncryptedAtStartAndReadTheSame(CapturedOutput output) throws Exception {
        storeAsBefore();
        try (ConfigurableApplicationContext hub = start()) {
            // The log says what happened by count, never with a secret or the key.
            String log = output.getAll();
            assertTrue(log.contains("Made the key for stored secrets") && log.contains("Encrypted 4 stored secret(s)"),
                    log);
            SECRETS.forEach(s -> assertFalse(log.contains(s), s));
            String key = Base64.getEncoder().encodeToString(
                    new SecretKeys(dir.resolve(SecretKeys.FILE)).ring().currentKey().getEncoded());
            assertFalse(log.contains(key), "the key is in the log");

            Map<String, Object> channel = hub.getBean(AlertChannelRepo.class).find("c1").orElseThrow().config();
            assertEquals("https://hooks.example/path-secret-1", channel.get("url"));
            assertEquals("signing-secret-2", channel.get("signingSecret"));
            assertEquals(Map.of("X-Token", "header-secret-3"), channel.get("headers"));
            assertEquals(Map.of("keyword", "ok", "headers", Map.of("Authorization", "Bearer check-secret-4")),
                    hub.getBean(CheckRepo.class).find("k1").orElseThrow().config());

            // Nor is a copy of them left in the files of the running hub: the old pages, or the write-ahead log.
            for (String name : List.of("svcmon.db", "svcmon.db-wal")) {
                Path f = dir.resolve(name);
                String bytes = Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1) : "";
                SECRETS.forEach(s -> assertFalse(bytes.contains(s), s + " is still in " + name));
            }
        }
        String stored = String.join("\n", rawConfigs());
        SECRETS.forEach(s -> assertFalse(stored.contains(s), s + " is still stored as it was:\n" + stored));
        assertTrue(stored.contains("enc:v1:") && stored.contains("\"keyword\":\"ok\""), stored);

        // Nothing is left to do at the next start: the rows stay as they are.
        try (ConfigurableApplicationContext hub = start()) {
            assertEquals(stored, String.join("\n", rawConfigs()));
        }
    }

    @Test
    void aMissingOrWrongKeyFileStopsTheStart() throws Exception {
        storeAsBefore();
        start().close();
        Path file = dir.resolve(SecretKeys.FILE);
        Path kept = Files.move(file, dir.resolve("kept.json"));

        String missing = refused();
        assertTrue(missing.contains(SecretKeys.FILE) && missing.contains("is missing")
                && missing.contains("secrets forget-unreadable"), missing);
        assertFalse(Files.exists(file), "no new key replaces the lost one");

        new SecretKeys(file).create();
        String wrong = refused();
        assertTrue(wrong.contains("cannot be decrypted") && wrong.contains("alert channel 'hook'")
                && wrong.contains("check 'keyed'"), wrong);

        Files.move(kept, file, StandardCopyOption.REPLACE_EXISTING);
        start().close();
    }

    private String refused() {
        Throwable e = assertThrows(Exception.class, this::start);
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        SECRETS.forEach(s -> assertFalse(all.toString().contains(s), all.toString()));
        return all.toString();
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(HubApplication.class).run("--server.port=0", "--svcmon.data-dir=" + dir,
                "--svcmon.bootstrap-admin-token=test-admin-token-0123456789abcdef0123456789abcdef",
                "--svcmon.embedded-ui=classpath:/no-dashboard-here/", "--spring.main.banner-mode=off");
    }

    private void storeAsBefore() throws SQLException {
        String url = "jdbc:sqlite:" + dir.resolve("svcmon.db");
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (Connection c = DriverManager.getConnection(url)) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO alert_channels (id, name, type, config, statuses,"
                    + " enabled, created_at) VALUES ('c1', 'hook', 'WEBHOOK', ?, 'DOWN,UP', 1, 0)")) {
                s.setString(1, "{\"url\":\"https://hooks.example/path-secret-1\",\"signingSecret\":\"signing-secret-2\","
                        + "\"headers\":{\"X-Token\":\"header-secret-3\"}}");
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO checks (id, name, type, target, config, run_from,"
                    + " source, enabled, created_at) VALUES ('k1', 'keyed', 'HTTP', 'http://127.0.0.1:9/', ?, 'HUB',"
                    + " 'HUB', 0, 0)")) {
                s.setString(1, "{\"keyword\":\"ok\",\"headers\":{\"Authorization\":\"Bearer check-secret-4\"}}");
                s.executeUpdate();
            }
        }
    }

    private List<String> rawConfigs() throws SQLException {
        List<String> configs = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("svcmon.db"));
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT config FROM alert_channels UNION ALL SELECT config FROM checks")) {
            while (rs.next()) {
                configs.add(rs.getString(1));
            }
        }
        return configs;
    }
}

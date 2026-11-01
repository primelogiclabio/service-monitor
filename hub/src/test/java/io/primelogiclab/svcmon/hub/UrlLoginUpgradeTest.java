package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks stored before HTTP checks refused a login in their URL lose it on upgrade (V9): from the URL, a name made up
 * from it and a declared check's key. Nothing else is touched.
 */
class UrlLoginUpgradeTest {

    @TempDir
    Path dir;

    @Test
    void loginsAreRemovedFromStoredChecksAndNothingElseChanges() throws SQLException {
        String url = "jdbc:sqlite:" + dir.resolve("svcmon.db");
        Flyway.configure().dataSource(url, null, null).target("8").load().migrate();
        try (Connection c = DriverManager.getConnection(url)) {
            c.createStatement().executeUpdate("INSERT INTO servers (id, name, created_at) VALUES ('s1', 'web-1', 0)");
            String login = "https://watch:s3cret@intranet.example/health";
            insert(c, "declared", "http " + login, "HTTP", login, "s1", "type:HTTP:" + login, 1);
            insert(c, "token", "Status page", "HTTP", "http://abc123@127.0.0.1:9/status", null, null, 2);
            insert(c, "query", "Mail link", "HTTP", "https://example.com/go?mail=a@b.example", null, null, 3);
            insert(c, "service", "getty@tty1", "SERVICE", "getty@tty1.service", "s1", null, 4);
            insert(c, "twin-a", "twin a", "HTTP", "https://a:1@twin.example/", "s1", "type:HTTP:https://a:1@twin.example/", 5);
            insert(c, "twin-b", "twin b", "HTTP", "https://b:2@twin.example/", "s1", "type:HTTP:https://b:2@twin.example/", 6);
        }

        Flyway.configure().dataSource(url, null, null).load().migrate();

        try (Connection c = DriverManager.getConnection(url)) {
            assertRow(c, "declared", "http https://intranet.example/health", "https://intranet.example/health",
                    "type:HTTP:https://intranet.example/health");
            assertRow(c, "token", "Status page", "http://127.0.0.1:9/status", null);
            assertRow(c, "query", "Mail link", "https://example.com/go?mail=a@b.example", null);
            assertRow(c, "service", "getty@tty1", "getty@tty1.service", null);
            // Two declared checks that differed only in their login: the older one gets the key the hub now makes.
            assertRow(c, "twin-a", "twin a", "https://twin.example/", "type:HTTP:https://twin.example/");
            assertRow(c, "twin-b", "twin b", "https://twin.example/", "type:HTTP:https://b:2@twin.example/");
        }
    }

    private static void insert(Connection c, String id, String name, String type, String target, String serverId,
                               String declaredKey, long createdAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO checks (id, name, type, target, server_id, run_from,"
                + " source, declared_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, id);
            s.setString(2, name);
            s.setString(3, type);
            s.setString(4, target);
            s.setString(5, serverId);
            s.setString(6, serverId == null ? "HUB" : "AGENT");
            s.setString(7, declaredKey == null ? "HUB" : "AGENT");
            s.setString(8, declaredKey);
            s.setLong(9, createdAt);
            s.executeUpdate();
        }
    }

    private static void assertRow(Connection c, String id, String name, String target, String declaredKey)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT name, target, declared_key FROM checks WHERE id = ?")) {
            s.setString(1, id);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                assertEquals(name, rs.getString("name"), id + " name");
                assertEquals(target, rs.getString("target"), id + " target");
                assertEquals(declaredKey, rs.getString("declared_key"), id + " declared key");
            }
        }
    }
}

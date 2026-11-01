package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.primelogiclab.svcmon.hub.secrets.SecretBox;
import io.primelogiclab.svcmon.hub.secrets.SecretKeys;
import io.primelogiclab.svcmon.hub.secrets.StoredSecrets;
import io.primelogiclab.svcmon.hub.store.CheckRepo;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** The command line for the key of the stored secrets: how they stand, a new key, and the way out of a lost one. */
class SecretsCommandTest {

    private record Result(int code, String out, String err) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SecretsCommand.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private final List<SingleConnectionDataSource> open = new ArrayList<>();

    private SingleConnectionDataSource database(Path dir) {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("svcmon.db"), true);
        open.add(ds);
        Flyway.configure().dataSource(ds).load().migrate();
        return ds;
    }

    /** Windows cannot delete the temp folder while the database is open. */
    @AfterEach
    void close() {
        open.forEach(SingleConnectionDataSource::destroy);
    }

    @Test
    void statusRotateKeyAndForgetUnreadable(@TempDir Path dir) throws Exception {
        SingleConnectionDataSource ds = database(dir);
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO alert_channels (id, name, type, config, statuses, enabled, created_at) VALUES ('c1', 'hook',"
                + " 'WEBHOOK', :c, 'DOWN', 1, 0)").param("c", "{\"url\":\"https://hooks.example/path-secret\","
                + "\"signingSecret\":\"signing-secret\",\"headers\":{\"X-Token\":\"header-secret\"}}").update();
        jdbc.sql("INSERT INTO checks (id, name, type, target, config, run_from, source, created_at) VALUES ('k1', 'keyed',"
                + " 'HTTP', 'http://127.0.0.1:9/', :c, 'HUB', 'HUB', 0)")
                .param("c", "{\"keyword\":\"ok\",\"headers\":{\"Authorization\":\"Bearer check-secret\"}}").update();
        String data = "--svcmon.data-dir=" + dir;
        Path file = dir.resolve(SecretKeys.FILE);

        Result before = run("secrets", "status", data);
        assertEquals(0, before.code(), before.err());
        assertTrue(before.out().contains("(missing)") && before.out().contains("No stored secret is encrypted yet"),
                before.out());

        // As the hub does at start.
        SecretBox box = new SecretBox(new SecretKeys(file));
        String first = box.keys().create().current();
        new StoredSecrets(ds, JsonMapper.builder().build(), box).sealAll();

        Result status = run("secrets", "status", data);
        assertEquals(0, status.code(), status.err());
        assertTrue(status.out().contains("(key " + first + ")") && status.out().contains("4 encrypted, 0 not yet"),
                status.out());

        Result rotated = run("secrets", "rotate-key", data);
        assertEquals(0, rotated.code(), rotated.err());
        assertTrue(rotated.out().contains("4 stored secret(s) encrypted with it again, and the old key removed"),
                rotated.out());
        SecretKeys.Ring ring = new SecretKeys(file).ring();
        assertNotEquals(first, ring.current());
        assertEquals(Set.of(ring.current()), ring.keys().keySet(), "the old key is gone");
        String raw = String.join("\n", jdbc.sql("SELECT config FROM alert_channels UNION ALL SELECT config FROM checks")
                .query(String.class).list());
        assertFalse(raw.contains("enc:v1:" + first) || raw.contains("-secret"), raw);
        CheckRepo checks = new CheckRepo(jdbc, JsonMapper.builder().build(), new SecretBox(new SecretKeys(file)));
        assertEquals(Map.of("Authorization", "Bearer check-secret"), checks.find("k1").orElseThrow().config().get("headers"));

        Result nothing = run("secrets", "forget-unreadable", data);
        assertEquals(0, nothing.code(), nothing.err());
        assertTrue(nothing.out().contains("nothing was removed"), nothing.out());

        // The key file is lost for good: the secrets go, the rest stays, and the channel stops sending.
        Files.delete(file);
        Result lost = run("secrets", "status", data);
        assertEquals(1, lost.code());
        assertTrue(lost.out().contains("4 stored secret(s) are encrypted with it and cannot be read"), lost.out());
        Result forgot = run("secrets", "forget-unreadable", data);
        assertEquals(0, forgot.code(), forgot.err());
        assertTrue(forgot.out().contains("Made a new key file")
                && forgot.out().contains("alert channel 'hook': url, signingSecret, header X-Token (switched off)")
                && forgot.out().contains("check 'keyed': header Authorization"), forgot.out());
        assertEquals(0, jdbc.sql("SELECT enabled FROM alert_channels WHERE id = 'c1'").query(Integer.class).single());
        assertEquals("{\"keyword\":\"ok\",\"headers\":{}}",
                jdbc.sql("SELECT config FROM checks WHERE id = 'k1'").query(String.class).single());
        Result after = run("secrets", "status", data);
        assertEquals(0, after.code(), after.out());
        assertTrue(after.out().contains("0 encrypted, 0 not yet (the hub encrypts them at start), 0 unreadable"),
                after.out());
    }

    @Test
    void anUnknownWordIsAUsageError() {
        assertEquals(2, run("secrets").code());
        assertEquals(2, run("secrets", "rotate").code());
        Result help = run("secrets", "--help");
        assertEquals(0, help.code());
        assertTrue(help.out().contains("rotate-key") && help.out().contains("forget-unreadable"), help.out());
    }
}

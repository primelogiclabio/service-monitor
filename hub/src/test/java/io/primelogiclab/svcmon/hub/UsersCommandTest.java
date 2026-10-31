package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.model.AuditEvent;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.SessionRecord;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.security.PasswordHasher;
import io.primelogiclab.svcmon.hub.store.AuditRepo;
import io.primelogiclab.svcmon.hub.store.SessionRepo;
import io.primelogiclab.svcmon.hub.store.UserRepo;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** The way back in when no administrator knows a password. */
class UsersCommandTest {

    private record Result(int code, String out, String err) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = UsersCommand.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private final List<SingleConnectionDataSource> open = new ArrayList<>();

    private JdbcClient database(Path dir) {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("svcmon.db"), true);
        open.add(ds);
        Flyway.configure().dataSource(ds).load().migrate();
        return JdbcClient.create(ds);
    }

    /** Windows cannot delete the temp folder while the database is open. */
    @AfterEach
    void close() {
        open.forEach(SingleConnectionDataSource::destroy);
    }

    @Test
    void aResetGivesATemporaryPasswordTurnsTheAccountOnAndEndsItsSignIns(@TempDir Path dir) {
        JdbcClient jdbc = database(dir);
        UserRepo users = new UserRepo(jdbc);
        users.insert(UserRecord.withPassword("u1", "root", Role.READ, new PasswordHasher().hash("forgotten password!"),
                false, true, 0, 0L, null));
        new SessionRepo(jdbc).insert(new SessionRecord("s1", "u1", "h", 0, 0, Long.MAX_VALUE, null, null,
                SessionRecord.PASSWORD));

        Result r = run("users", "reset", "ROOT", "--svcmon.data-dir=" + dir);
        assertEquals(0, r.code(), r.err());
        Matcher m = Pattern.compile("shown once\\):\\s+(\\S+)").matcher(r.out());
        assertTrue(m.find(), r.out());
        String temporary = m.group(1);
        assertTrue(r.out().contains("this user is one now"), "no administrator was left: " + r.out());

        UserRecord after = users.findById("u1").orElseThrow();
        assertTrue(new PasswordHasher().verify(temporary, after.passwordHash()));
        assertTrue(after.mustChangePassword(), "to be changed at the next sign-in");
        assertFalse(after.disabled());
        assertEquals(Role.ADMIN, after.role());
        assertTrue(new SessionRepo(jdbc).findByUser("u1").isEmpty());

        List<AuditEvent> audit = new AuditRepo(jdbc).find(null, null, 10);
        assertEquals(1, audit.size(), "the reset is in the audit");
        AuditEvent e = audit.get(0);
        assertEquals(List.of("sign_in", "password_reset", "ok", "users reset", "command_line", "root"),
                List.of(e.category(), e.kind(), e.outcome(), e.actor(), e.actorKind(), e.target()));
        assertFalse(e.toString().contains(temporary), "the temporary password is not");
    }

    @Test
    void anUnknownNameListsTheUsersAndBadArgumentsShowTheUsage(@TempDir Path dir) {
        new UserRepo(database(dir)).insert(UserRecord.withPassword("u1", "root", Role.ADMIN, "x", false, false, 0, null,
                null));
        Result unknown = run("users", "reset", "nobody", "--svcmon.data-dir=" + dir);
        assertEquals(1, unknown.code());
        assertTrue(unknown.err().contains("Users: root"), unknown.err());

        assertEquals(2, run("users").code());
        assertEquals(2, run("users", "delete", "root").code());
        assertTrue(run("users", "--help").out().contains("Usage:"));
    }

    @Test
    void aMistypedSettingIsRefusedLikeTheHubWould(@TempDir Path dir) {
        Result r = run("users", "reset", "root", "--svcmon.data-dir=" + dir, "--svcmon.data-dirr=x");
        assertEquals(1, r.code());
        assertTrue(r.err().contains("Unknown setting svcmon.data-dirr"), r.err());
    }
}

package io.primelogiclab.svcmon.hub;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.config.DataSourceConfig;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.config.SettingsGuard;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.security.PasswordHasher;
import io.primelogiclab.svcmon.hub.store.AuditRepo;
import io.primelogiclab.svcmon.hub.store.SessionRepo;
import io.primelogiclab.svcmon.hub.store.UserRepo;
import org.flywaydb.core.Flyway;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code java -jar svcmon-hub.jar users reset NAME [--svcmon.<setting>=<value>...]}: the way back in when no
 * administrator knows a password. It gives the user a new temporary password, printed once, that must be changed at
 * the next sign-in (from the internal network), turns the account back on, makes it an administrator if no other
 * administrator is left, and ends its sign-ins. It reads the same settings files as the hub, so it finds the same
 * database; it starts nothing else (no web server, no checks). Run it as someone who may write the hub's data folder.
 */
final class UsersCommand {

    private UsersCommand() {
    }

    /** Only what is needed to reach the database: the settings (checked as strictly as the hub's) and the data source. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HubProperties.class)
    @Import({SettingsGuard.class, DataSourceConfig.class})
    static class Context {
    }

    /** Runs the command. Returns the process exit code: 0 done, 1 refused or failed, 2 bad arguments. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> words = new ArrayList<>();
        List<String> settings = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-h") || args[i].equals("--help")) {
                out.println(usage());
                return 0;
            }
            if (args[i].startsWith("--")) {
                settings.add(args[i]);
            } else {
                words.add(args[i]);
            }
        }
        if (words.size() != 2 || !words.get(0).equals("reset")) {
            err.println(usage());
            return 2;
        }
        String name = words.get(1);

        SpringApplication app = new SpringApplication(Context.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        // A mistyped setting is reported below in one line, not as Spring's stack trace.
        app.setDefaultProperties(Map.of("logging.level.root", "warn",
                "logging.level.org.springframework.boot.SpringApplication", "off"));
        try (ConfigurableApplicationContext ctx = app.run(settings.toArray(String[]::new))) {
            DataSource ds = ctx.getBean(DataSource.class);
            Flyway.configure().dataSource(ds).load().migrate();
            JdbcClient jdbc = JdbcClient.create(ds);
            UserRepo users = new UserRepo(jdbc);
            UserRecord user = users.findByUsername(name).orElse(null);
            if (user == null) {
                List<String> names = users.findAll().stream().map(UserRecord::username).toList();
                err.println("There is no user '" + name + "'." + (names.isEmpty()
                        ? " The hub has no users yet: start it once to create the first administrator."
                        : " Users: " + String.join(", ", names)));
                return 1;
            }
            String temporary = PasswordHasher.generate();
            users.setPassword(user.id(), new PasswordHasher().hash(temporary), true, null);
            users.setDisabled(user.id(), false);
            boolean promoted = user.role() != Role.ADMIN && users.countEnabledAdmins() == 0;
            if (promoted) {
                users.setRole(user.id(), Role.ADMIN);
            }
            int ended = new SessionRepo(jdbc).deleteByUser(user.id());
            // Written straight to the database: this runs with the hub stopped, from the hub's machine.
            new AuditRepo(jdbc).insert(AuditService.event(System.currentTimeMillis(), AuditService.SIGN_IN,
                    "password_reset", "ok", "users reset", "command_line", null, null, null, user.username(),
                    promoted ? "made an administrator" : null));

            out.println();
            out.println("==================================================================");
            out.println(" Temporary password for user '" + user.username() + "' (shown once):");
            out.println("   " + temporary);
            out.println(" Sign in with it from the internal network and choose a new one.");
            if (promoted) {
                out.println(" No other administrator was left, so this user is one now.");
            }
            if (ended > 0) {
                out.println(" Ended " + ended + " sign-in(s) of this user.");
            }
            out.println("==================================================================");
            out.println();
            return 0;
        } catch (RuntimeException e) {
            err.println("Could not reset the password: " + e.getMessage());
            return 1;
        }
    }

    static String usage() {
        return "Usage: java -jar svcmon-hub.jar users reset NAME [--svcmon.data-dir=DIR ...]\n"
                + "  Gives the user NAME a temporary password, printed once, to be changed at the next sign-in.\n"
                + "  Reads the same settings as the hub (application.yml in the current folder, --svcmon.* flags).";
    }
}

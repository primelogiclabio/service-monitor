package io.primelogiclab.svcmon.hub;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.sql.DataSource;

import io.primelogiclab.svcmon.hub.config.DataSourceConfig;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.config.SettingsGuard;
import io.primelogiclab.svcmon.hub.secrets.SecretBox;
import io.primelogiclab.svcmon.hub.secrets.SecretKeys;
import io.primelogiclab.svcmon.hub.secrets.StoredSecrets;
import org.flywaydb.core.Flyway;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code java -jar svcmon-hub.jar secrets status|rotate-key|forget-unreadable [--svcmon.<setting>=<value>...]}: the
 * key the hub encrypts its stored secrets with (alert channel secrets, HTTP check header values). It reads the same
 * settings files as the hub, so it finds the same database and key file, and starts nothing else. Run it as someone
 * who may write the hub's data folder; stopping the hub first is safest for rotate-key and forget-unreadable.
 */
final class SecretsCommand {

    private static final Set<String> COMMANDS = Set.of("status", "rotate-key", "forget-unreadable");

    private SecretsCommand() {
    }

    /** Only what is needed to reach the database: the settings (checked as strictly as the hub's) and the data source. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HubProperties.class)
    @Import({SettingsGuard.class, DataSourceConfig.class})
    static class Context {
    }

    /** Runs the command. Returns the process exit code: 0 done, 1 refused, failed or a problem found, 2 bad arguments. */
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
        if (words.size() != 1 || !COMMANDS.contains(words.get(0))) {
            err.println(usage());
            return 2;
        }
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
            SecretBox box = new SecretBox(SecretKeys.of(ctx.getBean(HubProperties.class)));
            StoredSecrets stored = new StoredSecrets(ds, JsonMapper.builder().build(), box);
            return switch (words.get(0)) {
                case "status" -> status(box.keys(), stored, out);
                case "rotate-key" -> rotate(box.keys(), stored, out, err);
                default -> forget(box.keys(), stored, out);
            };
        } catch (RuntimeException e) {
            err.println("Could not run 'secrets " + words.get(0) + "': " + e.getMessage());
            return 1;
        }
    }

    private static int status(SecretKeys keys, StoredSecrets stored, PrintStream out) {
        if (!keys.exists()) {
            int sealed = stored.countSealed();
            out.println("Key file: " + keys.file() + " (missing)");
            out.println(sealed == 0
                    ? "No stored secret is encrypted yet; the hub makes the key at its next start."
                    : sealed + " stored secret(s) are encrypted with it and cannot be read: restore the file, or run"
                            + " 'secrets forget-unreadable'.");
            return sealed == 0 ? 0 : 1;
        }
        SecretKeys.Ring ring = keys.ring();
        int older = ring.keys().size() - 1;
        out.println("Key file: " + keys.file() + " (key " + ring.current()
                + (older > 0 ? ", and " + older + " older one(s) still in use" : "") + ")");
        StoredSecrets.Counts counts = stored.count();
        out.println("Stored secrets: " + counts.sealed() + " encrypted, " + counts.plain()
                + " not yet (the hub encrypts them at start), " + counts.unreadable() + " unreadable");
        counts.unreadableIn().forEach(where -> out.println("  cannot be read: " + where));
        return counts.unreadable() == 0 ? 0 : 1;
    }

    /**
     * A new key, every value sealed again with it, then the old keys dropped. The file holds old and new until the
     * values are done, so a stop at any point leaves every value readable.
     */
    private static int rotate(SecretKeys keys, StoredSecrets stored, PrintStream out, PrintStream err) {
        if (!keys.exists()) {
            err.println("There is no key to replace: " + keys.file() + " is missing. Start the hub once to make it.");
            return 1;
        }
        StoredSecrets.Counts before = stored.count();
        if (before.unreadable() > 0) {
            err.println(before.unreadable() + " stored secret(s) cannot be read with the keys in " + keys.file()
                    + ": restore the file, or run 'secrets forget-unreadable' first.");
            return 1;
        }
        SecretKeys.Ring next = SecretKeys.withNewKey(keys.ring());
        keys.replace(next);
        int sealed = stored.sealAll();
        Set<String> used = stored.count().keyIds();
        Map<String, SecretKey> kept = new LinkedHashMap<>();
        next.keys().forEach((id, key) -> {
            if (id.equals(next.current()) || used.contains(id)) {
                kept.put(id, key);
            }
        });
        keys.replace(new SecretKeys.Ring(next.current(), Map.copyOf(kept)));
        stored.compact();
        out.println("New key " + next.current() + " in " + keys.file() + "; " + sealed
                + " stored secret(s) encrypted with it again" + (kept.size() == 1
                ? ", and the old key removed."
                : "; " + (kept.size() - 1) + " older key(s) are still in use and were kept: run it again."));
        return 0;
    }

    /** For a key file that is lost for good: what no key opens is removed, and a missing file is made anew. */
    private static int forget(SecretKeys keys, StoredSecrets stored, PrintStream out) {
        boolean made = !keys.exists();
        if (made) {
            keys.create();
            out.println("Made a new key file: " + keys.file());
        }
        List<String> changed = stored.forgetUnreadable();
        if (changed.isEmpty()) {
            out.println("Every stored secret can be read; nothing was removed.");
        } else {
            stored.compact();
            out.println("Removed the secrets no key can read. Enter them again in the dashboard or with the CLI:");
            changed.forEach(line -> out.println("  " + line));
        }
        return 0;
    }

    static String usage() {
        return "Usage: java -jar svcmon-hub.jar secrets status|rotate-key|forget-unreadable [--svcmon.data-dir=DIR ...]\n"
                + "  status             where the key file is and how the stored secrets stand\n"
                + "  rotate-key         makes a new key and encrypts every stored secret with it again\n"
                + "  forget-unreadable  removes the stored secrets no key can read (when the key file is lost)\n"
                + "  Reads the same settings as the hub (application.yml in the current folder, --svcmon.* flags).";
    }
}

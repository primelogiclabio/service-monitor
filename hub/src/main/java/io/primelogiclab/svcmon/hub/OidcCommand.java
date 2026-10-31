package io.primelogiclab.svcmon.hub;

import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.config.SettingsGuard;
import io.primelogiclab.svcmon.hub.oidc.FileKey;
import io.primelogiclab.svcmon.hub.oidc.HubKey;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * {@code java -jar svcmon-hub.jar oidc key|rotate-key [--svcmon.<setting>=<value>...]}: the hub's own key pair for
 * signing in to the identity provider (see {@link HubKey}). {@code key} prints the public key, making the pair first
 * when there is none, so the provider can be set up before the hub starts; for a pair the hub was given in files
 * (svcmon.oidc.private-key-file, see {@link FileKey}) it checks those and prints the same, with the expiry. {@code rotate-key} replaces the pair: the
 * old key stops working at once, a running hub uses the new one from its next sign-in, and a provider that fetches
 * /api/v1/oidc/jwks picks it up by itself (one that was given the key by hand needs the new one). Only the public
 * half is ever printed. It reads the same settings files as the hub, so it finds the same data folder; run it as
 * someone who may write that folder.
 */
final class OidcCommand {

    private OidcCommand() {
    }

    /** Only the settings, checked as strictly as the hub's. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HubProperties.class)
    @Import(SettingsGuard.class)
    static class Context {
    }

    /** Runs the command. Returns the process exit code: 0 done, 1 failed, 2 bad arguments. */
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
        if (words.size() != 1 || !(words.get(0).equals("key") || words.get(0).equals("rotate-key"))) {
            err.println(usage());
            return 2;
        }
        SpringApplication app = new SpringApplication(Context.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of("logging.level.root", "warn",
                "logging.level.org.springframework.boot.SpringApplication", "off"));
        try (ConfigurableApplicationContext ctx = app.run(settings.toArray(String[]::new))) {
            HubProperties props = ctx.getBean(HubProperties.class);
            boolean rotate = words.get(0).equals("rotate-key");
            HubProperties.Oidc oidc = props.oidc() == null ? HubProperties.Oidc.DEFAULT : props.oidc();
            if (oidc.privateKeyFile() != null || oidc.certificateFile() != null) {
                return keyFromFiles(oidc, rotate, out, err);
            }
            HubKey key = new HubKey(props.dataDir());
            boolean existed = key.exists();
            RSAKey k = rotate ? key.rotate() : key.current();
            out.println();
            if (rotate) {
                out.println("Made a new key pair in " + key.file() + "; the old key no longer works.");
                out.println("A provider that fetches /api/v1/oidc/jwks picks it up by itself; give any other the new"
                        + " public key below.");
            } else if (!existed) {
                out.println("Made the hub's key pair in " + key.file() + ".");
            }
            out.println("Key id: " + k.getKeyID() + " (RSA, " + k.size() + " bits)");
            out.println();
            out.println("Public key (PEM):");
            out.print(HubKey.pem(k));
            out.println();
            out.println("Public key (JWK set):");
            out.println(new JWKSet(k.toPublicJWK()));
            out.println();
            return 0;
        } catch (RuntimeException e) {
            err.println("Could not " + (words.get(0).equals("key") ? "read" : "replace") + " the hub's key: "
                    + e.getMessage());
            return 1;
        }
    }

    /** The hub was given its pair in files (signing-keys-tool's): show it; a new one is made with that tool. */
    private static int keyFromFiles(HubProperties.Oidc oidc, boolean rotate, PrintStream out, PrintStream err) {
        if (rotate) {
            err.println("The hub uses the key pair in " + oidc.privateKeyFile() + " (svcmon.oidc.private-key-file),"
                    + " made outside the hub. Make a new pair with signing-keys-tool (generate --bits 3072), point"
                    + " private-key-file and certificate-file at it, and give the provider the new certificate or"
                    + " public key.");
            return 1;
        }
        if (oidc.privateKeyFile() == null || oidc.certificateFile() == null) {
            err.println("svcmon.oidc.private-key-file and certificate-file go together; set both.");
            return 1;
        }
        FileKey file = new FileKey(oidc.privateKeyFile(), oidc.certificateFile());
        RSAKey k = file.current();
        Instant expires = file.expiresAt();
        long days = FileKey.daysLeft(expires, Instant.now());
        out.println();
        out.println("The hub uses the key pair in " + file.keyFile() + " and " + file.certificateFile() + ".");
        out.println("Key id: " + k.getKeyID() + " (RSA, " + k.size() + " bits)");
        out.println("Certificate expires " + expires.toString().substring(0, 10)
                + (days < 0 ? " (expired)" : " (" + days + " days left)"));
        out.println();
        out.println("Public key (PEM):");
        out.print(HubKey.pem(k));
        out.println();
        out.println("Public key (JWK set, with the certificate):");
        out.println(new JWKSet(k.toPublicJWK()));
        out.println();
        return 0;
    }

    static String usage() {
        return "Usage: java -jar svcmon-hub.jar oidc key|rotate-key [--svcmon.data-dir=DIR ...]\n"
                + "  key         prints the hub's public key for the identity provider (makes the key pair if needed)\n"
                + "  rotate-key  replaces the key pair; the old key stops working at once\n"
                + "  Reads the same settings as the hub (application.yml in the current folder, --svcmon.* flags).";
    }
}

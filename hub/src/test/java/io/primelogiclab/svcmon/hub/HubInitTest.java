package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ByteArrayResource;

/** The settings template that "java -jar svcmon-hub.jar init" writes, and the command itself. */
class HubInitTest {

    private record Result(int code, String out, String err) {
    }

    private static Result run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = HubInit.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Result(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static String template() throws IOException {
        try (var in = HubInit.class.getResourceAsStream(HubInit.TEMPLATE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void writesTheTemplateIntoANewFolder() throws IOException {
        Path dir = Files.createTempDirectory("svcmon-init").resolve("new").resolve("hub");
        Result r = run("init", dir.toString());
        assertEquals(0, r.code(), r.err());
        assertEquals(template(), Files.readString(dir.resolve("application.yml")));
        assertTrue(r.out().contains(dir.resolve("application.yml").toAbsolutePath().normalize().toString()), r.out());
    }

    @Test
    void neverReplacesAnExistingFileUnlessAskedTo() throws IOException {
        Path dir = Files.createTempDirectory("svcmon-init-existing");
        Path file = Files.writeString(dir.resolve("application.yml"), "svcmon:\n  retention-days: 7\n");
        Result refused = run("init", dir.toString());
        assertEquals(1, refused.code());
        assertTrue(refused.err().contains("already exists"), refused.err());
        assertEquals("svcmon:\n  retention-days: 7\n", Files.readString(file), "the file must be left alone");

        assertEquals(0, run("init", dir.toString(), "--force").code());
        assertEquals(template(), Files.readString(file));
    }

    @Test
    void stdoutPrintsTheTemplateAndWritesNothing() throws IOException {
        Path dir = Files.createTempDirectory("svcmon-init-stdout");
        Result r = run("init", dir.toString(), "--stdout");
        assertEquals(0, r.code());
        assertEquals(template(), r.out());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void badArgumentsAreRefusedWithTheUsage() {
        for (String[] args : new String[][] {{"init", "--bogus"}, {"init", "a", "b"}}) {
            Result r = run(args);
            assertEquals(2, r.code(), String.join(" ", args));
            assertTrue(r.err().contains("Usage:"), r.err());
        }
        assertEquals(0, run("init", "--help").code());
    }

    /** Every setting the hub reads is in the template, so nothing has to be looked up elsewhere. */
    @Test
    void theTemplateMentionsEverySetting() throws IOException {
        String text = template();
        Set<String> notForUsers = Set.of("embedded-ui"); // only tests change where the jar's dashboard sits
        List<String> missing = new ArrayList<>();
        for (Class<?> type : new Class<?>[] {HubProperties.class, HubProperties.Users.class, HubProperties.Sessions.class,
                HubProperties.Access.class, HubProperties.Audit.class, HubProperties.Oidc.class}) {
            for (RecordComponent c : type.getRecordComponents()) {
                String name = kebab(c.getName());
                if (!notForUsers.contains(name) && !text.contains(name + ":")) {
                    missing.add(type.getSimpleName() + "." + name);
                }
            }
        }
        assertEquals(List.of(), missing, "settings the template does not show");
    }

    /** Uncommenting every setting gives a valid file whose values are the hub's defaults, where there is one. */
    @Test
    void theTemplateWithEverySettingUncommentedBindsToTheDefaults() throws IOException {
        String uncommented = template().lines()
                .map(l -> l.startsWith("# ") ? l.substring(2) : l)
                .collect(Collectors.joining("\n", "", "\n"));
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ByteArrayResource(uncommented.getBytes(StandardCharsets.UTF_8)));
        Properties flat = yaml.getObject();
        assertFalse(flat.isEmpty());

        HubProperties p = new Binder(new MapConfigurationPropertySource(flat)).bind("svcmon", HubProperties.class).get();
        assertEquals(30, p.retentionDays());
        assertEquals(30, p.agentHeartbeatSeconds());
        assertEquals(90, p.agentStaleSeconds());
        assertTrue(p.uiEnabled());
        assertTrue(p.agentDeclaredChecks());
        assertEquals(200, p.agentDeclaredChecksLimit());
        assertEquals(50, p.liveMaxSessions());
        assertFalse(p.allowInsecureAlertUrls());
        assertEquals(List.of("https://monitor.example.com"), p.corsAllowedOrigins());
        assertEquals(HubProperties.Users.DEFAULT, p.users());
        assertEquals(HubProperties.Sessions.DEFAULT, p.sessions());
        assertFalse(p.access().allowOutside());
        assertEquals(HubProperties.Access.DEFAULT.internalNetworks(), p.access().internalNetworks());
        assertEquals(List.of("127.0.0.2"), p.access().publicProxies());
        assertEquals(HubProperties.Audit.DEFAULT, p.audit());
        assertEquals(HubProperties.Oidc.ClientAuth.PRIVATE_KEY_JWT, p.oidc().clientAuth());
        assertEquals(HubProperties.Oidc.DEFAULT.scopes(), p.oidc().scopes());
        assertEquals(HubProperties.Oidc.DEFAULT.signingAlgorithm(), p.oidc().signingAlgorithm());
        assertEquals(HubProperties.Oidc.DEFAULT.rolesClaim(), p.oidc().rolesClaim());
        assertEquals(HubProperties.Oidc.DEFAULT.usernameClaim(), p.oidc().usernameClaim());
        assertEquals(HubProperties.Oidc.DEFAULT.buttonLabel(), p.oidc().buttonLabel());
    }

    @Test
    void theTemplateAsShippedChangesNothing() throws IOException {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ByteArrayResource(template().getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(null, yaml.getObject());
        assertTrue(yaml.getObject().isEmpty(), "every setting is commented out: " + yaml.getObject());
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT);
    }
}

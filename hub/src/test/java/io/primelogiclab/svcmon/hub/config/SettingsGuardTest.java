package io.primelogiclab.svcmon.hub.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.env.MockEnvironment;

import io.primelogiclab.svcmon.hub.HubApplication;

class SettingsGuardTest {

    @Test
    void knownSettingsPassInEveryFormTheBinderAccepts() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("svcmon.data-dir", "./data")
                .withProperty("svcmon.retentionDays", "10")
                .withProperty("svcmon.agent_stale_seconds", "60")
                .withProperty("svcmon.cors-allowed-origins[0]", "https://monitor.example.com")
                .withProperty("svcmon.ui.proxy.headers.X-Web-Client", "1")
                .withProperty("svcmon.jwt.issuer", "https://auth.example.com")
                .withProperty("svcmon.sessions.max-hours", "4")
                .withProperty("svcmon.users.initialAdminUsername", "root")
                .withProperty("svcmon.version", "1.2.3")
                .withProperty("svcmon.alert-retry-delays-ms", "0,10")
                .withProperty("server.port", "0")
                .withProperty("spring.application.name", "x");
        assertEquals(List.of(), SettingsGuard.problems(env, Map.of()));
    }

    @Test
    void aTypoIsNamedWithWhereItCameFrom() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("svcmon.retention-dayz", "10")
                .withProperty("svcmon.jwt.isuer", "https://auth.example.com")
                .withProperty("svcmon.ui.proxy.header.X-Web-Client", "1");
        List<String> problems = SettingsGuard.problems(env, Map.of());
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("Unknown setting svcmon.retention-dayz (mockProperties)"), problems.get(0));
        assertTrue(problems.stream().anyMatch(p -> p.contains("svcmon.jwt.isuer")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("svcmon.ui.proxy.header.X-Web-Client")), problems.toString());
    }

    @Test
    void environmentVariablesAreNotChecked() {
        MockEnvironment env = new MockEnvironment();
        // The CLI's own variables, which the binder would read as svcmon.token and svcmon.hub.
        env.getPropertySources().addLast(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("SVCMON_TOKEN", "x", "SVCMON_HUB", "http://localhost:8080")));
        assertEquals(List.of(), SettingsGuard.problems(env, Map.of()));
    }

    @Test
    void aRemovedSettingSaysWhatToUseInstead() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("svcmon.old-group.enabled", "false")
                .withProperty("svcmon.old-groupie", "1");
        List<String> problems = SettingsGuard.problems(env, Map.of("old-group", "use svcmon.new-group instead"));
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.contains("Setting svcmon.old-group.enabled (mockProperties) was removed: use svcmon.new-group instead"),
                problems.toString());
        assertTrue(problems.contains("Unknown setting svcmon.old-groupie (mockProperties)"), problems.toString());
    }

    /** The settings of the old local admin account stop the hub, so a leftover enabled: false is never dropped silently. */
    @Test
    void theOldLocalAdminSettingsSayWhatReplacedThem() {
        MockEnvironment env = new MockEnvironment().withProperty("svcmon.local-admin.enabled", "false");
        List<String> problems = SettingsGuard.problems(env, SettingsGuard.REMOVED);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("was removed") && problems.get(0).contains("svcmon.users.enabled"),
                problems.get(0));
    }

    @Test
    void theSameSettingFromTwoPlacesIsReportedOnce() {
        MockEnvironment env = new MockEnvironment().withProperty("svcmon.bogus", "1");
        Properties again = new Properties();
        again.setProperty("svcmon.Bogus", "2");
        env.getPropertySources().addLast(new PropertiesPropertySource("file", again));
        assertEquals(1, SettingsGuard.problems(env, Map.of()).size());
    }

    /** What 'init' writes, with every line uncommented, is accepted: the template and the guard agree. */
    @Test
    void theInitTemplateWithEverySettingUncommentedPasses() throws IOException {
        String template;
        try (var in = SettingsGuard.class.getResourceAsStream("/init/application.yml")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String uncommented = template.lines()
                .map(l -> l.startsWith("# ") ? l.substring(2) : l)
                .collect(Collectors.joining("\n", "", "\n"));
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ByteArrayResource(uncommented.getBytes(StandardCharsets.UTF_8)));
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addLast(new PropertiesPropertySource("template", yaml.getObject()));
        assertFalse(yaml.getObject().isEmpty());
        assertEquals(List.of(), SettingsGuard.problems(env, Map.of()));
    }

    /** A setting read with @Value somewhere must be listed, or the guard would refuse a hub that sets it. */
    @Test
    void everySettingReadWithValueIsKnown() throws IOException {
        Pattern value = Pattern.compile("\\$\\{svcmon\\.([a-z0-9-]+)");
        Set<String> read = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = value.matcher(Files.readString(f));
                while (m.find()) {
                    read.add(m.group(1));
                }
            }
        }
        assertFalse(read.isEmpty());
        assertEquals(read, new TreeSet<>(SettingsGuard.READ_ELSEWHERE));
    }

    @Test
    void anUnknownSettingStopsTheHubBeforeTheDatabaseIsMade(@TempDir Path dir) {
        Path data = dir.resolve("data");
        SpringApplication app = new SpringApplication(HubApplication.class);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> app.run(
                "--server.port=0", "--svcmon.data-dir=" + data, "--svcmon.retention-dayz=3"));
        assertTrue(e.getMessage().startsWith("Unknown setting svcmon.retention-dayz ("), e.getMessage());
        assertTrue(e.getMessage().contains("init"), e.getMessage());
        assertFalse(Files.exists(data), "nothing may be created before the settings are checked");
    }
}

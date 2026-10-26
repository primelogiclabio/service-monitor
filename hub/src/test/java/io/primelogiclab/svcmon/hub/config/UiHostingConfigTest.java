package io.primelogiclab.svcmon.hub.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Where the dashboard comes from, and what its config.json says, without starting a hub. */
class UiHostingConfigTest {

    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();

    private static HubProperties props(boolean enabled, Path uiDir, String embedded, HubProperties.Ui ui) {
        return new HubProperties(Path.of("."), 30, 30, 90, null, false, uiDir, List.of(), false, 50, null, true, 200,
                HubProperties.Jwt.DISABLED, HubProperties.LocalAdmin.DEFAULT, enabled, embedded, ui);
    }

    private static HubProperties.Ui proxy(String apiBase, Map<String, String> headers, String signIn, String signOut) {
        return new HubProperties.Ui(new HubProperties.Proxy(true, apiBase, headers, signIn, signOut));
    }

    @Test
    void aJarWithoutADashboardServesNoneAndSaysHow() {
        UiHostingConfig none = new UiHostingConfig(props(true, null, "classpath:/no-dashboard-here/", HubProperties.Ui.DEFAULT), LOADER);
        assertTrue(none.served().startsWith("not served"), none.served());
        assertTrue(none.served().contains("svcmon.ui-dir"), none.served());
        assertTrue(none.dashboardConfig().route(null).isEmpty(), "no config.json route without a dashboard inside");
    }

    @Test
    void theDashboardInsideTheJarIsUsedWhenThereIsNoFolder() {
        UiHostingConfig jar = new UiHostingConfig(props(true, null, "classpath:/test-ui", HubProperties.Ui.DEFAULT), LOADER);
        assertTrue(jar.served().contains("inside the hub jar"), jar.served());
    }

    @Test
    void aFolderWinsOverTheDashboardInsideTheJar() throws IOException {
        Path dir = Files.createTempDirectory("svcmon-ui-folder");
        Files.writeString(dir.resolve("index.html"), "x");
        UiHostingConfig folder = new UiHostingConfig(props(true, dir, "classpath:/test-ui/", HubProperties.Ui.DEFAULT), LOADER);
        assertEquals("served from " + dir.toAbsolutePath().normalize(), folder.served());
    }

    @Test
    void aWrongFolderDoesNotFallBackToTheJar() {
        UiHostingConfig wrong = new UiHostingConfig(
                props(true, Path.of("does-not-exist-svcmon"), "classpath:/test-ui/", HubProperties.Ui.DEFAULT), LOADER);
        assertTrue(wrong.served().startsWith("not served"), wrong.served());
    }

    @Test
    void theSwitchMakesTheHubHeadlessWhateverIsAvailable() throws IOException {
        Path dir = Files.createTempDirectory("svcmon-ui-off");
        Files.writeString(dir.resolve("index.html"), "x");
        for (Path folder : new Path[] {null, dir}) {
            UiHostingConfig off = new UiHostingConfig(props(false, folder, "classpath:/test-ui/", HubProperties.Ui.DEFAULT), LOADER);
            assertTrue(off.served().startsWith("off"), off.served());
        }
    }

    @Test
    void configJsonIsFalseUntilTheProxyIsOn() {
        assertEquals("{\"proxy\":false}", UiSettings.json(HubProperties.Ui.DEFAULT));
        assertEquals("{\"proxy\":false}", UiSettings.json(null));
    }

    @Test
    void theProxyDefaultsMatchTheDashboards() {
        assertEquals("{\"proxy\":{\"apiBase\":\"/web-api\",\"headers\":{\"X-Web-Client\":\"1\"},"
                        + "\"signInUrl\":\"/oauth2/start\",\"signOutUrl\":\"/oauth2/sign_out\"}}",
                UiSettings.json(proxy("/web-api", null, "/oauth2/start", "/oauth2/sign_out")));
    }

    @Test
    void aWrongValueStopsTheHubAtStartUpAndNamesTheSetting() {
        record Case(String setting, HubProperties.Ui ui) {
        }
        for (Case c : List.of(
                new Case("api-base", proxy("web-api", null, "/a", "/b")),
                new Case("api-base", proxy("//evil.example", null, "/a", "/b")),
                new Case("api-base", proxy("javascript:alert(1)", null, "/a", "/b")),
                new Case("headers", proxy("/x", Map.of("Authorization", "Bearer x"), "/a", "/b")),
                new Case("headers", proxy("/x", Map.of("Bad Name", "1"), "/a", "/b")),
                new Case("sign-in-url", proxy("/x", null, "javascript:alert(1)", "/b")),
                new Case("sign-in-url", proxy("/x", null, "//evil.example/in", "/b")),
                new Case("sign-out-url", proxy("/x", null, "/a", "data:text/html,x")))) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> UiSettings.json(c.ui()), c.toString());
            assertTrue(e.getMessage().contains("svcmon.ui.proxy." + c.setting()), e.getMessage());
        }
        // The hub itself refuses to start with the same value.
        assertThrows(IllegalStateException.class,
                () -> new UiHostingConfig(props(true, null, "classpath:/ui/", proxy("x", null, "/a", "/b")), LOADER));
        // An http(s) address is fine for the sign-in and sign-out pages, which may live on another site.
        assertFalse(UiSettings.json(proxy("/api", null, "https://login.example/in", "http://login.example/out")).isEmpty());
    }
}

package io.primelogiclab.svcmon.hub.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Where the dashboard comes from, without starting a hub. */
class UiHostingConfigTest {

    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();

    private static HubProperties props(boolean enabled, Path uiDir, String embedded) {
        return new HubProperties(Path.of("."), 30, 30, 90, null, false, uiDir, List.of(), false, 50, null, true, 200,
                HubProperties.Users.DEFAULT, HubProperties.Sessions.DEFAULT, enabled, embedded,
                HubProperties.Access.DEFAULT, HubProperties.Audit.DEFAULT, HubProperties.Oidc.DEFAULT);
    }

    @Test
    void aJarWithoutADashboardServesNoneAndSaysHow() {
        UiHostingConfig none = new UiHostingConfig(props(true, null, "classpath:/no-dashboard-here/"), LOADER);
        assertTrue(none.served().startsWith("not served"), none.served());
        assertTrue(none.served().contains("svcmon.ui-dir"), none.served());
        assertTrue(none.dashboardConfig().route(null).isEmpty(), "no config.json route without a dashboard inside");
    }

    @Test
    void theDashboardInsideTheJarIsUsedWhenThereIsNoFolder() {
        UiHostingConfig jar = new UiHostingConfig(props(true, null, "classpath:/test-ui"), LOADER);
        assertTrue(jar.served().contains("inside the hub jar"), jar.served());
    }

    @Test
    void aFolderWinsOverTheDashboardInsideTheJar() throws IOException {
        Path dir = Files.createTempDirectory("svcmon-ui-folder");
        Files.writeString(dir.resolve("index.html"), "x");
        UiHostingConfig folder = new UiHostingConfig(props(true, dir, "classpath:/test-ui/"), LOADER);
        assertEquals("served from " + dir.toAbsolutePath().normalize(), folder.served());
    }

    @Test
    void aWrongFolderDoesNotFallBackToTheJar() {
        UiHostingConfig wrong = new UiHostingConfig(
                props(true, Path.of("does-not-exist-svcmon"), "classpath:/test-ui/"), LOADER);
        assertTrue(wrong.served().startsWith("not served"), wrong.served());
    }

    @Test
    void theSwitchMakesTheHubHeadlessWhateverIsAvailable() throws IOException {
        Path dir = Files.createTempDirectory("svcmon-ui-off");
        Files.writeString(dir.resolve("index.html"), "x");
        for (Path folder : new Path[] {null, dir}) {
            UiHostingConfig off = new UiHostingConfig(props(false, folder, "classpath:/test-ui/"), LOADER);
            assertTrue(off.served().startsWith("off"), off.served());
        }
    }
}

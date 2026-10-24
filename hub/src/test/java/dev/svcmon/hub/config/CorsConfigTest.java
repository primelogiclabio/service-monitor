package dev.svcmon.hub.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class CorsConfigTest {

    @Test
    void acceptsExactOriginsAndNormalizesCase() {
        assertEquals(
                List.of("http://localhost:5173", "https://monitor.example.com"),
                CorsConfig.validate(List.of("http://localhost:5173", "  HTTPS://Monitor.Example.com ")));
    }

    @Test
    void ignoresBlankEntriesAndNull() {
        assertEquals(List.of(), CorsConfig.validate(null));
        assertEquals(List.of(), CorsConfig.validate(List.of()));
        assertEquals(List.of("https://a.example"), CorsConfig.validate(Arrays.asList("", "  ", null, "https://a.example")));
    }

    @Test
    void rejectsAnythingThatIsNotAnExactOrigin() {
        for (String bad : new String[] {
                "*", "monitor.example.com", "https://monitor.example.com/", "https://monitor.example.com/app",
                "ftp://monitor.example.com", "https://user@monitor.example.com", "http://monitor.example.com?x=1",
                "https://monitor.example.com#top", "http://"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CorsConfig.validate(List.of(bad)), bad);
            assertTrue(e.getMessage().contains("svcmon.cors-allowed-origins"), e.getMessage());
        }
    }

    @Test
    void uiDirResolutionIsStrictAboutWhatItServes() throws IOException {
        assertNull(UiHostingConfig.resolve(null));
        assertNull(UiHostingConfig.resolve(Path.of("")));
        Path dir = Files.createTempDirectory("svcmon-ui-resolve");
        assertNull(UiHostingConfig.resolve(dir.resolve("does-not-exist")));
        Path file = Files.writeString(dir.resolve("a-file.txt"), "x");
        assertNull(UiHostingConfig.resolve(file), "a file is not a UI folder");
        Path resolved = UiHostingConfig.resolve(dir.resolve("..").resolve(dir.getFileName()));
        assertNotNull(resolved);
        assertEquals(dir.toAbsolutePath().normalize(), resolved);
    }
}

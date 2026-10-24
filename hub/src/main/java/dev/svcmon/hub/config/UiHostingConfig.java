package dev.svcmon.hub.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Optionally serves the built web UI from a folder (svcmon.ui-dir) so the hub and the dashboard share one
 * address. The files are public; sign-in happens in the page with an API token. API routes always win over
 * files because controllers are matched before the resource handlers.
 */
@Configuration
public class UiHostingConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(UiHostingConfig.class);

    private final Path uiDir;

    public UiHostingConfig(HubProperties props) {
        this.uiDir = resolve(props.uiDir());
    }

    /** The folder to serve, or null when UI hosting is off or the folder is unusable. */
    static Path resolve(Path configured) {
        if (configured == null || configured.toString().isBlank()) {
            return null;
        }
        Path dir = configured.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            // A monitoring hub should keep monitoring even if the optional UI folder is wrong.
            log.warn("svcmon.ui-dir {} is not a directory; the web UI will not be served", dir);
            return null;
        }
        if (!Files.isRegularFile(dir.resolve("index.html"))) {
            log.warn("svcmon.ui-dir {} has no index.html; is this the UI project's dist folder?", dir);
        }
        log.info("Serving the web UI from {}", dir);
        return dir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (uiDir == null) {
            return;
        }
        String base = uiDir.toUri().toString();
        // Build output names files under assets/ with a content hash, so they can be cached for good.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(base + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations(base)
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (uiDir != null) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }
}

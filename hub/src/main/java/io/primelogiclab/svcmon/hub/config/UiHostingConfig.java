package io.primelogiclab.svcmon.hub.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Serves the web dashboard so the hub and the dashboard share one address. It comes from a folder
 * (svcmon.ui-dir) when one is set, otherwise from inside the jar when the jar was built with it, and
 * svcmon.ui-enabled=false turns it off. The files are public; people sign in from the page with a user name and password.
 * API routes always win over files because controllers are matched before the resource handlers.
 */
@Configuration
public class UiHostingConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(UiHostingConfig.class);

    private enum Kind { OFF, NONE, FOLDER, JAR }

    private final Kind kind;
    private final Path uiDir;
    private final String embedded;
    private final String configJson;

    public UiHostingConfig(HubProperties props, ResourceLoader loader) {
        // Checked even when it is not used, so a typo in svcmon.ui.proxy is found at start-up.
        this.configJson = UiSettings.json(props.ui());
        String location = props.embeddedUi() == null || props.embeddedUi().isBlank() ? "classpath:/ui/" : props.embeddedUi();
        this.embedded = location.endsWith("/") ? location : location + "/";
        if (!props.uiEnabled()) {
            this.kind = Kind.OFF;
            this.uiDir = null;
            log.info("The web dashboard is off (svcmon.ui-enabled is false)");
        } else if (props.uiDir() != null && !props.uiDir().toString().isBlank()) {
            // A monitoring hub should keep monitoring even if the optional UI folder is wrong.
            this.uiDir = resolve(props.uiDir());
            this.kind = uiDir == null ? Kind.NONE : Kind.FOLDER;
        } else if (loader.getResource(embedded + "index.html").exists()) {
            this.uiDir = null;
            this.kind = Kind.JAR;
            log.info("Serving the web dashboard from inside the hub jar");
        } else {
            this.uiDir = null;
            this.kind = Kind.NONE;
        }
    }

    /** The folder to serve, or null when UI hosting is off or the folder is unusable. */
    static Path resolve(Path configured) {
        if (configured == null || configured.toString().isBlank()) {
            return null;
        }
        Path dir = configured.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            log.warn("svcmon.ui-dir {} is not a directory; the web UI will not be served", dir);
            return null;
        }
        if (!Files.isRegularFile(dir.resolve("index.html"))) {
            log.warn("svcmon.ui-dir {} has no index.html; is this the UI project's dist folder?", dir);
        }
        log.info("Serving the web UI from {}", dir);
        return dir;
    }

    /** One line for the start-up summary: where the dashboard comes from, or why there is none. */
    public String served() {
        return switch (kind) {
            case FOLDER -> "served from " + uiDir;
            case JAR -> "served from inside the hub jar (svcmon.ui-dir would use a folder instead)";
            case OFF -> "off (svcmon.ui-enabled is false)";
            case NONE -> "not served (this jar has no dashboard inside; set svcmon.ui-dir to serve one)";
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String base;
        String assets;
        switch (kind) {
            case FOLDER -> {
                base = uiDir.toUri().toString();
                assets = base + "assets/";
            }
            case JAR -> {
                base = embedded;
                assets = embedded + "assets/";
            }
            default -> {
                return;
            }
        }
        // Build output names files under assets/ with a content hash, so they can be cached for good.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(assets)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations(base)
                .setCacheControl(CacheControl.noCache());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (kind == Kind.FOLDER || kind == Kind.JAR) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    /**
     * The page asks for ./config.json. A folder has its own file; the dashboard inside the jar gets one built from
     * svcmon.ui.proxy.*, so nobody edits files inside the jar.
     */
    @Bean
    RouterFunction<ServerResponse> dashboardConfig() {
        if (kind != Kind.JAR) {
            return request -> Optional.empty();
        }
        return RouterFunctions.route()
                .GET("/config.json", request -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .cacheControl(CacheControl.noCache())
                        .body(configJson))
                .build();
    }
}

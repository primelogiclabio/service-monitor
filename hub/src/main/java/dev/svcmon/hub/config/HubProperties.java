package dev.svcmon.hub.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("svcmon")
public record HubProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("30") int agentHeartbeatSeconds,
        @DefaultValue("90") int agentStaleSeconds,
        String bootstrapAdminToken,
        @DefaultValue("false") boolean newAdminToken,
        /** Folder with the built web UI (the UI project's dist folder). Unset means the hub serves no UI. */
        Path uiDir,
        /** Exact origins (scheme://host[:port]) allowed to call the API from a browser. Empty means CORS is off. */
        @DefaultValue List<String> corsAllowedOrigins) {
}

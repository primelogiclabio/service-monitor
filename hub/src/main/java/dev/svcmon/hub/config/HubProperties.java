package dev.svcmon.hub.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("svcmon")
public record HubProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("30") int agentHeartbeatSeconds,
        @DefaultValue("90") int agentStaleSeconds,
        String bootstrapAdminToken,
        @DefaultValue("false") boolean newAdminToken) {
}

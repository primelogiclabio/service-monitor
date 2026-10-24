package dev.svcmon.hub.model;

import java.util.Map;
import java.util.Set;

/**
 * A place alerts are sent. {@code config} holds the type's settings, including secrets (webhook URLs,
 * SMTP password), so it must never be returned by the API unmasked. {@code statuses} are the new
 * statuses that trigger a message.
 */
public record AlertChannel(
        String id,
        String name,
        AlertType type,
        Map<String, Object> config,
        Set<Status> statuses,
        boolean enabled,
        long createdAt,
        Long lastAttemptAt,
        Long lastSuccessAt,
        String lastError) {

    public AlertChannel {
        config = config == null ? Map.of() : config;
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }
}

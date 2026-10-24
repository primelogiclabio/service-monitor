package dev.svcmon.hub.core;

import java.util.Map;

import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.RunFrom;

/** Create/replace payload for a check. Omitted optional fields take defaults. */
public record CheckRequest(
        String name,
        CheckType type,
        String target,
        Map<String, Object> config,
        Integer intervalSeconds,
        Integer timeoutMs,
        Integer failuresBeforeDown,
        RunFrom runFrom,
        String serverId,
        Boolean enabled) {
}

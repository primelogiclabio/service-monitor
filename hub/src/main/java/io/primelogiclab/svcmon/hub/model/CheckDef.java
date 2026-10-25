package io.primelogiclab.svcmon.hub.model;

import java.util.Map;
import java.util.Objects;

public record CheckDef(
        String id,
        String name,
        CheckType type,
        String target,
        Map<String, Object> config,
        int intervalSeconds,
        int timeoutMs,
        int failuresBeforeDown,
        RunFrom runFrom,
        String serverId,
        boolean enabled,
        long createdAt,
        CheckSource source,
        boolean removedFromFile) {

    /** A check made in the hub. */
    public CheckDef(String id, String name, CheckType type, String target, Map<String, Object> config,
                    int intervalSeconds, int timeoutMs, int failuresBeforeDown, RunFrom runFrom, String serverId,
                    boolean enabled, long createdAt) {
        this(id, name, type, target, config, intervalSeconds, timeoutMs, failuresBeforeDown, runFrom, serverId,
                enabled, createdAt, CheckSource.HUB, false);
    }

    public CheckDef {
        config = config == null ? Map.of() : config;
        source = source == null ? CheckSource.HUB : source;
    }

    public CheckDef withEnabled(boolean value) {
        return new CheckDef(id, name, type, target, config, intervalSeconds, timeoutMs, failuresBeforeDown, runFrom,
                serverId, value, createdAt, source, removedFromFile);
    }

    public CheckDef withSource(CheckSource newSource, boolean removed) {
        return new CheckDef(id, name, type, target, config, intervalSeconds, timeoutMs, failuresBeforeDown, runFrom,
                serverId, enabled, createdAt, newSource, removed);
    }

    /** Whether everything that defines the check is the same, leaving out its on/off switch and bookkeeping. */
    public boolean sameDefinition(CheckDef o) {
        return name.equals(o.name) && type == o.type && Objects.equals(target, o.target) && config.equals(o.config)
                && intervalSeconds == o.intervalSeconds && timeoutMs == o.timeoutMs
                && failuresBeforeDown == o.failuresBeforeDown && runFrom == o.runFrom
                && Objects.equals(serverId, o.serverId);
    }

    public String configString(String key, String fallback) {
        Object v = config.get(key);
        return v == null ? fallback : v.toString();
    }

    public boolean configBool(String key, boolean fallback) {
        Object v = config.get(key);
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    public double configDouble(String key, double fallback) {
        Object v = config.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(v.toString());
            } catch (NumberFormatException ignored) {
                // fall through to the fallback
            }
        }
        return fallback;
    }
}

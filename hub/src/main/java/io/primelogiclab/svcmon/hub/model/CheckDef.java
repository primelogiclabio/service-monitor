package io.primelogiclab.svcmon.hub.model;

import java.util.LinkedHashMap;
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

    /** Shown in place of a header's value (the same mask as the alert channels' secrets). */
    public static final String MASK = "********";

    public CheckDef {
        config = config == null ? Map.of() : config;
        source = source == null ? CheckSource.HUB : source;
    }

    /**
     * This check as the API shows it: a header's value may be a secret (an API token, say), so only the header names
     * are shown. Agents and checkers use the check itself.
     */
    public CheckDef withHeadersMasked() {
        if (!(config.get("headers") instanceof Map<?, ?> headers) || headers.isEmpty()) {
            return this;
        }
        Map<String, Object> hidden = new LinkedHashMap<>();
        headers.keySet().forEach(k -> hidden.put(String.valueOf(k), MASK));
        Map<String, Object> shown = new LinkedHashMap<>(config);
        shown.put("headers", hidden);
        return new CheckDef(id, name, type, target, shown, intervalSeconds, timeoutMs, failuresBeforeDown, runFrom,
                serverId, enabled, createdAt, source, removedFromFile);
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

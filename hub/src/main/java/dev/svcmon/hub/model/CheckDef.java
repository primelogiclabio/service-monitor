package dev.svcmon.hub.model;

import java.util.Map;

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
        long createdAt) {

    public CheckDef {
        config = config == null ? Map.of() : config;
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

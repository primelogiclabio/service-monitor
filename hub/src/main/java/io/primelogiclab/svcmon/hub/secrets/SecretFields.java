package io.primelogiclab.svcmon.hub.secrets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import io.primelogiclab.svcmon.hub.model.AlertType;

/**
 * Which values of a stored config are secrets, so that one rule decides what is encrypted: an alert channel's {@link
 * AlertType#secretKeys()} and a webhook's header values, and a check's HTTP header values. Each method returns a copy
 * with the function applied to those values (sealing, opening or looking at them) and leaves the rest alone.
 */
public final class SecretFields {

    private SecretFields() {
    }

    public static Map<String, Object> channel(AlertType type, Map<String, Object> config, UnaryOperator<String> f) {
        if (config == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(config);
        for (String key : type.secretKeys()) {
            if (out.get(key) instanceof String value) {
                out.put(key, f.apply(value));
            }
        }
        if (type == AlertType.WEBHOOK) {
            headers(out, f);
        }
        return out;
    }

    public static Map<String, Object> check(Map<String, Object> config, UnaryOperator<String> f) {
        if (config == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(config);
        headers(out, f);
        return out;
    }

    private static void headers(Map<String, Object> config, UnaryOperator<String> f) {
        if (config.get("headers") instanceof Map<?, ?> headers && !headers.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            headers.forEach((k, v) -> out.put(String.valueOf(k), v instanceof String value ? f.apply(value) : v));
            config.put("headers", out);
        }
    }
}

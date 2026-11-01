package io.primelogiclab.svcmon.hub.model;

import java.util.List;

/** Where an alert channel delivers to. */
public enum AlertType {
    WEBHOOK, SLACK, DISCORD, EMAIL;

    /**
     * The settings that are secrets: masked in API answers and encrypted in the database. A webhook's header values
     * are secrets too.
     */
    public List<String> secretKeys() {
        return switch (this) {
            case WEBHOOK -> List.of("url", "signingSecret");
            case SLACK, DISCORD -> List.of("url");
            case EMAIL -> List.of("password");
        };
    }
}

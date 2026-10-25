package io.primelogiclab.svcmon.hub.alerts;

import io.primelogiclab.svcmon.hub.model.Status;

/**
 * Something worth telling a person about: a check changed status, a server's agent went silent or came
 * back, or an admin pressed "test" on a channel.
 */
public record AlertEvent(
        Kind kind,
        String id,
        String name,
        String type,
        String target,
        String serverId,
        String serverName,
        Status from,
        Status to,
        String message,
        long ts) {

    public enum Kind { CHECK, SERVER, TEST }

    /** "web01 / CPU", "web01 / agent", or "Test alert". */
    public String subject() {
        return switch (kind) {
            case TEST -> "Test alert";
            case SERVER -> (serverName == null ? name : serverName) + " / agent";
            case CHECK -> serverName == null ? name : serverName + " / " + name;
        };
    }

    public static AlertEvent test(long ts) {
        return new AlertEvent(Kind.TEST, "test", "Test alert", "TEST", null, null, null, Status.UP, Status.UP,
                "This is a test alert from svcmon. If you can read it, this channel works.", ts);
    }
}

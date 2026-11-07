package io.primelogiclab.svcmon.hub.model;

import java.util.Map;

/**
 * A server's maintenance window: while it is open the hub sends no alert about the server's agent or its checks.
 *
 * @param snapshot     how things stood when it opened, compared with how they stand when it ends
 * @param untilBack    end as soon as everything that went down is back, rather than only at {@code endsAt}
 * @param disturbedAt  when something last went down (or the agent was last seen away) while it was open, or null
 */
public record Maintenance(
        String serverId,
        long startedAt,
        long endsAt,
        String reason,
        String startedBy,
        String startedByKind,
        boolean untilBack,
        Snapshot snapshot,
        Long disturbedAt) {

    /** The agent's status (UP when it was reporting) and each enabled check's status by id. */
    public record Snapshot(Status agent, Map<String, Status> checks) {

        public Snapshot {
            agent = agent == null ? Status.UNKNOWN : agent;
            checks = checks == null ? Map.of() : Map.copyOf(checks);
        }
    }
}

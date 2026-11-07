package io.primelogiclab.svcmon.hub.core;

import java.util.List;

import io.primelogiclab.svcmon.hub.model.CheckSource;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.Maintenance;
import io.primelogiclab.svcmon.hub.model.RunFrom;
import io.primelogiclab.svcmon.hub.model.Status;

/** Read models returned by the status API. */
public final class StatusViews {

    private StatusViews() {
    }

    public record CheckView(
            String id,
            String name,
            CheckType type,
            String target,
            RunFrom runFrom,
            boolean enabled,
            Status status,
            Double latencyMs,
            Double value,
            String message,
            Long lastResultAt,
            Long lastChange,
            CheckSource source,
            boolean removedFromFile) {
    }

    public record ServerView(
            String id,
            String name,
            String cluster,
            String os,
            String agentVersion,
            Long lastSeen,
            boolean agentOnline,
            Status status,
            List<CheckView> checks,
            /** The server's open maintenance window, or null (left out of the JSON) when its alerts go out. */
            MaintenanceView maintenance) {
    }

    /** An open maintenance window: no alert about the server is sent until it ends. */
    public record MaintenanceView(
            String serverId,
            long startedAt,
            long endsAt,
            String reason,
            String startedBy,
            String startedByKind,
            boolean untilBack) {

        public static MaintenanceView of(Maintenance m) {
            return new MaintenanceView(m.serverId(), m.startedAt(), m.endsAt(), m.reason(), m.startedBy(),
                    m.startedByKind(), m.untilBack());
        }
    }

    public record ClusterView(String name, Status status, List<ServerView> servers) {
    }

    public record Counts(int up, int warn, int down, int unknown) {
    }

    public record Summary(Status overall, Counts servers, Counts checks) {
    }

    public record StatusResponse(
            long generatedAt,
            Summary summary,
            List<ClusterView> clusters,
            List<CheckView> hubChecks) {
    }
}

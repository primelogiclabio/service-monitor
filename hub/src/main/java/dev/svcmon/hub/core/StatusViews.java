package dev.svcmon.hub.core;

import java.util.List;

import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.RunFrom;
import dev.svcmon.hub.model.Status;

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
            Long lastChange) {
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
            List<CheckView> checks) {
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

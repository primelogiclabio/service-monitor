package dev.svcmon.hub.model;

import java.util.Collection;

public enum Status {
    UP(0), UNKNOWN(1), WARN(2), DOWN(3);

    private final int rank;

    Status(int rank) {
        this.rank = rank;
    }

    /** Worst status wins: DOWN, then WARN, then UNKNOWN, then UP. Empty means UNKNOWN. */
    public static Status worst(Collection<Status> statuses) {
        Status worst = null;
        for (Status s : statuses) {
            if (worst == null || s.rank > worst.rank) {
                worst = s;
            }
        }
        return worst == null ? UNKNOWN : worst;
    }
}

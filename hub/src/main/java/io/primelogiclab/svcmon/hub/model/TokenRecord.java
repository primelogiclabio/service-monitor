package io.primelogiclab.svcmon.hub.model;

public record TokenRecord(
        String id,
        String name,
        Scope scope,
        String serverId,
        String tokenHash,
        long createdAt,
        Long expiresAt,
        Long usedAt,
        /** Join keys only: the cluster every server it registers is placed in, or null for any. */
        String cluster,
        /** Join keys only: how many servers it has registered. */
        int useCount) {
}

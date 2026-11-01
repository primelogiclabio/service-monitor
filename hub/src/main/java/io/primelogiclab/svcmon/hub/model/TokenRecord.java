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
        int useCount,
        /** Who made it (a user, token, join key or enrollment token name, or "hub"); null if made before the hub recorded it (migration V8). */
        String createdBy,
        /** What kind of maker that is: user, token, join_key, enrollment_token or hub, like the audit's actor kind. */
        String createdByKind) {
}

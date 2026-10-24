package dev.svcmon.hub.model;

public record TokenRecord(
        String id,
        String name,
        Scope scope,
        String serverId,
        String tokenHash,
        long createdAt,
        Long expiresAt,
        Long usedAt) {
}

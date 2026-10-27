package io.primelogiclab.svcmon.hub.model;

/** A signed-in browser. {@code tokenHash} is the SHA-256 of the cookie's value, which only the browser has. */
public record SessionRecord(
        String id,
        String userId,
        String tokenHash,
        long createdAt,
        long lastSeenAt,
        long expiresAt,
        String clientAddress,
        String userAgent) {
}

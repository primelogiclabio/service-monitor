package io.primelogiclab.svcmon.hub.model;

/** A person who signs in. {@code passwordHash} never leaves the hub. */
public record UserRecord(
        String id,
        String username,
        Role role,
        String passwordHash,
        boolean mustChangePassword,
        boolean disabled,
        long createdAt,
        Long passwordChangedAt,
        Long lastSignInAt) {
}

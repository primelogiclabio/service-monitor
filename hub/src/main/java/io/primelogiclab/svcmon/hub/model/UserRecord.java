package io.primelogiclab.svcmon.hub.model;

/**
 * A person who signs in. {@code passwordHash} never leaves the hub. A user of the identity provider (svcmon.oidc) has
 * {@code providerIssuer} and {@code providerSubject} set and no password: their hash is {@link #NO_PASSWORD}.
 */
public record UserRecord(
        String id,
        String username,
        Role role,
        String passwordHash,
        boolean mustChangePassword,
        boolean disabled,
        long createdAt,
        Long passwordChangedAt,
        Long lastSignInAt,
        String providerIssuer,
        String providerSubject) {

    /** Stored instead of a hash for a provider's user; it is not in the hasher's format, so no password matches it. */
    public static final String NO_PASSWORD = "provider";

    /** A user with a password, as every user was before sign-in with a provider. */
    public static UserRecord withPassword(String id, String username, Role role, String passwordHash,
                                          boolean mustChangePassword, boolean disabled, long createdAt,
                                          Long passwordChangedAt, Long lastSignInAt) {
        return new UserRecord(id, username, role, passwordHash, mustChangePassword, disabled, createdAt,
                passwordChangedAt, lastSignInAt, null, null);
    }

    /** True for someone who signs in with the identity provider and has no password on the hub. */
    public boolean viaProvider() {
        return providerSubject != null;
    }
}

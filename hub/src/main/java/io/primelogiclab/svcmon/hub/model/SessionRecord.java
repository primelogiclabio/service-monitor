package io.primelogiclab.svcmon.hub.model;

/**
 * A signed-in browser. {@code tokenHash} is the SHA-256 of the cookie's value, which only the browser has.
 * {@code method} is how the person signed in: {@value #PASSWORD} or {@value #PROVIDER}.
 */
public record SessionRecord(
        String id,
        String userId,
        String tokenHash,
        long createdAt,
        long lastSeenAt,
        long expiresAt,
        String clientAddress,
        String userAgent,
        String method) {

    public static final String PASSWORD = "password";
    public static final String PROVIDER = "provider";

    public boolean viaProvider() {
        return PROVIDER.equals(method);
    }
}

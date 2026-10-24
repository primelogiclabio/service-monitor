package dev.svcmon.hub.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import dev.svcmon.hub.model.Scope;
import dev.svcmon.hub.model.TokenRecord;
import dev.svcmon.hub.store.TokenRepo;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies bearer tokens. Tokens are 256 random bits; only their SHA-256 hash is stored, so a
 * database leak does not reveal usable tokens. The raw value is shown once, at creation.
 */
@Service
public class TokenService {

    public record Issued(TokenRecord record, String raw) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenRepo tokens;

    public TokenService(TokenRepo tokens) {
        this.tokens = tokens;
    }

    public Issued issue(String name, Scope scope, String serverId, Long expiresAt) {
        return store(name, scope, serverId, expiresAt, generate(scope));
    }

    /** Stores a caller-supplied token value (used for the optional bootstrap admin token). */
    public Issued issueWithValue(String name, Scope scope, String raw) {
        return store(name, scope, null, null, raw);
    }

    private Issued store(String name, Scope scope, String serverId, Long expiresAt, String raw) {
        TokenRecord rec = new TokenRecord(UUID.randomUUID().toString(), name, scope, serverId, hash(raw),
                System.currentTimeMillis(), expiresAt, null);
        tokens.insert(rec);
        return new Issued(rec, raw);
    }

    /** Returns the token if the raw value is known, unexpired and (for enrollment tokens) unused. */
    public Optional<TokenRecord> authenticate(String raw) {
        if (raw == null || raw.length() < 20 || raw.length() > 200) {
            return Optional.empty();
        }
        return tokens.findByHash(hash(raw)).filter(t -> {
            if (t.expiresAt() != null && t.expiresAt() < System.currentTimeMillis()) {
                return false;
            }
            return !(t.scope() == Scope.ENROLL && t.usedAt() != null);
        });
    }

    /** True while an authenticated token still exists and has not expired, so a long-lived connection notices a revoke. */
    public boolean isActive(TokenRecord token) {
        return tokens.findByHash(token.tokenHash())
                .filter(t -> t.expiresAt() == null || t.expiresAt() >= System.currentTimeMillis())
                .isPresent();
    }

    static String generate(Scope scope) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "svm_" + scope.code() + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

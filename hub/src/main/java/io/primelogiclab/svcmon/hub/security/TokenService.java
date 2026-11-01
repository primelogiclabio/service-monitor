package io.primelogiclab.svcmon.hub.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import io.primelogiclab.svcmon.hub.audit.AuditService.Actor;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.store.TokenRepo;
import io.primelogiclab.svcmon.hub.store.UserRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies bearer tokens. Tokens are 256 random bits; only their SHA-256 hash is stored, so a
 * database leak does not reveal usable tokens. The raw value is shown once, at creation.
 * <p>
 * A token a person made acts for them (its owner): they saw its value once and may have kept it, so it can never do
 * more than they can now. It is suspended while they are disabled, works as READ while they are read-only (a join key
 * or enrollment token stops, as a read-only user cannot add servers), and is deleted with them. A token made with a
 * token acts for the same person. Tokens the hub made at start-up, those made with them, and agents' own credentials
 * act for nobody, so they outlive people.
 */
@Service
public class TokenService {

    public record Issued(TokenRecord record, String raw) {
    }

    /** What a token may do now, given the user it acts for. */
    public enum Limit { NONE, READ_ONLY, SUSPENDED }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenRepo tokens;
    private final UserRepo users;

    public TokenService(TokenRepo tokens, UserRepo users) {
        this.tokens = tokens;
        this.users = users;
    }

    /**
     * {@code by} is who asked for it, kept with the token so the lists can say who made each one; {@code owner} is the
     * id of the user it acts for (see {@link #ownerOf}), or null.
     */
    public Issued issue(String name, Scope scope, String serverId, Long expiresAt, Actor by, String owner) {
        return store(name, scope, serverId, expiresAt, generate(scope), null, by, owner);
    }

    /** A join key: registers any number of agents, optionally only into one cluster, until revoked or expired. */
    public Issued issueJoinKey(String name, String cluster, Long expiresAt, Actor by, String owner) {
        return store(name, Scope.JOIN, null, expiresAt, generate(Scope.JOIN), cluster, by, owner);
    }

    /** Stores a caller-supplied token value (used for the optional bootstrap admin token, which acts for nobody). */
    public Issued issueWithValue(String name, Scope scope, String raw, Actor by) {
        return store(name, scope, null, null, raw, null, by, null);
    }

    private Issued store(String name, Scope scope, String serverId, Long expiresAt, String raw, String cluster,
                         Actor by, String owner) {
        // An agent's credential belongs to its server, never to a person: deleting someone must not stop a server.
        TokenRecord rec = new TokenRecord(UUID.randomUUID().toString(), name, scope, serverId, hash(raw),
                System.currentTimeMillis(), expiresAt, null, cluster, 0, by == null ? null : by.name(),
                by == null ? null : by.kind(), scope == Scope.AGENT ? null : owner);
        tokens.insert(rec);
        return new Issued(rec, raw);
    }

    /**
     * The user a token made by this request acts for: the signed-in user, or the one the request's own token acts for
     * (null when that acts for nobody), as TokenAuthFilter found them.
     */
    public static String ownerOf(HttpServletRequest req) {
        if (req.getAttribute(TokenAuthFilter.USER_ATTR) instanceof SessionService.Signed who) {
            return who.user().id();
        }
        return req.getAttribute(TokenAuthFilter.ATTR) instanceof TokenRecord token ? token.ownerUserId() : null;
    }

    /** What the token may do now: a user it acts for who is gone or disabled suspends it, a read-only one limits it. */
    public Limit limit(TokenRecord token) {
        if (token.ownerUserId() == null) {
            return Limit.NONE;
        }
        UserRecord owner = users.findById(token.ownerUserId()).orElse(null);
        if (owner == null || owner.disabled()) {
            return Limit.SUSPENDED;
        }
        if (owner.role() == Role.READ) {
            return switch (token.scope()) {
                case ADMIN -> Limit.READ_ONLY;
                case ENROLL, JOIN -> Limit.SUSPENDED;
                default -> Limit.NONE;
            };
        }
        return Limit.NONE;
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

    /**
     * True while an authenticated token still exists, has not expired and is not suspended, so a long-lived connection
     * notices a revoke, or that the user it acts for was disabled.
     */
    public boolean isActive(TokenRecord token) {
        return tokens.findByHash(token.tokenHash())
                .filter(t -> t.expiresAt() == null || t.expiresAt() >= System.currentTimeMillis())
                .filter(t -> limit(t) != Limit.SUSPENDED)
                .isPresent();
    }

    static String generate(Scope scope) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "svm_" + scope.code() + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

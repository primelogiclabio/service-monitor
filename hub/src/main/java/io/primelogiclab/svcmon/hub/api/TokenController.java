package io.primelogiclab.svcmon.hub.api;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.TokenService;
import io.primelogiclab.svcmon.hub.store.TokenRepo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Token management. All endpoints here are ADMIN-only (enforced in TokenAuthFilter). */
@RestController
@RequestMapping("/api/v1")
public class TokenController {

    public record TokenRequest(String name, Scope scope) {
    }

    public record EnrollmentRequest(String name, Integer ttlMinutes) {
    }

    /** A join key: optional cluster its servers go to, optional lifetime in days (none means until revoked). */
    public record JoinKeyRequest(String name, String cluster, Integer expiresInDays) {
    }

    /** The raw token appears only in this response. */
    public record IssuedToken(String id, String name, Scope scope, String token, Long expiresAt, String cluster) {
    }

    /** useCount is only set for join keys: how many servers they registered (usedAt is the last time). */
    public record TokenView(String id, String name, Scope scope, String serverId, long createdAt,
                            Long expiresAt, Long usedAt, String cluster, Integer useCount) {
    }

    private final TokenService tokens;
    private final TokenRepo repo;

    public TokenController(TokenService tokens, TokenRepo repo) {
        this.tokens = tokens;
        this.repo = repo;
    }

    @GetMapping("/tokens")
    public List<TokenView> list() {
        return repo.findAll().stream().map(TokenController::view).toList();
    }

    /** Creates an ADMIN or READ API token (for the CLI, a console, scripts). */
    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedToken create(@RequestBody TokenRequest req) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 100) {
            throw ApiException.badRequest("name is required (max 100 characters)");
        }
        if (req.scope() != Scope.ADMIN && req.scope() != Scope.READ) {
            throw ApiException.badRequest("scope must be ADMIN or READ");
        }
        TokenService.Issued issued = tokens.issue(req.name().trim(), req.scope(), null, null);
        return issued(issued);
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id) {
        if (!repo.delete(id)) {
            throw ApiException.notFound("Token");
        }
    }

    /** One-time token for registering a new agent. Default lifetime 60 minutes, maximum 24 hours. */
    @PostMapping("/enrollment-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedToken enrollment(@RequestBody(required = false) EnrollmentRequest req) {
        int ttl = req == null || req.ttlMinutes() == null ? 60 : req.ttlMinutes();
        if (ttl < 1 || ttl > 1440) {
            throw ApiException.badRequest("ttlMinutes must be between 1 and 1440");
        }
        String name = req == null || req.name() == null || req.name().isBlank() ? "enrollment" : req.name().trim();
        long expires = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ttl);
        return issued(tokens.issue(name, Scope.ENROLL, null, expires));
    }

    @GetMapping("/join-keys")
    public List<TokenView> joinKeys() {
        return repo.findAll().stream().filter(t -> t.scope() == Scope.JOIN).map(TokenController::view).toList();
    }

    /**
     * A reusable key that registers any number of agents (the same install command works on every server) until it
     * is revoked or expires. Treat it like a password: anyone holding it can add servers.
     */
    @PostMapping("/join-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedToken createJoinKey(@RequestBody JoinKeyRequest req) {
        if (req == null || req.name() == null || req.name().isBlank() || req.name().trim().length() > 100) {
            throw ApiException.badRequest("name is required (max 100 characters)");
        }
        String cluster = req.cluster() == null || req.cluster().isBlank() ? null : req.cluster().trim();
        if (cluster != null && cluster.length() > 100) {
            throw ApiException.badRequest("cluster must be at most 100 characters");
        }
        Long expires = null;
        if (req.expiresInDays() != null) {
            if (req.expiresInDays() < 1 || req.expiresInDays() > 3650) {
                throw ApiException.badRequest("expiresInDays must be between 1 and 3650, or left out for no expiry");
            }
            expires = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(req.expiresInDays());
        }
        return issued(tokens.issueJoinKey(req.name().trim(), cluster, expires));
    }

    /** Stops a join key working. Servers it already registered keep their own credentials and are not affected. */
    @DeleteMapping("/join-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeJoinKey(@PathVariable String id) {
        boolean isJoinKey = repo.findAll().stream().anyMatch(t -> t.id().equals(id) && t.scope() == Scope.JOIN);
        if (!isJoinKey || !repo.delete(id)) {
            throw ApiException.notFound("Join key");
        }
    }

    private static IssuedToken issued(TokenService.Issued i) {
        TokenRecord r = i.record();
        return new IssuedToken(r.id(), r.name(), r.scope(), i.raw(), r.expiresAt(), r.cluster());
    }

    private static TokenView view(TokenRecord r) {
        return new TokenView(r.id(), r.name(), r.scope(), r.serverId(), r.createdAt(), r.expiresAt(), r.usedAt(),
                r.cluster(), r.scope() == Scope.JOIN ? r.useCount() : null);
    }
}

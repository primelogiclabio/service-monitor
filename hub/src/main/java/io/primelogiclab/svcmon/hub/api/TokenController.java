package io.primelogiclab.svcmon.hub.api;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.TokenService;
import io.primelogiclab.svcmon.hub.store.TokenRepo;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * useCount is only set for join keys: how many servers they registered (usedAt is the last time). createdBy and
     * createdByKind say who made it (user, token, join_key, enrollment_token or hub); absent for older tokens.
     */
    public record TokenView(String id, String name, Scope scope, String serverId, long createdAt,
                            Long expiresAt, Long usedAt, String cluster, Integer useCount, String createdBy,
                            String createdByKind) {
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
    public IssuedToken create(@RequestBody TokenRequest req, HttpServletRequest http) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 100) {
            throw ApiException.badRequest("name is required (max 100 characters)");
        }
        if (req.scope() != Scope.ADMIN && req.scope() != Scope.READ) {
            throw ApiException.badRequest("scope must be ADMIN or READ");
        }
        TokenService.Issued issued = tokens.issue(req.name().trim(), req.scope(), null, null,
                AuditService.actorOf(http));
        return audited(http, issued, req.scope().name());
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, HttpServletRequest http) {
        repo.find(id).ifPresent(t -> named(http, t, null));
        if (!repo.delete(id)) {
            throw ApiException.notFound("Token");
        }
    }

    /** One-time token for registering a new agent. Default lifetime 60 minutes, maximum 24 hours. */
    @PostMapping("/enrollment-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedToken enrollment(@RequestBody(required = false) EnrollmentRequest req, HttpServletRequest http) {
        int ttl = req == null || req.ttlMinutes() == null ? 60 : req.ttlMinutes();
        if (ttl < 1 || ttl > 1440) {
            throw ApiException.badRequest("ttlMinutes must be between 1 and 1440");
        }
        String name = req == null || req.name() == null || req.name().isBlank() ? "enrollment" : req.name().trim();
        long expires = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ttl);
        return audited(http, tokens.issue(name, Scope.ENROLL, null, expires, AuditService.actorOf(http)),
                ttl + " minutes");
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
    public IssuedToken createJoinKey(@RequestBody JoinKeyRequest req, HttpServletRequest http) {
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
        return audited(http, tokens.issueJoinKey(req.name().trim(), cluster, expires, AuditService.actorOf(http)),
                cluster == null ? null : "cluster " + cluster);
    }

    /** Stops a join key working. Servers it already registered keep their own credentials and are not affected. */
    @DeleteMapping("/join-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeJoinKey(@PathVariable String id, HttpServletRequest http) {
        TokenRecord key = repo.find(id).filter(t -> t.scope() == Scope.JOIN)
                .orElseThrow(() -> ApiException.notFound("Join key"));
        named(http, key, null);
        if (!repo.delete(id)) {
            throw ApiException.notFound("Join key");
        }
    }

    private static IssuedToken audited(HttpServletRequest http, TokenService.Issued i, String about) {
        TokenRecord r = i.record();
        named(http, r, about);
        return new IssuedToken(r.id(), r.name(), r.scope(), i.raw(), r.expiresAt(), r.cluster());
    }

    /**
     * Names the token a change is about in the audit: its name, and its id as short as the CLI's list shows it, since
     * names repeat and a revoked token's id alone says nothing. Never the token's value.
     */
    private static void named(HttpServletRequest http, TokenRecord t, String about) {
        http.setAttribute(AuditService.TARGET_ATTR, t.name());
        String id = "id " + t.id().substring(0, Math.min(8, t.id().length()));
        http.setAttribute(AuditService.DETAIL_ATTR, about == null ? id : about + ", " + id);
    }

    private static TokenView view(TokenRecord r) {
        return new TokenView(r.id(), r.name(), r.scope(), r.serverId(), r.createdAt(), r.expiresAt(), r.usedAt(),
                r.cluster(), r.scope() == Scope.JOIN ? r.useCount() : null, r.createdBy(), r.createdByKind());
    }
}

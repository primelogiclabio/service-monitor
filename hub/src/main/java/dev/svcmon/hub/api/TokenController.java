package dev.svcmon.hub.api;

import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.svcmon.hub.core.ApiException;
import dev.svcmon.hub.model.Scope;
import dev.svcmon.hub.model.TokenRecord;
import dev.svcmon.hub.security.TokenService;
import dev.svcmon.hub.store.TokenRepo;
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

    /** The raw token appears only in this response. */
    public record IssuedToken(String id, String name, Scope scope, String token, Long expiresAt) {
    }

    public record TokenView(String id, String name, Scope scope, String serverId, long createdAt,
                            Long expiresAt, Long usedAt) {
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

    private static IssuedToken issued(TokenService.Issued i) {
        TokenRecord r = i.record();
        return new IssuedToken(r.id(), r.name(), r.scope(), i.raw(), r.expiresAt());
    }

    private static TokenView view(TokenRecord r) {
        return new TokenView(r.id(), r.name(), r.scope(), r.serverId(), r.createdAt(), r.expiresAt(), r.usedAt());
    }
}

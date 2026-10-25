package io.primelogiclab.svcmon.hub.api;

import java.util.Map;

import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.security.LocalAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Password sign-in for the hub's own administrator, and changing that password. Signing in needs no token (see
 * TokenAuthFilter); changing the password needs an ADMIN token or sign-in.
 */
@RestController
@RequestMapping("/api/v1")
public class LoginController {

    public record LoginRequest(String username, String password) {
    }

    public record PasswordRequest(String newPassword) {
    }

    /** The token appears only in this response, like any other; it ends at expiresAt. */
    public record Session(String token, long expiresAt, Scope scope, String name) {
    }

    private final LocalAdminService admin;

    public LoginController(LocalAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        LocalAdminService.LoginResult result = admin.login(req.username(), req.password(), http.getRemoteAddr());
        ResponseEntity.BodyBuilder reply = ResponseEntity.status(switch (result.status()) {
            case OK -> HttpStatus.OK;
            case WRONG -> HttpStatus.UNAUTHORIZED;
            case THROTTLED -> HttpStatus.TOO_MANY_REQUESTS;
            case DISABLED -> HttpStatus.NOT_FOUND;
        }).header("Cache-Control", "no-store");
        return switch (result.status()) {
            case OK -> {
                TokenRecord t = result.issued().record();
                yield reply.body(new Session(result.issued().raw(), t.expiresAt(), t.scope(), t.name()));
            }
            // The same answer for a wrong name and a wrong password.
            case WRONG -> reply.body(Map.of("error", "unauthorized", "message", "Wrong user name or password"));
            case THROTTLED -> reply.header("Retry-After", String.valueOf(result.retryAfterSeconds()))
                    .body(Map.of("error", "too_many_requests",
                            "message", "Too many wrong attempts; try again in " + result.retryAfterSeconds() + " seconds"));
            case DISABLED -> reply.body(Map.of("error", "not_found", "message", "Local sign-in is turned off on this hub"));
        };
    }

    /** Sets the local admin's password and ends every sign-in made with the old one. */
    @PutMapping("/local-admin/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordRequest req) {
        admin.changePassword(req.newPassword());
    }
}

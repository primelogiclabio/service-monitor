package io.primelogiclab.svcmon.hub.api;

import java.util.List;

import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.security.SessionService;
import io.primelogiclab.svcmon.hub.security.TokenAuthFilter;
import io.primelogiclab.svcmon.hub.security.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Users, for administrators (TokenAuthFilter lets nobody else in). A new user, or one whose password is reset, gets a
 * temporary password that appears once in the answer and must be changed at their first sign-in.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    public record UserView(String id, String username, Role role, boolean disabled, boolean mustChangePassword,
                           long createdAt, Long passwordChangedAt, Long lastSignInAt) {

        static UserView of(UserRecord u) {
            return new UserView(u.id(), u.username(), u.role(), u.disabled(), u.mustChangePassword(), u.createdAt(),
                    u.passwordChangedAt(), u.lastSignInAt());
        }
    }

    public record CreateRequest(String username, Role role) {
    }

    public record UpdateRequest(Role role, Boolean disabled) {
    }

    public record WithPassword(UserView user, String temporaryPassword) {
    }

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserView> list() {
        return users.list().stream().map(UserView::of).toList();
    }

    @PostMapping
    public ResponseEntity<WithPassword> create(@RequestBody CreateRequest body) {
        UserService.WithPassword created = users.create(body.username(), body.role());
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(new WithPassword(UserView.of(created.user()), created.temporaryPassword()));
    }

    @PatchMapping("/{id}")
    public UserView update(@PathVariable String id, @RequestBody UpdateRequest body, HttpServletRequest req) {
        return UserView.of(users.update(id, body.role(), body.disabled(), actor(req)));
    }

    @PostMapping("/{id}/password-reset")
    public ResponseEntity<WithPassword> resetPassword(@PathVariable String id, HttpServletRequest req) {
        UserService.WithPassword reset = users.resetPassword(id, actor(req));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new WithPassword(UserView.of(reset.user()), reset.temporaryPassword()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        users.delete(id, actor(req));
    }

    /** The administrator's own user id, or null when an ADMIN API token or JWT is calling. */
    private static String actor(HttpServletRequest req) {
        return req.getAttribute(TokenAuthFilter.USER_ATTR) instanceof SessionService.Signed who ? who.user().id() : null;
    }
}

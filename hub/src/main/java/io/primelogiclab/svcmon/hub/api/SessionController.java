package io.primelogiclab.svcmon.hub.api;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.SessionRecord;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.security.NetworkPolicy;
import io.primelogiclab.svcmon.hub.security.SessionService;
import io.primelogiclab.svcmon.hub.security.TokenAuthFilter;
import io.primelogiclab.svcmon.hub.security.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in and out with a user name and password, and what a signed-in user does to their own account. The
 * sign-in is a cookie (see {@link SessionService}); nothing here ever returns it in a body.
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    public record SignInRequest(String username, String password) {
    }

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    public record Who(String id, String name, Role role) {
    }

    /** {@code user}, {@code mustChangePassword} and {@code expiresAt} are there only when signed in. */
    public record State(boolean signedIn, Who user, Boolean mustChangePassword, Long expiresAt) {
    }

    public record SessionView(String id, String user, long createdAt, long lastSeenAt, long expiresAt,
                              String clientAddress, String userAgent, boolean current) {
    }

    public record LiveTicket(String ticket, long expiresInSeconds) {
    }

    private final UserService users;
    private final SessionService sessions;
    private final NetworkPolicy network;

    public SessionController(UserService users, SessionService sessions, NetworkPolicy network) {
        this.users = users;
        this.sessions = sessions;
        this.network = network;
    }

    @GetMapping("/session")
    public ResponseEntity<State> state(HttpServletRequest req) {
        State state = sessions.authenticate(req).map(SessionController::state).orElse(new State(false, null, null, null));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(state);
    }

    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> signIn(@RequestBody SignInRequest body, HttpServletRequest req, HttpServletResponse resp) {
        String client = req.getRemoteAddr();
        UserService.SignInResult result = users.signIn(body.username(), body.password(), client,
                network.isInside(client), req.getHeader("User-Agent"));
        ResponseEntity.BodyBuilder reply = ResponseEntity.status(switch (result.status()) {
            case OK -> HttpStatus.OK;
            case WRONG -> HttpStatus.UNAUTHORIZED;
            case THROTTLED -> HttpStatus.TOO_MANY_REQUESTS;
            case INSIDE_ONLY -> HttpStatus.FORBIDDEN;
            case DISABLED -> HttpStatus.NOT_FOUND;
        }).header("Cache-Control", "no-store");
        return switch (result.status()) {
            case OK -> {
                sessions.writeCookie(req, resp, result.session().raw());
                yield reply.body(state(new SessionService.Signed(result.session().session(), result.user())));
            }
            // The same answer for a wrong name, a wrong password and a disabled account.
            case WRONG -> reply.body(error("wrong_credentials", "Wrong user name or password"));
            case THROTTLED -> reply.header("Retry-After", String.valueOf(result.retryAfterSeconds()))
                    .body(error("too_many_attempts",
                            "Too many wrong attempts; try again in " + result.retryAfterSeconds() + " seconds"));
            case INSIDE_ONLY -> reply.body(error("initial_password_inside_only",
                    "This password has to be changed, and that works only from the internal network"));
            case DISABLED -> reply.body(error("not_found", "Sign-in with a user name and password is turned off on this hub"));
        };
    }

    @DeleteMapping("/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(HttpServletRequest req, HttpServletResponse resp) {
        sessions.authenticate(req).ifPresent(who -> sessions.end(who.session().id()));
        sessions.clearCookie(req, resp);
    }

    /** A one-time ticket for the first frame of the live view (see LiveHandler). */
    @PostMapping("/session/live-ticket")
    public ResponseEntity<LiveTicket> liveTicket(HttpServletRequest req) {
        SessionService.Signed who = signedIn(req);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new LiveTicket(sessions.liveTicket(who.session()), SessionService.LIVE_TICKET_SECONDS));
    }

    /** Changes the signed-in user's own password; their other sign-ins end and this one gets a new cookie. */
    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordRequest body, HttpServletRequest req, HttpServletResponse resp) {
        SessionService.Signed who = signedIn(req);
        String client = req.getRemoteAddr();
        SessionService.Issued renewed = users.changeOwnPassword(who, body.currentPassword(), body.newPassword(), client,
                network.isInside(client));
        sessions.writeCookie(req, resp, renewed.raw());
    }

    /** The caller's own sign-ins; an administrator sees everyone's. */
    @GetMapping("/sessions")
    public List<SessionView> list(HttpServletRequest req) {
        SessionService.Signed who = (SessionService.Signed) req.getAttribute(TokenAuthFilter.USER_ATTR);
        List<SessionRecord> list;
        if (isAdmin(req)) {
            list = sessions.list();
        } else if (who != null) {
            list = sessions.listFor(who.user().id());
        } else {
            list = List.of();
        }
        Map<String, String> names = users.list().stream().collect(Collectors.toMap(UserRecord::id, UserRecord::username));
        String current = who == null ? null : who.session().id();
        return list.stream().map(s -> new SessionView(s.id(), names.getOrDefault(s.userId(), "?"), s.createdAt(),
                s.lastSeenAt(), s.expiresAt(), s.clientAddress(), s.userAgent(), s.id().equals(current))).toList();
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable String id, HttpServletRequest req) {
        SessionService.Signed who = (SessionService.Signed) req.getAttribute(TokenAuthFilter.USER_ATTR);
        Function<SessionRecord, Boolean> mine = s -> who != null && s.userId().equals(who.user().id());
        boolean allowed = sessions.list().stream().filter(s -> s.id().equals(id))
                .anyMatch(s -> isAdmin(req) || mine.apply(s));
        if (!allowed || !sessions.end(id)) {
            throw ApiException.notFound("Session " + id);
        }
    }

    private static SessionService.Signed signedIn(HttpServletRequest req) {
        if (req.getAttribute(TokenAuthFilter.USER_ATTR) instanceof SessionService.Signed who) {
            return who;
        }
        throw ApiException.badRequest("not_a_user", "Only a signed-in user can do this; an API token has no account");
    }

    private static boolean isAdmin(HttpServletRequest req) {
        if (req.getAttribute(TokenAuthFilter.USER_ATTR) instanceof SessionService.Signed who) {
            return who.user().role() == Role.ADMIN;
        }
        return req.getAttribute(TokenAuthFilter.JWT_USER_ATTR) != null
                || req.getAttribute(TokenAuthFilter.ATTR) instanceof TokenRecord t && t.scope() == Scope.ADMIN;
    }

    private static State state(SessionService.Signed who) {
        UserRecord u = who.user();
        return new State(true, new Who(u.id(), u.username(), u.role()), u.mustChangePassword(), who.session().expiresAt());
    }

    private static Map<String, String> error(String code, String message) {
        return Map.of("error", code, "message", message);
    }
}

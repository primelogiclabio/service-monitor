package io.primelogiclab.svcmon.hub.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.model.SessionRecord;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.oidc.OidcSettings;
import io.primelogiclab.svcmon.hub.oidc.ProviderSignIn;
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

    /** {@code provider}: the user signs in with the identity provider and has no password on the hub. */
    public record Who(String id, String name, Role role, boolean provider) {
    }

    /** The identity provider's sign-in: the button's text, whether it may be used from here, and where it starts. */
    public record ProviderOption(String label, boolean allowed, String startUrl) {
    }

    /**
     * {@code user}, {@code mustChangePassword} and {@code expiresAt} are there only when signed in. {@code network}
     * (inside or outside) and {@code address} say how the hub sees this browser. {@code passwordSignIn} says whether
     * the password form may be used from here, {@code provider} (only on a hub with one) the same for the identity
     * provider; {@code signInAllowed} is false when neither may, and {@code signInRefusal} then names the network's
     * reason.
     */
    public record State(boolean signedIn, Who user, Boolean mustChangePassword, Long expiresAt, String network,
                        String address, boolean signInAllowed, String signInRefusal, boolean passwordSignIn,
                        ProviderOption provider) {
    }

    /** {@code providerSignOut}: where to send the browser to sign out at the identity provider too. */
    public record SignedOut(String providerSignOut) {
    }

    public record SessionView(String id, String user, long createdAt, long lastSeenAt, long expiresAt,
                              String clientAddress, String userAgent, boolean current) {
    }

    public record LiveTicket(String ticket, long expiresInSeconds) {
    }

    private final UserService users;
    private final SessionService sessions;
    private final NetworkPolicy network;
    private final AuditService audit;
    private final ProviderSignIn provider;

    public SessionController(UserService users, SessionService sessions, NetworkPolicy network, AuditService audit,
                             ProviderSignIn provider) {
        this.users = users;
        this.sessions = sessions;
        this.network = network;
        this.audit = audit;
        this.provider = provider;
    }

    @GetMapping("/session")
    public ResponseEntity<State> state(HttpServletRequest req) {
        // A sign-in that may not be used from here is not reported, so the page shows the refusal instead.
        SessionService.Signed who = network.forUser(req) == null ? sessions.authenticate(req).orElse(null) : null;
        if (who != null && network.forSession(req, who.session().viaProvider()) != null) {
            who = null;
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(state(who, req));
    }

    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> signIn(@RequestBody SignInRequest body, HttpServletRequest req, HttpServletResponse resp) {
        String client = req.getRemoteAddr();
        UserService.SignInResult result = users.signIn(body.username(), body.password(), client,
                network.isInside(req), req.getHeader("User-Agent"));
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
                yield reply.body(state(new SessionService.Signed(result.session().session(), result.user()), req));
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

    /**
     * Ends this sign-in. One made with the identity provider answers with the provider's sign-out address, when it
     * has one, so the page can end the provider's sign-in too; otherwise there is nothing to say (204).
     */
    @DeleteMapping("/session")
    public ResponseEntity<SignedOut> signOut(HttpServletRequest req, HttpServletResponse resp) {
        SessionService.Signed who = sessions.authenticate(req).orElse(null);
        URI providerSignOut = null;
        if (who != null) {
            sessions.end(who.session().id());
            audit.signIn("sign_out", "ok", AuditService.Actor.user(who.user().username()), req.getRemoteAddr(), null,
                    null);
            providerSignOut = who.session().viaProvider() ? provider.endSessionUri() : null;
        }
        sessions.clearCookie(req, resp);
        return providerSignOut == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok().header("Cache-Control", "no-store").body(new SignedOut(providerSignOut.toString()));
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
                network.isInside(req));
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
        SessionRecord session = sessions.list().stream().filter(s -> s.id().equals(id))
                .filter(s -> isAdmin(req) || mine.apply(s)).findFirst().orElse(null);
        if (session == null || !sessions.end(id)) {
            throw ApiException.notFound("Session " + id);
        }
        String owner = users.list().stream().filter(u -> u.id().equals(session.userId())).map(UserRecord::username)
                .findFirst().orElse("?");
        req.setAttribute(AuditService.TARGET_ATTR, owner);
        audit.signIn("session_ended", "ok", AuditService.actorOf(req), req.getRemoteAddr(), owner, null);
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
        return req.getAttribute(TokenAuthFilter.ATTR) instanceof TokenRecord t && t.scope() == Scope.ADMIN;
    }

    private State state(SessionService.Signed who, HttpServletRequest req) {
        NetworkPolicy.Refusal refusal = network.forUser(req);
        String where = network.isInside(req) ? "inside" : "outside";
        boolean password = users.enabled() && network.forPassword(req) == null;
        ProviderOption option = provider.enabled() ? new ProviderOption(provider.settings().buttonLabel(),
                refusal == null, provider.settings().publicUrl() + OidcSettings.START) : null;
        boolean allowed = password || option != null && option.allowed();
        String reason = refusal == null ? null : refusal.code;
        if (who == null) {
            return new State(false, null, null, null, where, req.getRemoteAddr(), allowed, reason, password, option);
        }
        UserRecord u = who.user();
        return new State(true, new Who(u.id(), u.username(), u.role(), u.viaProvider()), u.mustChangePassword(),
                who.session().expiresAt(), where, req.getRemoteAddr(), allowed, reason, password, option);
    }

    private static Map<String, String> error(String code, String message) {
        return Map.of("error", code, "message", message);
    }
}

package io.primelogiclab.svcmon.hub.security;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.audit.AuditService.Actor;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.store.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The hub's users: signing in, changing one's own password, and what an administrator does to the others.
 * <p>
 * A password someone else set (the initial one, or a temporary one from an administrator) must be changed at the
 * first sign-in, and until then the session can do nothing else. Such a password also works only from the internal
 * network, so the well-known initial one cannot be tried from outside. Every failed sign-in gets the same answer,
 * whether the name, the password or the account was wrong, and costs the same time. The audit (see
 * {@link AuditService}) does record which it was, for the administrators.
 */
@Service
public class UserService {

    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    public enum Status { OK, WRONG, THROTTLED, INSIDE_ONLY, DISABLED }

    /** {@code session} is set when the status is OK, {@code retryAfterSeconds} when it is THROTTLED. */
    public record SignInResult(Status status, SessionService.Issued session, UserRecord user, long retryAfterSeconds) {
        static SignInResult of(Status status) {
            return new SignInResult(status, null, null, 0);
        }
    }

    /** A user and the temporary password they were given, which appears only here. */
    public record WithPassword(UserRecord user, String temporaryPassword) {
    }

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final HubProperties.Users cfg;
    private final UserRepo users;
    private final SessionService sessions;
    private final PasswordHasher hasher;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private volatile String dummyHash;

    public UserService(HubProperties props, UserRepo users, SessionService sessions, PasswordHasher hasher,
                       LoginThrottle throttle, AuditService audit) {
        this.cfg = props.users() == null ? HubProperties.Users.DEFAULT : props.users();
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.throttle = throttle;
        this.audit = audit;
        if (cfg.enabled()) {
            // Settings that would leave the first administrator unusable stop the start-up instead.
            if (cfg.initialAdminUsername() == null || !USERNAME.matcher(cfg.initialAdminUsername()).matches()) {
                throw new IllegalStateException("svcmon.users.initial-admin-username must be 1 to 64 letters, digits"
                        + " and . _ @ -");
            }
            if (cfg.initialAdminPassword() == null || cfg.initialAdminPassword().isBlank()
                    || cfg.initialAdminPassword().length() > PasswordHasher.MAX_LENGTH) {
                throw new IllegalStateException("svcmon.users.initial-admin-password must be 1 to "
                        + PasswordHasher.MAX_LENGTH + " characters");
            }
        }
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    public SignInResult signIn(String username, String password, String client, boolean inside, String userAgent) {
        if (!cfg.enabled()) {
            return SignInResult.of(Status.DISABLED);
        }
        long wait = throttle.waitSeconds(client, username, inside);
        if (wait > 0) {
            log.warn("Refused a sign-in from {}: too many wrong attempts, try again in {} s", client, wait);
            if (audit.once("throttled:" + client)) {
                audit.signIn("sign_in", "too_many_attempts", known(username), client, null, null);
            }
            return new SignInResult(Status.THROTTLED, null, null, wait);
        }
        boolean sizeOk = password != null && password.length() <= PasswordHasher.MAX_LENGTH;
        UserRecord user = username == null || !USERNAME.matcher(username).matches() ? null
                : users.findByUsername(username).orElse(null);
        // Always hash, against a stand-in when there is no such user, so the time does not say which part was wrong.
        boolean passwordOk = hasher.verify(sizeOk ? password : "", user != null ? user.passwordHash() : dummy());
        if (user == null || !passwordOk || !sizeOk || user.disabled()) {
            throttle.failed(client, username, inside);
            // A name nobody has is not written down: it may be a password typed into the wrong box.
            log.warn("Wrong sign-in from {} for {}", client,
                    user == null ? "an unknown user" : "user '" + user.username() + "'" + (user.disabled() ? " (disabled)" : ""));
            String reason = user == null ? "unknown_user" : !passwordOk || !sizeOk ? "wrong_password" : "account_disabled";
            audit.signIn("sign_in", reason, user == null ? Actor.UNKNOWN : Actor.user(user.username()), client, null, null);
            return SignInResult.of(Status.WRONG);
        }
        if (user.mustChangePassword() && !inside) {
            log.warn("Refused a sign-in from {} for user '{}': a password that must be changed works only from the"
                    + " internal network", client, user.username());
            audit.signIn("sign_in", "initial_password_inside_only", Actor.user(user.username()), client, null, null);
            return SignInResult.of(Status.INSIDE_ONLY);
        }
        throttle.succeeded(client);
        long now = System.currentTimeMillis();
        users.signedIn(user.id(), now);
        SessionService.Issued session = sessions.create(user, client, userAgent);
        log.info("User '{}' signed in from {}", user.username(), client);
        audit.signIn("sign_in", "ok", Actor.user(user.username()), client, null, null);
        return new SignInResult(Status.OK, session, user, 0);
    }

    /**
     * Sets the signed-in user's own password after checking the current one. Their other sessions end and this one
     * gets a new cookie value, which is returned.
     */
    public SessionService.Issued changeOwnPassword(SessionService.Signed who, String current, String next,
                                                   String client, boolean inside) {
        UserRecord user = who.user();
        if (throttle.waitSeconds(client, user.username(), inside) > 0) {
            if (audit.once("password-throttled:" + user.id())) {
                audit.signIn("password_changed", "too_many_attempts", Actor.user(user.username()), client, null, null);
            }
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "Too many wrong passwords; try again later");
        }
        boolean sizeOk = current != null && current.length() <= PasswordHasher.MAX_LENGTH;
        if (!sizeOk || !hasher.verify(current, user.passwordHash())) {
            throttle.failed(client, user.username(), inside);
            audit.signIn("password_changed", "wrong_password", Actor.user(user.username()), client, null, null);
            throw ApiException.badRequest("wrong_password", "The current password is wrong");
        }
        String problem = problem(next, user.username());
        if (problem != null) {
            throw ApiException.badRequest("weak_password", problem);
        }
        if (next.equals(current)) {
            throw ApiException.badRequest("same_password", "Choose a password different from the current one");
        }
        users.setPassword(user.id(), hasher.hash(next), false, System.currentTimeMillis());
        int ended = sessions.endOthers(user.id(), who.session().id());
        log.info("User '{}' changed their password{}", user.username(),
                ended > 0 ? "; " + ended + " other sign-in(s) ended" : "");
        audit.signIn("password_changed", "ok", Actor.user(user.username()), client, null,
                ended > 0 ? ended + " other sign-in(s) ended" : null);
        return sessions.renew(who.session());
    }

    public List<UserRecord> list() {
        return users.findAll();
    }

    public WithPassword create(String username, Role role) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("invalid_username", "A user name is 1 to 64 letters, digits and . _ @ -");
        }
        if (role == null) {
            throw ApiException.badRequest("role is required: ADMIN or READ");
        }
        if (users.findByUsername(username).isPresent()) {
            throw ApiException.conflict("username_taken", "There is already a user named '" + username + "'");
        }
        String temporary = PasswordHasher.generate();
        UserRecord u = new UserRecord(UUID.randomUUID().toString(), username, role, hasher.hash(temporary), true,
                false, System.currentTimeMillis(), null, null);
        users.insert(u);
        log.info("User '{}' was created with the role {}", username, role);
        return new WithPassword(u, temporary);
    }

    /**
     * Changes another user's role or turns them off or on. {@code actorId} is the administrator doing it, or null
     * for an API token. Either change ends the user's sessions, so it takes effect at once.
     */
    public UserRecord update(String id, Role role, Boolean disabled, String actorId) {
        UserRecord u = find(id);
        boolean demote = role != null && role != u.role() && u.role() == Role.ADMIN;
        boolean disable = Boolean.TRUE.equals(disabled) && !u.disabled();
        if ((role != null && role != u.role()) || disabled != null && disabled != u.disabled()) {
            notYourself(u, actorId);
        }
        if ((demote || disable) && isLastAdmin(u)) {
            throw lastAdmin();
        }
        if (role != null && role != u.role()) {
            users.setRole(id, role);
            log.info("User '{}' now has the role {}", u.username(), role);
        }
        if (disabled != null && disabled != u.disabled()) {
            users.setDisabled(id, disabled);
            log.info("User '{}' was {}", u.username(), disabled ? "disabled" : "enabled");
        }
        if ((role != null && role != u.role()) || disable) {
            sessions.endAll(id);
        }
        return find(id);
    }

    /** Gives a user a new temporary password, to be changed at their next sign-in, and ends their sessions. */
    public WithPassword resetPassword(String id, String actorId) {
        UserRecord u = find(id);
        notYourself(u, actorId);
        String temporary = PasswordHasher.generate();
        users.setPassword(id, hasher.hash(temporary), true, null);
        sessions.endAll(id);
        log.info("User '{}' was given a temporary password", u.username());
        return new WithPassword(find(id), temporary);
    }

    public void delete(String id, String actorId) {
        UserRecord u = find(id);
        notYourself(u, actorId);
        if (isLastAdmin(u)) {
            throw lastAdmin();
        }
        users.delete(id);
        log.info("User '{}' was deleted", u.username());
    }

    /** The account a throttled attempt was for, when there is one; a name nobody has may be a mistyped password. */
    private Actor known(String username) {
        return username != null && USERNAME.matcher(username).matches()
                ? users.findByUsername(username).map(u -> Actor.user(u.username())).orElse(Actor.UNKNOWN)
                : Actor.UNKNOWN;
    }

    private UserRecord find(String id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User " + id));
    }

    private boolean isLastAdmin(UserRecord u) {
        return u.role() == Role.ADMIN && !u.disabled() && users.countEnabledAdmins() <= 1;
    }

    private static ApiException lastAdmin() {
        return ApiException.conflict("last_admin", "This is the last administrator; make another user an"
                + " administrator first");
    }

    /** An administrator changes their own account through their own password, never by locking themselves out. */
    private static void notYourself(UserRecord u, String actorId) {
        if (u.id().equals(actorId)) {
            throw ApiException.conflict("own_account", "You cannot change, reset or delete your own account here;"
                    + " another administrator can");
        }
    }

    /** What is wrong with a new password, or null; it may not be the user's own name. */
    static String problem(String password, String username) {
        String problem = PasswordHasher.problem(password);
        if (problem == null && password.strip().toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT))) {
            problem = "The password must not be the user name";
        }
        return problem;
    }

    private String dummy() {
        String d = dummyHash;
        if (d == null) {
            d = hasher.hash(PasswordHasher.generate());
            dummyHash = d;
        }
        return d;
    }
}

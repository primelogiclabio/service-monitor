package io.primelogiclab.svcmon.hub.security;

import java.util.concurrent.TimeUnit;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.store.MetaRepo;
import io.primelogiclab.svcmon.hub.store.TokenRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/**
 * The hub's own administrator account: the way in when the provider behind the proxy is down, and the only
 * password-based one. A correct sign-in is answered with an ordinary ADMIN token that ends after
 * svcmon.local-admin.session-hours, so everything that already works with tokens (the dashboard, the live view,
 * revoking) works with it, and a token's name {@value #SESSION_NAME} says where it came from.
 *
 * <p>Only a salted hash of the password is stored, in the hub's key-value table. At first start a password is
 * created (from svcmon.local-admin.password, or random and printed once, like the first admin token); a changed
 * password ends every sign-in made with the old one. Wrong attempts are throttled by {@link LoginThrottle}, and a
 * wrong user name costs the same time as a wrong password so the name cannot be found out.
 */
@Service
public class LocalAdminService implements ApplicationRunner {

    public static final String SESSION_NAME = "local-login";
    static final String META_KEY = "local-admin.password-hash";
    private static final int MAX_USERNAME_LENGTH = 100;

    public enum Status { OK, WRONG, THROTTLED, DISABLED }

    /** {@code issued} is set when the status is OK, {@code retryAfterSeconds} when it is THROTTLED. */
    public record LoginResult(Status status, TokenService.Issued issued, long retryAfterSeconds) {
        static LoginResult of(Status status) {
            return new LoginResult(status, null, 0);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LocalAdminService.class);

    private final HubProperties.LocalAdmin cfg;
    private final PasswordHasher hasher;
    private final LoginThrottle throttle;
    private final MetaRepo meta;
    private final TokenRepo tokenRepo;
    private final TokenService tokens;
    private volatile String dummyHash;

    public LocalAdminService(HubProperties props, PasswordHasher hasher, LoginThrottle throttle, MetaRepo meta,
                             TokenRepo tokenRepo, TokenService tokens) {
        this.cfg = props.localAdmin() == null ? HubProperties.LocalAdmin.DEFAULT : props.localAdmin();
        this.hasher = hasher;
        this.throttle = throttle;
        this.meta = meta;
        this.tokenRepo = tokenRepo;
        this.tokens = tokens;
        if (cfg.enabled()) {
            // Settings that would silently leave the account unusable or too weak stop the start-up instead.
            if (cfg.username() == null || cfg.username().isBlank() || cfg.username().length() > MAX_USERNAME_LENGTH
                    || cfg.username().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException("svcmon.local-admin.username must be 1 to " + MAX_USERNAME_LENGTH
                        + " characters without control characters");
            }
            if (cfg.sessionHours() < 1 || cfg.sessionHours() > 168) {
                throw new IllegalStateException("svcmon.local-admin.session-hours must be between 1 and 168");
            }
            if (cfg.password() != null && !cfg.password().isEmpty() && PasswordHasher.problem(cfg.password()) != null) {
                throw new IllegalStateException("svcmon.local-admin.password must be " + PasswordHasher.MIN_LENGTH + " to "
                        + PasswordHasher.MAX_LENGTH + " characters");
            }
        }
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    /** Creates the account on first start, or a new password when asked for one. */
    @Override
    public void run(ApplicationArguments args) {
        if (!cfg.enabled()) {
            log.info("The local admin account is turned off (svcmon.local-admin.enabled)");
            return;
        }
        boolean exists = meta.get(META_KEY).isPresent();
        if (exists && !cfg.newPassword()) {
            log.info("Local admin account '{}' is ready", cfg.username());
            return;
        }
        boolean configured = cfg.password() != null && !cfg.password().isEmpty() && !cfg.newPassword();
        String password = configured ? cfg.password() : PasswordHasher.generate();
        store(password);
        if (configured) {
            log.info("Local admin account '{}' created from svcmon.local-admin.password", cfg.username());
            return;
        }
        System.out.println();
        System.out.println("==================================================================");
        System.out.println(" Local admin account (shown once, store it somewhere safe):");
        System.out.println("   user:     " + cfg.username());
        System.out.println("   password: " + password);
        System.out.println("==================================================================");
        System.out.println();
    }

    public LoginResult login(String username, String password, String client) {
        if (!cfg.enabled()) {
            return LoginResult.of(Status.DISABLED);
        }
        long wait = throttle.waitSeconds(client);
        if (wait > 0) {
            log.warn("Refused a local admin sign-in from {}: too many wrong attempts, try again in {} s", client, wait);
            return new LoginResult(Status.THROTTLED, null, wait);
        }
        String stored = meta.get(META_KEY).orElse(null);
        boolean nameOk = username != null && username.length() <= MAX_USERNAME_LENGTH && username.equals(cfg.username());
        boolean sizeOk = password != null && password.length() <= PasswordHasher.MAX_LENGTH;
        // Always hash, against a stand-in when the name is wrong, so the time does not say which part was wrong.
        boolean passwordOk = hasher.verify(sizeOk ? password : "", nameOk && stored != null ? stored : dummy());
        if (!(nameOk && passwordOk && sizeOk && stored != null)) {
            throttle.failed(client);
            log.warn("Wrong local admin sign-in from {} for user '{}'", client, clean(username));
            return LoginResult.of(Status.WRONG);
        }
        throttle.succeeded(client);
        long now = System.currentTimeMillis();
        tokenRepo.deleteExpiredByName(SESSION_NAME, now);
        TokenService.Issued issued = tokens.issue(SESSION_NAME, Scope.ADMIN, null,
                now + TimeUnit.HOURS.toMillis(cfg.sessionHours()));
        log.info("Local admin '{}' signed in from {}", cfg.username(), client);
        return new LoginResult(Status.OK, issued, 0);
    }

    /** Sets a new password and ends every sign-in made with the old one. */
    public void changePassword(String newPassword) {
        if (!cfg.enabled()) {
            throw ApiException.notFound("The local admin account");
        }
        String problem = PasswordHasher.problem(newPassword);
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
        store(newPassword);
        log.info("The local admin password was changed");
    }

    private void store(String password) {
        meta.set(META_KEY, hasher.hash(password));
        int ended = tokenRepo.deleteByName(SESSION_NAME);
        if (ended > 0) {
            log.info("Ended {} local admin sign-in(s) made with the old password", ended);
        }
    }

    private String dummy() {
        String d = dummyHash;
        if (d == null) {
            d = hasher.hash(PasswordHasher.generate());
            dummyHash = d;
        }
        return d;
    }

    /** The name is typed by whoever calls the API, so keep it to one short printable line in the log. */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String one = raw.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
        return one.length() > 50 ? one.substring(0, 50) + "..." : one;
    }
}

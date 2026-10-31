package io.primelogiclab.svcmon.hub.security;

import java.util.UUID;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import io.primelogiclab.svcmon.hub.store.MetaRepo;
import io.primelogiclab.svcmon.hub.store.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Makes sure a hub has someone who can sign in. On a hub without users it creates the first administrator: from the
 * local admin account of an older hub, which keeps its password, or with the initial password, which must be changed
 * at the first sign-in and works only from the internal network until then. While an administrator still has a
 * password someone else set, every start says so.
 */
@Component
public class UserBootstrap implements ApplicationRunner {

    /** Where hubs before users kept the one local admin's password hash. */
    static final String LEGACY_META_KEY = "local-admin.password-hash";

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final HubProperties.Users cfg;
    private final UserRepo users;
    private final MetaRepo meta;
    private final PasswordHasher hasher;

    public UserBootstrap(HubProperties props, UserRepo users, MetaRepo meta, PasswordHasher hasher) {
        this.cfg = props.users() == null ? HubProperties.Users.DEFAULT : props.users();
        this.users = users;
        this.meta = meta;
        this.hasher = hasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!cfg.enabled()) {
            log.info("Sign-in with a user name and password is turned off (svcmon.users.enabled)");
            return;
        }
        if (users.count() == 0) {
            createFirstAdmin();
            return;
        }
        users.findAll().stream()
                .filter(u -> u.role() == Role.ADMIN && !u.disabled() && u.mustChangePassword())
                .forEach(u -> log.warn("Administrator '{}' has not chosen a password yet: sign in from the internal"
                        + " network and change it", u.username()));
    }

    private void createFirstAdmin() {
        String name = cfg.initialAdminUsername();
        long now = System.currentTimeMillis();
        String legacy = meta.get(LEGACY_META_KEY).orElse(null);
        if (legacy != null) {
            users.insert(UserRecord.withPassword(UUID.randomUUID().toString(), name, Role.ADMIN, legacy, false, false,
                    now, now, null));
            meta.delete(LEGACY_META_KEY);
            log.info("The local admin account is now user '{}', an administrator, with the same password", name);
            return;
        }
        users.insert(UserRecord.withPassword(UUID.randomUUID().toString(), name, Role.ADMIN,
                hasher.hash(cfg.initialAdminPassword()), true, false, now, null, null));
        log.warn("Created administrator '{}' with the initial password (svcmon.users.initial-admin-password): sign in"
                + " from the internal network and choose a new one", name);
    }
}

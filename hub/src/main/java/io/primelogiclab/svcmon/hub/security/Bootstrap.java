package io.primelogiclab.svcmon.hub.security;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.Scope;
import io.primelogiclab.svcmon.hub.store.TokenRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * First-start setup: creates the hub ID and, if no admin token exists yet, an admin token that is printed
 * once to the console. Only a hash is stored, so a lost token is replaced with svcmon.new-admin-token=true.
 */
@Component
public class Bootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final HubProperties props;
    private final TokenRepo tokenRepo;
    private final TokenService tokens;
    private final HubIdentity identity;

    public Bootstrap(HubProperties props, TokenRepo tokenRepo, TokenService tokens, HubIdentity identity) {
        this.props = props;
        this.tokenRepo = tokenRepo;
        this.tokens = tokens;
        this.identity = identity;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Hub ID {}", identity.id());
        boolean noAdmin = tokenRepo.countByScope(Scope.ADMIN) == 0;

        if (noAdmin && props.bootstrapAdminToken() != null && !props.bootstrapAdminToken().isBlank()) {
            if (props.bootstrapAdminToken().length() < 32) {
                throw new IllegalStateException("svcmon.bootstrap-admin-token must be at least 32 characters");
            }
            tokens.issueWithValue("bootstrap", Scope.ADMIN, props.bootstrapAdminToken());
            log.info("Admin token created from svcmon.bootstrap-admin-token");
            return;
        }
        if (noAdmin || props.newAdminToken()) {
            TokenService.Issued issued = tokens.issue(noAdmin ? "initial-admin" : "recovery-admin", Scope.ADMIN, null, null);
            System.out.println();
            System.out.println("==================================================================");
            System.out.println(" New admin token (shown once, store it somewhere safe):");
            System.out.println("   " + issued.raw());
            System.out.println("==================================================================");
            System.out.println();
        }
    }
}

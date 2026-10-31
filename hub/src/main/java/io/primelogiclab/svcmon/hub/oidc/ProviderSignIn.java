package io.primelogiclab.svcmon.hub.oidc;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.Nonce;
import io.primelogiclab.svcmon.hub.audit.AuditService;
import io.primelogiclab.svcmon.hub.audit.AuditService.Actor;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.config.HubProperties.Oidc.ClientAuth;
import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.oidc.ProviderClient.ProviderException;
import io.primelogiclab.svcmon.hub.security.SessionService;
import io.primelogiclab.svcmon.hub.security.TokenService;
import io.primelogiclab.svcmon.hub.security.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sign-in with the identity provider (svcmon.oidc), run by the hub: the authorization code flow with PKCE. A sign-in
 * that was started is remembered for {@value #PENDING_MINUTES} minutes under its state, together with its nonce, its
 * PKCE verifier and the hash of a random value in a cookie of the browser that started it, so a code can only finish
 * the sign-in in that same browser (no one can sign someone else in as themselves). Each is used once.
 * <p>
 * The ID token's roles decide the hub role (svcmon.oidc.admin-role, read-role); {@link UserService} finds or makes
 * the user by the provider's issuer and subject and starts an ordinary cookie session.
 */
@Service
public class ProviderSignIn {

    /** Codes for a sign-in that did not work; the dashboard explains each. */
    public static final String NOT_CONFIGURED = "not_configured";
    public static final String EXPIRED = "expired";

    /** Where the provider was sent, and the value for the browser's binding cookie. */
    public record Start(URI redirect, String binding) {
    }

    /** {@code error} is null when {@code session} is set. */
    public record Finish(String error, SessionService.Issued session) {
    }

    /** How long a started sign-in waits for the provider, and the browser's binding cookie lasts. */
    public static final long PENDING_MINUTES = 10;
    private static final int MAX_PENDING = 1_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(ProviderSignIn.class);

    private record Pending(String bindingHash, Nonce nonce, CodeVerifier verifier, long expiresAt) {
    }

    private final OidcSettings settings;
    private final SigningKey key;
    private final ProviderClient client;
    private final UserService users;
    private final AuditService audit;
    private final Map<String, Pending> pending = new HashMap<>();

    @Autowired
    public ProviderSignIn(HubProperties props, UserService users, AuditService audit) {
        this(OidcSettings.from(props), props, users, audit);
    }

    ProviderSignIn(OidcSettings settings, HubProperties props, UserService users, AuditService audit) {
        this.settings = settings;
        this.users = users;
        this.audit = audit;
        if (settings == null) {
            this.key = null;
            this.client = null;
            return;
        }
        if (!users.enabled()) {
            throw new IllegalStateException("svcmon.oidc needs people's sign-in: svcmon.users.enabled is false");
        }
        if (settings.clientAuth() != ClientAuth.PRIVATE_KEY_JWT) {
            this.key = null;
        } else if (settings.privateKeyFile() != null) {
            this.key = new FileKey(settings.privateKeyFile(), settings.certificateFile()); // checked now
        } else {
            this.key = new HubKey(props.dataDir());
            key.current(); // made now, so the provider can be given the public key before anyone signs in
        }
        this.client = new ProviderClient(settings, key);
    }

    public boolean enabled() {
        return settings != null;
    }

    public OidcSettings settings() {
        return settings;
    }

    /** The hub's key pair, or null when provider sign-in is off or uses a client secret. */
    public SigningKey key() {
        return key;
    }

    public ProviderClient client() {
        return client;
    }

    /** Asks the provider for its discovery document once the hub is up, without holding up the start. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (client != null) {
            Thread.ofVirtual().name("oidc-discovery").start(client::warmUp);
        }
        checkExpiry();
    }

    /** A key pair from files carries an expiry in its certificate: say so in time, every day while it is close. */
    @Scheduled(cron = "0 0 9 * * *")
    public void checkExpiry() {
        if (!(key instanceof FileKey file)) {
            return;
        }
        Instant expires = file.expiresAt();
        long days = FileKey.daysLeft(expires, Instant.now());
        if (days < 0) {
            log.error("The certificate of the hub's key pair ({}) expired on {}; the identity provider may refuse the"
                    + " hub's sign-in. Make a new pair (signing-keys-tool generate) and give the provider its"
                    + " certificate", file.certificateFile(), expires);
        } else if (days <= FileKey.WARN_DAYS) {
            log.warn("The certificate of the hub's key pair ({}) expires on {}, in {} day(s). Make a new pair"
                    + " (signing-keys-tool generate) and give the provider its certificate", file.certificateFile(),
                    expires, days);
        }
    }

    /** Starts a sign-in: where to send the browser, and the value it must bring back in its cookie. */
    public Start start() throws ProviderException {
        if (settings == null) {
            throw new ProviderException(NOT_CONFIGURED, "svcmon.oidc is not set");
        }
        State state = new State();
        Nonce nonce = new Nonce();
        CodeVerifier verifier = new CodeVerifier();
        URI redirect = client.authorizationUri(state, nonce, verifier);
        String binding = random();
        synchronized (pending) {
            long now = System.currentTimeMillis();
            pending.values().removeIf(p -> p.expiresAt() <= now);
            if (pending.size() >= MAX_PENDING) {
                throw new ProviderException("provider_unavailable", "too many sign-ins are waiting for the provider");
            }
            pending.put(state.getValue(), new Pending(TokenService.hash(binding), nonce, verifier,
                    now + TimeUnit.MINUTES.toMillis(PENDING_MINUTES)));
        }
        return new Start(redirect, binding);
    }

    /**
     * Finishes a sign-in with what the provider sent back: {@code code} and {@code state}, or {@code error} when the
     * person did not sign in there. {@code binding} is the browser's cookie from {@link #start}.
     */
    public Finish finish(String code, String state, String error, String binding, String clientAddress,
                         String userAgent) {
        Pending p = null;
        if (state != null) {
            synchronized (pending) {
                p = pending.remove(state);
            }
        }
        if (p == null || p.expiresAt() <= System.currentTimeMillis() || binding == null
                || !TokenService.hash(binding).equals(p.bindingHash())) {
            return failed(EXPIRED, "no sign-in was started in this browser with that state, or it took over "
                    + PENDING_MINUTES + " minutes", clientAddress);
        }
        if (error != null) {
            return failed("provider_refused", "the provider answered " + ProviderClient.clean(error), clientAddress);
        }
        if (code == null || code.isBlank()) {
            return failed("invalid_response", "the provider sent no code", clientAddress);
        }
        Map<String, Object> claims;
        try {
            claims = client.exchange(code, p.verifier(), p.nonce());
        } catch (ProviderException e) {
            return failed(e.code, e.getMessage(), clientAddress);
        }
        String issuer = (String) claims.get("iss");
        String subject = (String) claims.get("sub");
        Role role = role(claims, settings.rolesPath(), settings.adminRole(), settings.readRole());
        String username = username(claims, settings.usernameClaim());
        UserService.ProviderSignInResult result = users.signInWithProvider(issuer, subject, username, role,
                clientAddress, userAgent);
        return switch (result.status()) {
            case OK -> new Finish(null, result.session());
            case NO_ROLE -> new Finish("no_role", null);
            case DISABLED -> new Finish("account_disabled", null);
            case USERNAME_TAKEN -> new Finish("username_taken", null);
            case USERNAME_UNUSABLE -> new Finish("username_unusable", null);
        };
    }

    /** The provider's sign-out address, or null when it has none or provider sign-in is off. */
    public URI endSessionUri() {
        return client == null ? null : client.endSessionUri();
    }

    /** The public key's id, or null without one. */
    public String keyId() {
        if (key == null) {
            return null;
        }
        RSAKey k = key.current();
        return k.getKeyID();
    }

    /** One line for the start-up summary. */
    public String describe() {
        if (settings == null) {
            return "user name and password (svcmon.oidc is not set)";
        }
        String how = key instanceof FileKey file
                ? "key pair from " + file.keyFile() + ", key id " + keyId() + ", " + settings.signingAlgorithm()
                + ", certificate expires " + file.expiresAt().toString().substring(0, 10)
                : settings.clientAuth() == ClientAuth.PRIVATE_KEY_JWT
                ? "its own key pair, key id " + keyId() + ", " + settings.signingAlgorithm()
                : "client secret from " + settings.clientSecretFile();
        return "user name and password from inside, and identity provider " + settings.issuer() + " as client "
                + settings.clientId() + " (" + how + "); roles from " + settings.rolesClaim() + ": "
                + settings.adminRole() + " = ADMIN" + (settings.readRole() == null ? "" : ", " + settings.readRole()
                + " = READ");
    }

    /** The hub role the ID token's roles give: admin-role wins over read-role; null for neither. */
    static Role role(Map<String, Object> claims, List<String> path, String adminRole, String readRole) {
        Object value = claims;
        for (String part : path) {
            value = value instanceof Map<?, ?> m ? m.get(part) : null;
        }
        Set<String> roles = new LinkedHashSet<>();
        if (value instanceof Collection<?> c) {
            c.stream().filter(String.class::isInstance).map(String.class::cast).forEach(roles::add);
        } else if (value instanceof String s) {
            roles.add(s);
        }
        if (roles.contains(adminRole)) {
            return Role.ADMIN;
        }
        return readRole != null && roles.contains(readRole) ? Role.READ : null;
    }

    /** The hub user's name from the ID token: the configured claim, else email, when it fits a hub name. */
    static String username(Map<String, Object> claims, String claim) {
        for (String name : List.of(claim, "email")) {
            if (claims.get(name) instanceof String s && UserService.USERNAME.matcher(s).matches()) {
                return s;
            }
        }
        return null;
    }

    private Finish failed(String code, String detail, String clientAddress) {
        log.warn("A sign-in with the identity provider from {} did not work ({}): {}", clientAddress, code, detail);
        if (audit.once("provider:" + code + ":" + clientAddress)) {
            audit.signIn("sign_in", code, Actor.UNKNOWN, clientAddress, null, "identity provider");
        }
        return new Finish(code, null);
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

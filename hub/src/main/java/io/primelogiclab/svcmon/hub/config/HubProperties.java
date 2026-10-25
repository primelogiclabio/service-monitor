package io.primelogiclab.svcmon.hub.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("svcmon")
public record HubProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("30") int agentHeartbeatSeconds,
        @DefaultValue("90") int agentStaleSeconds,
        String bootstrapAdminToken,
        @DefaultValue("false") boolean newAdminToken,
        /** Folder with the built web UI (the UI project's dist folder). Unset means the hub serves no UI. */
        Path uiDir,
        /** Exact origins (scheme://host[:port]) allowed to call the API from a browser. Empty means CORS is off. */
        @DefaultValue List<String> corsAllowedOrigins,
        /** Lets Slack and Discord alert URLs use http. Off by default; only for tests and local receivers. */
        @DefaultValue("false") boolean allowInsecureAlertUrls,
        /** Most dashboards that may hold the live WebSocket open at once; more are told to try again later. */
        @DefaultValue("50") int liveMaxSessions,
        /** The address people open the dashboard at (https://monitor.example.com); gives alert emails an "Open in dashboard" link. */
        String publicUrl,
        /** Lets agents declare their own checks in a file beside their config. On unless the admin turns it off. */
        @DefaultValue("true") boolean agentDeclaredChecks,
        /** Most checks one server may declare. */
        @DefaultValue("200") int agentDeclaredChecksLimit,
        /** Optional sign-in with OpenID Connect access tokens (Keycloak and others); off unless an issuer is set. */
        @DefaultValue Jwt jwt,
        /** The hub's own administrator account, for when no other sign-in works; on unless turned off. */
        @DefaultValue LocalAdmin localAdmin,
        /** False makes the hub headless: no dashboard is served, from ui-dir or from inside the jar. */
        @DefaultValue("true") boolean uiEnabled,
        /** Where inside the jar the dashboard sits; a jar built without one serves none. Only tests change it. */
        @DefaultValue("classpath:/ui/") String embeddedUi,
        /** How the dashboard that comes from inside the jar behaves; ui-dir has its own config.json. */
        @DefaultValue Ui ui) {

    /** Settings of the dashboard, served to the page as config.json. */
    public record Ui(@DefaultValue Proxy proxy) {

        public static final Ui DEFAULT = new Ui(Proxy.DEFAULT);
    }

    /**
     * For a deployment where a reverse proxy signs people in: the page then calls the proxy's path on its own site,
     * sends only {@code headers} and never holds a token. {@code headers} unset means X-Web-Client: 1.
     */
    public record Proxy(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("/web-api") String apiBase,
            Map<String, String> headers,
            @DefaultValue("/oauth2/start") String signInUrl,
            @DefaultValue("/oauth2/sign_out") String signOutUrl) {

        public static final Proxy DEFAULT = new Proxy(false, "/web-api", null, "/oauth2/start", "/oauth2/sign_out");
    }

    /**
     * One administrator with a password, kept by the hub itself. Signing in with it returns an ADMIN token that
     * lasts {@code sessionHours}. The password is stored only as a salted hash. Without {@code password} a random
     * one is generated at first start and printed once; {@code newPassword} does that again (recovery).
     */
    public record LocalAdmin(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("admin") String username,
            /** Used only to create the account, 12 to 128 characters; ignored once the account exists. */
            String password,
            @DefaultValue("false") boolean newPassword,
            /** How long a sign-in lasts, 1 to 168 hours. */
            @DefaultValue("12") int sessionHours) {

        public static final LocalAdmin DEFAULT = new LocalAdmin(true, "admin", null, false, 12);
    }

    /**
     * How to check an access token sent as a bearer token: it must be signed by a key the issuer publishes, name
     * this issuer and audience, not be expired, and hold {@code adminRole} in {@code rolesClaim}.
     */
    public record Jwt(
            /** The provider's issuer address, e.g. https://auth.example.com/realms/main. Setting it turns JWT sign-in on. */
            String issuer,
            /** The audience (aud) a token must contain, so a token meant for another API is refused. */
            String audience,
            /** Where the provider's signing keys are. Unset means the address in the issuer's discovery document. */
            String jwksUri,
            /** Where the roles are in the token; dots walk into nested objects, as in Keycloak's realm_access.roles. */
            @DefaultValue("realm_access.roles") String rolesClaim,
            /** The role that makes a user an administrator of the hub. Tokens without it are refused. */
            String adminRole) {

        public static final Jwt DISABLED = new Jwt(null, null, null, "realm_access.roles", null);

        public boolean enabled() {
            return issuer != null && !issuer.isBlank();
        }
    }
}

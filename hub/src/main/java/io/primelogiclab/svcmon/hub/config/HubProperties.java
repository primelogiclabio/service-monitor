package io.primelogiclab.svcmon.hub.config;

import java.nio.file.Path;
import java.util.List;

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
        /** Optional sign-in with OpenID Connect access tokens (Keycloak and others); off unless an issuer is set. */
        @DefaultValue Jwt jwt) {

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

package io.primelogiclab.svcmon.hub.security;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Optional sign-in with an OpenID Connect access token (Keycloak, Auth0, Entra and so on). The caller sends the
 * token as {@code Authorization: Bearer <JWT>}, usually added by a reverse proxy after it has signed the user in,
 * and the hub checks it itself: the signature against the provider's published keys, the issuer, the audience and
 * the expiry. Nothing is trusted because of where the request came from. A token whose role claim holds the
 * configured admin role gets ADMIN access; any other valid token is refused.
 *
 * <p>Only asymmetric signatures are accepted, so "alg none" and a public key passed off as an HMAC secret fail.
 * The keys come from the issuer's discovery document the first time they are needed (so the hub starts even when
 * the provider is down) and are cached; a key the hub has not seen yet triggers a rate-limited refresh, and the
 * cached keys keep working through a provider outage.
 */
@Component
public class JwtAuth {

    public enum Kind {
        /** Not a JWT, or JWT sign-in is off: check it as one of the hub's own tokens. */
        NOT_JWT,
        /** Not accepted: bad signature, wrong issuer or audience, expired, malformed. */
        INVALID,
        /** The provider's keys could not be fetched, so the token could be neither accepted nor refused. */
        UNAVAILABLE,
        /** A valid token for a user who is not an administrator. */
        FORBIDDEN,
        /** A valid token for an administrator. */
        ADMIN
    }

    public record Result(Kind kind, String user, long expiresAtMillis, String reason) {
        static final Result NOT_JWT = new Result(Kind.NOT_JWT, null, 0, null);

        static Result of(Kind kind, String reason) {
            return new Result(kind, null, 0, reason);
        }
    }

    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final int MAX_USER_LENGTH = 100;
    private static final long RETRY_AFTER_MS = 10_000;
    private static final Pattern SHAPE = Pattern.compile("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);
    private static final Logger log = LoggerFactory.getLogger(JwtAuth.class);

    private final HubProperties.Jwt cfg;
    private final String issuer;
    private final String[] rolesPath;
    private final DefaultResourceRetriever retriever = new DefaultResourceRetriever(3000, 3000, 256 * 1024);

    private DefaultJWTProcessor<SecurityContext> processor;
    private long failedAt;

    @Autowired
    public JwtAuth(HubProperties props) {
        this(props.jwt() == null ? HubProperties.Jwt.DISABLED : props.jwt(), null);
    }

    /** Tests give the keys directly instead of fetching them. */
    JwtAuth(HubProperties.Jwt cfg, JWKSource<SecurityContext> keys) {
        this.cfg = cfg;
        if (!cfg.enabled()) {
            // A half-set group of settings would leave sign-in silently off, so fail the start-up instead.
            if (notBlank(cfg.audience()) || notBlank(cfg.adminRole()) || notBlank(cfg.jwksUri())) {
                throw new IllegalStateException("svcmon.jwt.issuer is required when other svcmon.jwt settings are set");
            }
            this.issuer = null;
            this.rolesPath = null;
            return;
        }
        this.issuer = stripSlash(requireUrl("svcmon.jwt.issuer", cfg.issuer()));
        if (!notBlank(cfg.audience())) {
            throw new IllegalStateException("svcmon.jwt.audience is required when svcmon.jwt.issuer is set");
        }
        if (!notBlank(cfg.adminRole())) {
            throw new IllegalStateException("svcmon.jwt.admin-role is required when svcmon.jwt.issuer is set");
        }
        if (!notBlank(cfg.rolesClaim())) {
            throw new IllegalStateException("svcmon.jwt.roles-claim must not be blank");
        }
        if (notBlank(cfg.jwksUri())) {
            requireUrl("svcmon.jwt.jwks-uri", cfg.jwksUri());
        }
        this.rolesPath = cfg.rolesClaim().strip().split("\\.");
        if (keys != null) {
            this.processor = processor(keys);
        }
        log.info("Sign-in with OIDC tokens is on: issuer {}, audience {}, role '{}' in '{}'", issuer, cfg.audience(),
                cfg.adminRole(), cfg.rolesClaim());
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    /** True when JWT sign-in is on and this bearer value has the shape of a JWT (the hub's own tokens do not). */
    public boolean handles(String raw) {
        return cfg.enabled() && raw != null && raw.length() <= MAX_TOKEN_LENGTH && SHAPE.matcher(raw).matches();
    }

    public Result verify(String raw) {
        if (!handles(raw)) {
            return Result.NOT_JWT;
        }
        DefaultJWTProcessor<SecurityContext> p;
        try {
            p = processor();
        } catch (IOException | RuntimeException e) {
            return Result.of(Kind.UNAVAILABLE, "could not load the signing keys from " + issuer + ": " + e.getMessage());
        }
        JWTClaimsSet claims;
        try {
            claims = p.process(raw, null);
        } catch (ParseException | BadJOSEException e) {
            return Result.of(Kind.INVALID, e.getMessage());
        } catch (JOSEException e) {
            // The only thing that fails here that is not the token's fault is fetching the keys.
            return Result.of(Kind.UNAVAILABLE, "could not check the signature: " + e.getMessage());
        }
        String user = user(claims);
        Date exp = claims.getExpirationTime();
        long expiresAt = exp == null ? 0 : exp.getTime();
        if (!roles(claims.getClaims()).contains(cfg.adminRole().strip())) {
            return new Result(Kind.FORBIDDEN, user, expiresAt, "does not have the role '" + cfg.adminRole() + "'");
        }
        return new Result(Kind.ADMIN, user, expiresAt, null);
    }

    /**
     * A signed-in browser's session cookie is sent on any request, so a proxy that turns the cookie into a bearer
     * token can be driven by a foreign page. A socket cannot carry the custom header the proxy uses to stop that,
     * so the browser's own Sec-Fetch-Site must say the page is on this origin (or that the user typed it); a
     * browser too old to send it must not send an Origin either. Scripts, which send neither, are not affected.
     */
    public static boolean fromOwnPage(String secFetchSite, String origin) {
        if (secFetchSite != null) {
            return secFetchSite.equals("same-origin") || secFetchSite.equals("none");
        }
        return origin == null;
    }

    private synchronized DefaultJWTProcessor<SecurityContext> processor() throws IOException {
        if (processor != null) {
            return processor;
        }
        long now = System.currentTimeMillis();
        if (now - failedAt < RETRY_AFTER_MS && failedAt != 0) {
            throw new IOException("the last attempt failed less than " + RETRY_AFTER_MS / 1000 + " s ago");
        }
        try {
            URL jwks = URI.create(notBlank(cfg.jwksUri()) ? cfg.jwksUri().strip() : discover()).toURL();
            JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(jwks, retriever)
                    .retrying(true).outageTolerant(true).build();
            processor = processor(keys);
            log.info("Using the token signing keys published by {}", issuer);
            return processor;
        } catch (IOException | RuntimeException e) {
            failedAt = now;
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    private DefaultJWTProcessor<SecurityContext> processor(JWKSource<SecurityContext> keys) {
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                Set.of(cfg.audience().strip()),
                new JWTClaimsSet.Builder().issuer(issuer).build(),
                Set.of("exp", "sub"),
                null));
        return p;
    }

    /** The provider's discovery document says where its keys are, and must name the same issuer. */
    private String discover() throws IOException {
        URL url = URI.create(issuer + "/.well-known/openid-configuration").toURL();
        try {
            Map<String, Object> doc = JSONObjectUtils.parse(retriever.retrieveResource(url).getContent());
            if (!issuer.equals(stripSlash(String.valueOf(doc.get("issuer"))))) {
                throw new IOException("the discovery document names another issuer");
            }
            Object jwks = doc.get("jwks_uri");
            if (!(jwks instanceof String uri)) {
                throw new IOException("the discovery document has no jwks_uri");
            }
            requireUrl("jwks_uri", uri);
            return uri;
        } catch (ParseException e) {
            throw new IOException("the discovery document is not valid JSON");
        }
    }

    private String user(JWTClaimsSet claims) {
        Object name = claims.getClaim("preferred_username");
        String raw = name instanceof String s && !s.isBlank() ? s : claims.getSubject();
        return clean(raw == null ? "" : raw);
    }

    /** Follows the claim path (default realm_access.roles); a claim whose own name holds dots is tried first. */
    private List<String> roles(Map<String, Object> claims) {
        Object value = claims.get(cfg.rolesClaim().strip());
        if (value == null) {
            value = claims;
            for (String step : rolesPath) {
                value = value instanceof Map<?, ?> m ? m.get(step) : null;
            }
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return value instanceof String s ? List.of(s.strip().split("[\\s,]+")) : List.of();
    }

    /** The name ends up in logs, so keep it to one printable line. */
    static String clean(String raw) {
        String one = raw.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
        return one.length() > MAX_USER_LENGTH ? one.substring(0, MAX_USER_LENGTH) : one;
    }

    /** https, or http for a local provider while developing; no credentials, query or fragment in the address. */
    private static String requireUrl(String setting, String value) {
        String text = value == null ? "" : value.strip();
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            boolean ok = (scheme.equals("https") || (scheme.equals("http") && local))
                    && !host.isEmpty() && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
            if (!ok) {
                throw new IllegalStateException(setting + ": '" + text + "' must be an https address with no user, query or"
                        + " fragment (http is allowed only for localhost)");
            }
            uri.toURL();
            return text;
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException(setting + ": '" + text + "' is not a valid address");
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

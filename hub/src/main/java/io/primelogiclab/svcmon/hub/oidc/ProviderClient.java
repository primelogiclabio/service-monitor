package io.primelogiclab.svcmon.hub.oidc;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.assertions.jwt.JWTAssertionDetails;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.PrivateKeyJWT;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.id.Audience;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.JWTID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import io.primelogiclab.svcmon.hub.config.HubProperties.Oidc.ClientAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The hub's side of OpenID Connect with one provider: its discovery document, the address a browser is sent to, and
 * turning the code that comes back into a checked ID token. The ID token's signature (with the provider's published
 * keys), issuer, audience, expiry and nonce are all checked; a symmetric signature (HS256), which would need a shared
 * secret, is never accepted. Nothing the provider sends back is logged beyond its error code and description, made
 * one line and shortened.
 */
public class ProviderClient {

    /** Why a sign-in with the provider failed, as a code the dashboard explains; {@code detail} is for the log. */
    public static class ProviderException extends Exception {
        public final String code;

        ProviderException(String code, String detail) {
            super(detail);
            this.code = code;
        }

        ProviderException(String code, String detail, Throwable cause) {
            super(detail, cause);
            this.code = code;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    static final int TIMEOUT_MS = 10_000;
    private static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    private static final long RETRY_MS = 10_000;
    /** How long the hub's proof is good for; it is sent at once, and Keycloak's default limit is a minute. */
    private static final int ASSERTION_SECONDS = 60;
    /** Signatures an ID token may carry. */
    private static final Set<JWSAlgorithm> ID_TOKEN_ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384,
            JWSAlgorithm.RS512, JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256,
            JWSAlgorithm.ES384, JWSAlgorithm.ES512, JWSAlgorithm.EdDSA);

    private record Provider(OIDCProviderMetadata metadata, IDTokenValidator validator) {
    }

    private final OidcSettings settings;
    private final SigningKey key;
    private volatile Provider provider;
    private volatile String problem = "not asked yet";
    private volatile long lastTry;

    /** {@code key} is the hub's key pair for private_key_jwt, null with a client secret. */
    public ProviderClient(OidcSettings settings, SigningKey key) {
        this.settings = settings;
        this.key = key;
    }

    /** True once the discovery document was read; {@link #problem()} says why not otherwise. */
    public boolean reachable() {
        return provider != null;
    }

    public String problem() {
        return provider != null ? null : problem;
    }

    /** Reads the discovery document now if it has not been read; for the start-up, off the main thread. */
    public void warmUp() {
        try {
            provider();
        } catch (ProviderException e) {
            log.warn("The identity provider {} could not be reached yet: {}; people can still sign in with a password"
                    + " from the internal network", settings.issuer(), e.getMessage());
        }
    }

    /** Where to send the browser to sign in. */
    public URI authorizationUri(State state, Nonce nonce, CodeVerifier verifier) throws ProviderException {
        OIDCProviderMetadata m = provider().metadata();
        return new AuthenticationRequest.Builder(new ResponseType(ResponseType.Value.CODE), settings.scope(),
                settings.clientId(), settings.redirectUri())
                .endpointURI(m.getAuthorizationEndpointURI())
                .state(state)
                .nonce(nonce)
                .codeChallenge(verifier, CodeChallengeMethod.S256)
                .build()
                .toURI();
    }

    /** Swaps the code for tokens and returns the ID token's claims, once they have all been checked. */
    public Map<String, Object> exchange(String code, CodeVerifier verifier, Nonce nonce) throws ProviderException {
        Provider p = provider();
        URI tokenEndpoint = p.metadata().getTokenEndpointURI();
        TokenRequest request = new TokenRequest(tokenEndpoint, clientAuthentication(tokenEndpoint),
                new AuthorizationCodeGrant(new AuthorizationCode(code), settings.redirectUri(), verifier));
        TokenResponse response;
        try {
            HTTPRequest http = request.toHTTPRequest();
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            response = OIDCTokenResponseParser.parse(http.send());
        } catch (IOException e) {
            throw new ProviderException("provider_unavailable", "the token endpoint could not be reached: " + e, e);
        } catch (ParseException e) {
            throw new ProviderException("invalid_response", "the token endpoint's answer is not a token response", e);
        }
        if (!response.indicatesSuccess()) {
            ErrorObject error = response.toErrorResponse().getErrorObject();
            // The provider's own description is the only place it says why (Keycloak logs no reason), so it is kept.
            String why = clean(error.getDescription());
            throw new ProviderException("provider_refused", "the token endpoint answered " + error.getHTTPStatusCode()
                    + " " + clean(error.getCode()) + (why.isEmpty() ? "" : ": " + why));
        }
        if (!(response.toSuccessResponse() instanceof OIDCTokenResponse tokens)
                || tokens.getOIDCTokens().getIDToken() == null) {
            throw new ProviderException("invalid_response", "the token endpoint sent no ID token");
        }
        JWT idToken = tokens.getOIDCTokens().getIDToken();
        try {
            IDTokenClaimsSet claims = p.validator().validate(idToken, nonce);
            return claims.toJSONObject();
        } catch (BadJOSEException | JOSEException e) {
            throw new ProviderException("invalid_response", "the ID token failed its checks: " + clean(e.getMessage()), e);
        }
    }

    /** The provider's sign-out address that comes back to the dashboard, or null when it has none. */
    public URI endSessionUri() {
        Provider p = provider;
        if (p == null || p.metadata().getEndSessionEndpointURI() == null) {
            return null;
        }
        URI end = p.metadata().getEndSessionEndpointURI();
        String query = "client_id=" + encode(settings.clientId().getValue()) + "&post_logout_redirect_uri="
                + encode(settings.postLogoutRedirectUri().toString());
        return URI.create(end + (end.getRawQuery() == null ? "?" : "&") + query);
    }

    private ClientAuthentication clientAuthentication(URI tokenEndpoint) throws ProviderException {
        if (settings.clientAuth() == ClientAuth.CLIENT_SECRET) {
            try {
                return new ClientSecretBasic(settings.clientId(),
                        new Secret(OidcSettings.readSecret(settings.clientSecretFile())));
            } catch (IllegalStateException e) {
                throw new ProviderException("provider_unavailable", e.getMessage(), e);
            }
        }
        try {
            return new PrivateKeyJWT(clientAssertion(settings.clientId(), tokenEndpoint, settings.signingAlgorithm(),
                    key));
        } catch (JOSEException | IllegalArgumentException e) {
            throw new ProviderException("provider_unavailable", "could not sign the hub's proof: " + e, e);
        }
    }

    /**
     * The hub's proof: a JWT about itself (iss and sub its client id) for the token endpoint only (its audience),
     * good for {@value #ASSERTION_SECONDS} seconds from when it was made (iat and nbf, which Entra ID requires) and
     * used once (jti), signed with its private key. Keycloak refuses one without iat that lasts longer than that
     * ("Token expiration is too far in the future"). The header names the key by its id and,
     * when the key has a certificate, by the certificate's thumbprints (x5t#S256, and the older x5t that some
     * providers, Entra ID among them, match an uploaded certificate by).
     */
    @SuppressWarnings("deprecation") // x5t: SHA-1 is deprecated for it, but providers still ask for it
    static SignedJWT clientAssertion(ClientID clientId, URI tokenEndpoint, JWSAlgorithm alg, SigningKey key)
            throws JOSEException {
        RSAKey k = key.current();
        JWSHeader.Builder header = new JWSHeader.Builder(alg).type(JOSEObjectType.JWT).keyID(k.getKeyID());
        X509Certificate cert = key.certificate();
        if (cert != null) {
            header.x509CertSHA256Thumbprint(FileKey.thumbprint(cert, "SHA-256"))
                    .x509CertThumbprint(FileKey.thumbprint(cert, "SHA-1"));
        }
        Date now = new Date();
        JWTClaimsSet claims = new JWTAssertionDetails(new Issuer(clientId), new Subject(clientId.getValue()),
                List.of(new Audience(tokenEndpoint)), new Date(now.getTime() + ASSERTION_SECONDS * 1000L), now, now,
                new JWTID(), null).toJWTClaimsSet();
        SignedJWT jwt = new SignedJWT(header.build(), claims);
        jwt.sign(new RSASSASigner(k));
        return jwt;
    }

    private Provider provider() throws ProviderException {
        Provider p = provider;
        if (p != null) {
            return p;
        }
        synchronized (this) {
            if (provider != null) {
                return provider;
            }
            long now = System.currentTimeMillis();
            if (now - lastTry < RETRY_MS && lastTry != 0) {
                throw new ProviderException("provider_unavailable", problem);
            }
            lastTry = now;
            try {
                provider = discover();
                problem = null;
                log.info("Identity provider {} answered; people may sign in with it", settings.issuer());
                return provider;
            } catch (ProviderException e) {
                problem = e.getMessage();
                throw e;
            }
        }
    }

    private Provider discover() throws ProviderException {
        OIDCProviderMetadata m;
        try {
            // Also checks that the document names this very issuer.
            m = OIDCProviderMetadata.resolve(settings.issuer(), TIMEOUT_MS, TIMEOUT_MS);
        } catch (GeneralException e) {
            throw new ProviderException("provider_unavailable", "its discovery document is not usable: "
                    + clean(e.getMessage()), e);
        } catch (IOException e) {
            throw new ProviderException("provider_unavailable", "its discovery document could not be read: " + e, e);
        }
        for (URI endpoint : List.of(required(m.getAuthorizationEndpointURI(), "authorization_endpoint"),
                required(m.getTokenEndpointURI(), "token_endpoint"), required(m.getJWKSetURI(), "jwks_uri"))) {
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !OidcSettings.loopback(endpoint)) {
                throw new ProviderException("provider_unavailable", "its endpoint " + endpoint + " is not https");
            }
        }
        Set<JWSAlgorithm> algorithms = new LinkedHashSet<>();
        List<JWSAlgorithm> offered = m.getIDTokenJWSAlgs();
        (offered == null || offered.isEmpty() ? List.of(JWSAlgorithm.RS256) : offered).stream()
                .filter(ID_TOKEN_ALGORITHMS::contains).forEach(algorithms::add);
        if (algorithms.isEmpty()) {
            throw new ProviderException("provider_unavailable", "it signs ID tokens only in ways the hub does not accept"
                    + " (a shared-secret signature, or none)");
        }
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.create(m.getJWKSetURI().toURL(),
                    new DefaultResourceRetriever(TIMEOUT_MS, TIMEOUT_MS, MAX_DOCUMENT_BYTES)).build();
        } catch (MalformedURLException e) {
            throw new ProviderException("provider_unavailable", "its jwks_uri is not an address", e);
        }
        IDTokenValidator validator = new IDTokenValidator(m.getIssuer(), settings.clientId(),
                new JWSVerificationKeySelector<>(algorithms, keys), null);
        validator.setMaxClockSkew(60);
        return new Provider(m, validator);
    }

    private static URI required(URI uri, String name) throws ProviderException {
        if (uri == null) {
            throw new ProviderException("provider_unavailable", "its discovery document has no " + name);
        }
        return uri;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Text from the provider, kept to one short printable line before it reaches the log. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }
}

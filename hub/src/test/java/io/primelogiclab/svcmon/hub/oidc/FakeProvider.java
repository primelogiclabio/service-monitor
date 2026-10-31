package io.primelogiclab.svcmon.hub.oidc;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in OpenID provider on 127.0.0.1, for tests: a discovery document, its keys, and a token endpoint that
 * checks what a real one checks (the code once, the redirect URI, PKCE, and the hub's private_key_jwt against the
 * hub's own /api/v1/oidc/jwks) before it signs an ID token. The browser's visit to the sign-in page is
 * {@link #approve}. The public fields let a test make it misbehave.
 */
public final class FakeProvider implements AutoCloseable {

    /** What one sign-in at the provider gave: its code is swapped for an ID token with these claims. */
    private record Grant(String clientId, String redirectUri, String challenge, String nonce, Map<String, Object> claims) {
    }

    public final String issuer;
    /** The ID token says this nonce, audience or issuer instead of the right one, when set. */
    public volatile String nonceOverride;
    public volatile String audienceOverride;
    public volatile String issuerOverride;
    /** Seconds the ID token lasts; negative makes it expired. */
    public volatile long idTokenSeconds = 300;
    /** Signs the ID token with a key it does not publish. */
    public volatile boolean signWithUnknownKey;
    /** The key id, certificate thumbprint (x5t#S256) and audience of the last client assertion the hub sent. */
    public volatile String lastAssertionKeyId;
    public volatile String lastAssertionThumbprint;
    public volatile List<String> lastAssertionAudience;

    private final HttpServer server;
    private final RSAKey key;
    private final RSAKey strayKey;
    private final Map<String, Grant> codes = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile String hubJwks;

    private FakeProvider() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID("provider-key").generate();
        strayKey = new RSAKeyGenerator(2048).keyID("provider-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test";
        server.createContext("/realms/test/.well-known/openid-configuration", ex -> send(ex, 200, Map.of(
                "issuer", issuer,
                "authorization_endpoint", issuer + "/auth",
                "token_endpoint", issuer + "/token",
                "jwks_uri", issuer + "/certs",
                "end_session_endpoint", issuer + "/logout",
                "response_types_supported", List.of("code"),
                "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256"),
                "token_endpoint_auth_methods_supported", List.of("private_key_jwt"),
                "code_challenge_methods_supported", List.of("S256"))));
        server.createContext("/realms/test/certs", ex -> send(ex, 200,
                json.readValue(new JWKSet((JWK) key.toPublicJWK()).toString(), Map.class)));
        server.createContext("/realms/test/token", this::token);
        server.start();
    }

    public static FakeProvider start() {
        try {
            return new FakeProvider();
        } catch (IOException | JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where the hub's public key is, to check its client assertions. */
    public void hubJwks(String url) {
        hubJwks = url;
    }

    public void reset() {
        nonceOverride = null;
        audienceOverride = null;
        issuerOverride = null;
        idTokenSeconds = 300;
        signWithUnknownKey = false;
    }

    /** The person signs in at the provider's page the hub sent them to; returns the code it sends them back with. */
    public String approve(URI authorizationRequest, Map<String, Object> claims) {
        Map<String, String> q = query(authorizationRequest.getRawQuery());
        if (!"code".equals(q.get("response_type")) || !"S256".equals(q.get("code_challenge_method"))
                || !q.getOrDefault("scope", "").contains("openid") || q.get("state") == null || q.get("nonce") == null) {
            throw new IllegalArgumentException("not a code flow request with PKCE, state and nonce: " + q);
        }
        String code = UUID.randomUUID().toString();
        codes.put(code, new Grant(q.get("client_id"), q.get("redirect_uri"), q.get("code_challenge"), q.get("nonce"),
                claims));
        return code;
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form;
        try (InputStream in = ex.getRequestBody()) {
            form = query(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Grant grant = form.get("code") == null ? null : codes.remove(form.get("code"));
        if (grant == null || !"authorization_code".equals(form.get("grant_type"))
                || !grant.redirectUri().equals(form.get("redirect_uri"))) {
            send(ex, 400, Map.of("error", "invalid_grant"));
            return;
        }
        if (form.get("code_verifier") == null || !s256(form.get("code_verifier")).equals(grant.challenge())) {
            send(ex, 400, Map.of("error", "invalid_grant", "error_description", "PKCE"));
            return;
        }
        if (!clientAssertionOk(form, grant.clientId())) {
            send(ex, 401, Map.of("error", "invalid_client"));
            return;
        }
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuerOverride != null ? issuerOverride : issuer)
                    .audience(audienceOverride != null ? audienceOverride : grant.clientId())
                    .issueTime(new Date(now))
                    .expirationTime(new Date(now + idTokenSeconds * 1000))
                    .claim("nonce", nonceOverride != null ? nonceOverride : grant.nonce());
            grant.claims().forEach(claims::claim);
            SignedJWT idToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims.build());
            idToken.sign(new RSASSASigner(signWithUnknownKey ? strayKey : key));
            send(ex, 200, Map.of("access_token", "at-" + UUID.randomUUID(), "token_type", "Bearer", "expires_in", 300,
                    "id_token", idToken.serialize()));
        } catch (JOSEException e) {
            send(ex, 500, Map.of("error", "server_error"));
        }
    }

    /** The hub's private_key_jwt: signed by a key the hub publishes, about itself, for this token endpoint, fresh. */
    private boolean clientAssertionOk(Map<String, String> form, String clientId) {
        if (!"urn:ietf:params:oauth:client-assertion-type:jwt-bearer".equals(form.get("client_assertion_type"))
                || form.get("client_assertion") == null || hubJwks == null) {
            return false;
        }
        try {
            SignedJWT assertion = SignedJWT.parse(form.get("client_assertion"));
            JWK hubKey = JWKSet.load(new URL(hubJwks)).getKeyByKeyId(assertion.getHeader().getKeyID());
            if (hubKey == null || !assertion.verify(new RSASSAVerifier(hubKey.toRSAKey()))) {
                return false;
            }
            JWTClaimsSet c = assertion.getJWTClaimsSet();
            lastAssertionKeyId = assertion.getHeader().getKeyID();
            lastAssertionThumbprint = assertion.getHeader().getX509CertSHA256Thumbprint() == null ? null
                    : assertion.getHeader().getX509CertSHA256Thumbprint().toString();
            lastAssertionAudience = c.getAudience();
            return clientId.equals(c.getIssuer()) && clientId.equals(c.getSubject())
                    && c.getAudience().contains(issuer + "/token") && c.getJWTID() != null
                    && c.getExpirationTime() != null && c.getExpirationTime().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    private void send(HttpExchange ex, int status, Map<?, ?> body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

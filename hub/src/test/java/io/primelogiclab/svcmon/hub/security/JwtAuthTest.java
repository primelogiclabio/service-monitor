package io.primelogiclab.svcmon.hub.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.security.JwtAuth.Kind;
import org.junit.jupiter.api.Test;

class JwtAuthTest {

    static final String ISSUER = "https://auth.example.test/realms/main";

    static final JwtFixture PROVIDER = new JwtFixture(ISSUER);

    static HubProperties.Jwt config(String rolesClaim) {
        return new HubProperties.Jwt(ISSUER, JwtFixture.AUDIENCE, null, rolesClaim, JwtFixture.ADMIN_ROLE);
    }

    /** JWT sign-in against the fixture provider's keys, without any network. */
    static JwtAuth auth() {
        return auth(config("realm_access.roles"));
    }

    static JwtAuth auth(HubProperties.Jwt cfg) {
        return new JwtAuth(cfg, new ImmutableJWKSet<SecurityContext>(new JWKSet(PROVIDER.key.toPublicJWK())));
    }

    private static JwtAuth.Result verify(String token) {
        return auth().verify(token);
    }

    @Test
    void anAdministratorsTokenGetsIn() {
        long exp = System.currentTimeMillis() + 120_000;
        JwtAuth.Result r = verify(PROVIDER.sign(PROVIDER.claims().expirationTime(new Date(exp)).build()));
        assertEquals(Kind.ADMIN, r.kind(), r.reason());
        assertEquals("alice", r.user());
        assertEquals(exp / 1000, r.expiresAtMillis() / 1000);
    }

    @Test
    void theNameFallsBackToTheSubjectAndStaysOnOneLine() {
        assertEquals("5b0f3c1e-0000-4000-8000-000000000001", verify(PROVIDER.sign(
                PROVIDER.claims().claim("preferred_username", null).build())).user());
        assertEquals("a b", verify(PROVIDER.sign(PROVIDER.claims().claim("preferred_username", "a\r\nb").build())).user());
        assertEquals(100, JwtAuth.clean("x".repeat(500)).length());
    }

    @Test
    void anAudienceListIsFineAsLongAsItNamesThisHub() {
        JWTClaimsSet both = PROVIDER.claims().audience(List.of("https://other.example.test/api", JwtFixture.AUDIENCE)).build();
        assertEquals(Kind.ADMIN, verify(PROVIDER.sign(both)).kind());
    }

    @Test
    void aValidTokenWithoutTheAdminRoleIsRefusedButNamed() {
        for (Object realmAccess : new Object[] {
                Map.of("roles", List.of("offline_access", "platform-admin-readonly")),
                Map.of("roles", List.of()),
                Map.of("other", List.of(JwtFixture.ADMIN_ROLE)),
                "platform-admin",
                null}) {
            JwtAuth.Result r = verify(PROVIDER.sign(PROVIDER.claims().claim("realm_access", realmAccess).build()));
            assertEquals(Kind.FORBIDDEN, r.kind(), String.valueOf(realmAccess));
            assertEquals("alice", r.user());
        }
    }

    @Test
    void theRoleClaimPathIsASetting() {
        JWTClaimsSet flat = PROVIDER.claims().claim("roles", List.of(JwtFixture.ADMIN_ROLE)).build();
        assertEquals(Kind.ADMIN, auth(config("roles")).verify(PROVIDER.sign(flat)).kind());
        assertEquals(Kind.FORBIDDEN, auth(config("realm_access.roles")).verify(PROVIDER.sign(
                PROVIDER.claims().claim("realm_access", null).claim("roles", List.of(JwtFixture.ADMIN_ROLE)).build())).kind(),
                "the default path is not the flat claim");

        JWTClaimsSet nested = PROVIDER.claims()
                .claim("resource_access", Map.of("hub", Map.of("roles", List.of(JwtFixture.ADMIN_ROLE)))).build();
        assertEquals(Kind.ADMIN, auth(config("resource_access.hub.roles")).verify(PROVIDER.sign(nested)).kind());

        JWTClaimsSet dotted = PROVIDER.claims().claim("https://example.test/roles", List.of(JwtFixture.ADMIN_ROLE)).build();
        assertEquals(Kind.ADMIN, auth(config("https://example.test/roles")).verify(PROVIDER.sign(dotted)).kind());

        JWTClaimsSet spaced = PROVIDER.claims().claim("scope", "openid " + JwtFixture.ADMIN_ROLE).build();
        assertEquals(Kind.ADMIN, auth(config("scope")).verify(PROVIDER.sign(spaced)).kind());
    }

    @Test
    void tokensThatAreNotForThisHubOrNotCurrentAreRefused() {
        long now = System.currentTimeMillis();
        Map<String, JWTClaimsSet> bad = Map.of(
                "another issuer", PROVIDER.claims().issuer("https://evil.example.test/realms/main").build(),
                "issuer with a different path", PROVIDER.claims().issuer(ISSUER + "x").build(),
                "another audience", PROVIDER.claims().audience("https://golf.example.test/api").build(),
                "no audience", PROVIDER.claims().audience((String) null).build(),
                "expired", PROVIDER.claims().expirationTime(new Date(now - 5 * 60_000)).build(),
                "not valid yet", PROVIDER.claims().notBeforeTime(new Date(now + 5 * 60_000)).build(),
                "no expiry", PROVIDER.claims().expirationTime(null).build(),
                "no subject", PROVIDER.claims().subject(null).build());
        bad.forEach((why, claims) -> assertEquals(Kind.INVALID, verify(PROVIDER.sign(claims)).kind(), why));
    }

    @Test
    void aTokenSignedByAnyoneElseIsRefused() throws Exception {
        JWTClaimsSet good = PROVIDER.claims().build();
        var impostor = new RSAKeyGenerator(2048).keyID("key-1").generate();
        assertEquals(Kind.INVALID, verify(JwtFixture.sign(impostor, "key-1", good)).kind(), "same key id, other key");
        assertEquals(Kind.INVALID, verify(JwtFixture.sign(impostor, "unknown", good)).kind(), "unknown key id");
    }

    @Test
    void editingATokenAfterItWasSignedBreaksIt() {
        String[] parts = PROVIDER.sign(PROVIDER.claims().claim("realm_access", Map.of("roles", List.of("nobody"))).build())
                .split("\\.");
        String admin = Base64.getUrlEncoder().withoutPadding().encodeToString(
                PROVIDER.claims().build().toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(Kind.INVALID, verify(parts[0] + "." + admin + "." + parts[2]).kind());
    }

    @Test
    void unsignedAndSymmetricallySignedTokensAreRefused() throws Exception {
        JWTClaimsSet claims = PROVIDER.claims().build();
        assertEquals(Kind.INVALID, verify(new PlainJWT(claims).serialize()).kind(), "alg none");

        byte[] secret = "0123456789abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(), claims);
        hmac.sign(new MACSigner(secret));
        assertEquals(Kind.INVALID, verify(hmac.serialize()).kind(), "HMAC with a guessed secret");

        // The classic confusion: the public key, which anyone can download, used as the HMAC secret.
        SignedJWT confused = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(), claims);
        confused.sign(new MACSigner(PROVIDER.key.toPublicJWK().toRSAPublicKey().getEncoded()));
        assertEquals(Kind.INVALID, verify(confused.serialize()).kind(), "public key as HMAC secret");
    }

    @Test
    void onlyJwtShapedValuesAreHandledAndTheHubsOwnTokensAreNot() {
        JwtAuth on = auth();
        assertTrue(on.handles(PROVIDER.admin()));
        for (String own : new String[] {"svm_a_wJ1mC5xk8f2lT9p0Qe7yRz4uHbVnS3dGaXoYiKtLsE", "test-admin-token-0123456789abcdef0123456789abcdef",
                "a.b.c", "eyJ", "eyJhbGciOiJSUzI1NiJ9", "eyJhbGciOiJSUzI1NiJ9.e30", "eyJ.x.y.z", "", null}) {
            assertFalse(on.handles(own), String.valueOf(own));
            assertEquals(Kind.NOT_JWT, on.verify(own).kind());
        }
        assertFalse(on.handles(PROVIDER.admin() + "A".repeat(9000)), "oversized values are not parsed");
        assertEquals(Kind.INVALID, on.verify("eyJhbGciOiJSUzI1NiJ9.e30.c2ln").kind(), "shaped like a JWT but not one");

        JwtAuth off = new JwtAuth(HubProperties.Jwt.DISABLED, null);
        assertFalse(off.enabled());
        assertFalse(off.handles(PROVIDER.admin()), "with JWT sign-in off every bearer value is a hub token");
        assertEquals(Kind.NOT_JWT, off.verify(PROVIDER.admin()).kind());
    }

    @Test
    void settingsThatCouldLockEveryoneOutOrLetTheWrongTokensInStopTheStart() {
        assertStartFails("audience", new HubProperties.Jwt(ISSUER, null, null, "realm_access.roles", "admin"));
        assertStartFails("audience", new HubProperties.Jwt(ISSUER, " ", null, "realm_access.roles", "admin"));
        assertStartFails("admin-role", new HubProperties.Jwt(ISSUER, "aud", null, "realm_access.roles", null));
        assertStartFails("roles-claim", new HubProperties.Jwt(ISSUER, "aud", null, " ", "admin"));
        assertStartFails("issuer", new HubProperties.Jwt(null, "aud", null, "realm_access.roles", "admin"));
        assertStartFails("issuer", new HubProperties.Jwt(null, null, null, "realm_access.roles", "admin"));
        assertStartFails("issuer", new HubProperties.Jwt("http://auth.example.test/realms/main", "aud", null, "r", "a"));
        assertStartFails("issuer", new HubProperties.Jwt("auth.example.test", "aud", null, "r", "a"));
        assertStartFails("issuer", new HubProperties.Jwt("https://u:p@auth.example.test", "aud", null, "r", "a"));
        assertStartFails("issuer", new HubProperties.Jwt("https://auth.example.test/r?x=1", "aud", null, "r", "a"));
        assertStartFails("jwks-uri", new HubProperties.Jwt(ISSUER, "aud", "http://keys.example.test/jwks", "r", "a"));
        // http is fine for a provider on this machine while developing
        new JwtAuth(new HubProperties.Jwt("http://localhost:8180/realms/main", "aud", null, "r", "a"), null);
        new JwtAuth(new HubProperties.Jwt("http://127.0.0.1:8180/realms/main/", "aud", "http://[::1]:8180/jwks", "r", "a"), null);
    }

    private static void assertStartFails(String mentions, HubProperties.Jwt cfg) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JwtAuth(cfg, null));
        assertTrue(e.getMessage().contains("svcmon.jwt." + mentions), e.getMessage());
    }

    @Test
    void whenTheProviderCannotBeReachedTheAnswerIsUnavailableNotInvalid() throws IOException {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        JwtAuth auth = new JwtAuth(new HubProperties.Jwt("http://127.0.0.1:" + closed + "/realms/main", JwtFixture.AUDIENCE,
                null, "realm_access.roles", JwtFixture.ADMIN_ROLE), null);
        assertEquals(Kind.UNAVAILABLE, auth.verify(PROVIDER.admin()).kind());
        assertEquals(Kind.UNAVAILABLE, auth.verify(PROVIDER.admin()).kind(), "and again without hammering the provider");
    }

    @Test
    void theDiscoveryDocumentMustNameTheSameIssuerAndSaysWhereTheKeysAre() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String issuer = base + "/realms/main";
        JwtFixture provider = new JwtFixture(issuer);
        serve(server, "/realms/main/.well-known/openid-configuration",
                "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + base + "/keys\"}");
        serve(server, "/keys", provider.jwksJson());
        serve(server, "/other/.well-known/openid-configuration",
                "{\"issuer\":\"https://someone-else.example.test\",\"jwks_uri\":\"" + base + "/keys\"}");
        server.start();
        try {
            JwtAuth good = new JwtAuth(new HubProperties.Jwt(issuer, JwtFixture.AUDIENCE, null, "realm_access.roles",
                    JwtFixture.ADMIN_ROLE), null);
            assertEquals(Kind.ADMIN, good.verify(provider.admin()).kind());

            JwtAuth lying = new JwtAuth(new HubProperties.Jwt(base + "/other", JwtFixture.AUDIENCE, null,
                    "realm_access.roles", JwtFixture.ADMIN_ROLE), null);
            assertEquals(Kind.UNAVAILABLE, lying.verify(new JwtFixture(base + "/other").admin()).kind(),
                    "keys from a document that names another issuer are not used");
        } finally {
            server.stop(0);
        }
    }

    private static void serve(HttpServer server, String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void changesFromAnotherSiteCannotUseASessionTurnedIntoAToken() {
        assertTrue(JwtAuth.fromOwnPage(null, null), "a script or the CLI sends neither header");
        assertTrue(JwtAuth.fromOwnPage("same-origin", "https://monitor.example.test"));
        assertTrue(JwtAuth.fromOwnPage("none", null));
        for (String site : new String[] {"cross-site", "same-site", "bogus"}) {
            assertFalse(JwtAuth.fromOwnPage(site, "https://evil.example.test"), site);
        }
        assertFalse(JwtAuth.fromOwnPage(null, "https://evil.example.test"), "an Origin without Sec-Fetch-Site cannot be verified");
    }
}

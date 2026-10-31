package io.primelogiclab.svcmon.hub.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.config.HubProperties.Oidc;
import io.primelogiclab.svcmon.hub.config.HubProperties.Oidc.ClientAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** svcmon.oidc is checked at start-up, and every mistake names its setting. */
class OidcSettingsTest {

    private static final String ISSUER = "https://auth.example.com/realms/main";

    private static HubProperties props(String publicUrl, Oidc oidc) {
        return new HubProperties(Path.of("."), 30, 30, 90, null, false, null, List.of(), false, 50, publicUrl, true, 200,
                HubProperties.Users.DEFAULT, HubProperties.Sessions.DEFAULT, true, "classpath:/ui/",
                HubProperties.Access.DEFAULT, HubProperties.Audit.DEFAULT, oidc);
    }

    private static Oidc oidc(String issuer, String clientId, ClientAuth auth, Path secret, String alg, List<String> scopes,
                             String rolesClaim, String adminRole, String readRole, String label) {
        return new Oidc(issuer, clientId, auth, secret, null, null, alg, scopes, rolesClaim, adminRole, readRole,
                "preferred_username", label);
    }

    private static Oidc withFiles(ClientAuth auth, Path keyFile, Path certFile) {
        return new Oidc(ISSUER, "c", auth, null, keyFile, certFile, "RS256", List.of("openid"), "roles", "a", null,
                "preferred_username", "SSO");
    }

    @Test
    void aKeyPairFromFilesNeedsBothFilesAndPrivateKeyJwt() {
        Path key = Path.of("keys", "prod", "signing-private.pem");
        Path cert = Path.of("keys", "prod", "signing-cert.pem");
        OidcSettings s = OidcSettings.from(props("https://m.example", withFiles(ClientAuth.PRIVATE_KEY_JWT, key, cert)));
        assertEquals(key, s.privateKeyFile());
        assertEquals(cert, s.certificateFile());
        assertTrue(refused("https://m.example", withFiles(ClientAuth.PRIVATE_KEY_JWT, key, null))
                .startsWith("svcmon.oidc.certificate-file:"));
        assertTrue(refused("https://m.example", withFiles(ClientAuth.PRIVATE_KEY_JWT, null, cert))
                .startsWith("svcmon.oidc.private-key-file:"));
        assertTrue(refused("https://m.example", withFiles(ClientAuth.CLIENT_SECRET, key, cert))
                .startsWith("svcmon.oidc.private-key-file:"));
    }

    private static Oidc valid() {
        return oidc(ISSUER, "service-monitor", ClientAuth.PRIVATE_KEY_JWT, null, "RS256", List.of("openid", "profile"),
                "realm_access.roles", "platform-admin", null, "Sign in with Keycloak");
    }

    private static String refused(String publicUrl, Oidc oidc) {
        return assertThrows(IllegalStateException.class, () -> OidcSettings.from(props(publicUrl, oidc))).getMessage();
    }

    @Test
    void offUnlessAnIssuerIsSet() {
        assertNull(OidcSettings.from(props(null, Oidc.DEFAULT)));
        assertNull(OidcSettings.from(props(null, oidc(" ", null, null, null, null, null, null, null, null, null))));
    }

    @Test
    void aValidSetUpBuildsTheRedirectFromThePublicUrl() {
        OidcSettings s = OidcSettings.from(props("https://monitor.example.com/", valid()));
        assertEquals(ISSUER, s.issuer().getValue());
        assertEquals(URI.create("https://monitor.example.com/api/v1/oidc/callback"), s.redirectUri());
        assertEquals(URI.create("https://monitor.example.com/"), s.postLogoutRedirectUri());
        assertEquals(List.of("realm_access", "roles"), s.rolesPath());
        assertEquals(List.of("openid", "profile"), s.scope().toStringList());
        assertNull(s.readRole(), "administrators only");
        assertEquals(JWSAlgorithm.RS256, s.signingAlgorithm());

        Oidc ps = oidc("https://tenant.auth0.com/", "c", ClientAuth.PRIVATE_KEY_JWT, null, "ps256", List.of("openid"),
                "roles", "admin", "viewer", "SSO");
        OidcSettings auth0 = OidcSettings.from(props("https://monitor.example.com/monitor", ps));
        assertEquals("https://tenant.auth0.com/", auth0.issuer().getValue(), "the issuer is kept exactly, slash and all");
        assertEquals(JWSAlgorithm.PS256, auth0.signingAlgorithm());
        assertEquals(URI.create("https://monitor.example.com/monitor/api/v1/oidc/callback"), auth0.redirectUri());
    }

    @Test
    void theIssuerIsHttpsExceptOnThisMachine() {
        assertTrue(refused("https://m.example", oidc("http://auth.example.com/realms/main", "c",
                ClientAuth.PRIVATE_KEY_JWT, null, "RS256", List.of("openid"), "roles", "a", null, "SSO"))
                .startsWith("svcmon.oidc.issuer:"));
        assertTrue(refused("https://m.example", oidc(ISSUER + "?x=1", "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("openid"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.issuer:"));
        assertTrue(refused("https://m.example", oidc("https://user@auth.example.com", "c", ClientAuth.PRIVATE_KEY_JWT,
                null, "RS256", List.of("openid"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.issuer:"));
        assertEquals("http://127.0.0.1:8180/realms/main", OidcSettings.from(props("http://localhost:8080",
                oidc("http://127.0.0.1:8180/realms/main", "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                        List.of("openid"), "roles", "a", null, "SSO"))).issuer().getValue());
    }

    @Test
    void whatIsRequiredIsNamed() {
        assertTrue(refused("https://m.example", oidc(ISSUER, null, ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("openid"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.client-id:"));
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("openid"), "roles", " ", null, "SSO")).startsWith("svcmon.oidc.admin-role:"));
        assertTrue(refused(null, valid()).contains("svcmon.public-url"));
        assertTrue(refused("javascript:alert(1)", valid()).startsWith("svcmon.public-url:"));
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("openid"), "roles", "same", "same", "SSO")).startsWith("svcmon.oidc.read-role:"));
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("openid"), "realm_access..roles", "a", null, "SSO")).startsWith("svcmon.oidc.roles-claim:"));
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("openid"), "roles", "a", null, "x".repeat(61))).startsWith("svcmon.oidc.button-label:"));
    }

    @Test
    void scopesIncludeOpenidAndTheSignatureIsRsaOnly() {
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("profile"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.scopes:"));
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, null, "RS256",
                List.of("openid", "two words"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.scopes:"));
        for (String alg : List.of("HS256", "none", "ES256", "RS-256")) {
            assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, null, alg,
                    List.of("openid"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.signing-algorithm:"), alg);
        }
    }

    @Test
    void aClientSecretComesFromAFileAndOnlyForClientSecret(@TempDir Path dir) throws IOException {
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.CLIENT_SECRET, null, "RS256",
                List.of("openid"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.client-secret-file:"));
        Path missing = dir.resolve("missing");
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.CLIENT_SECRET, missing, "RS256",
                List.of("openid"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.client-secret-file:"));
        Path empty = Files.writeString(dir.resolve("empty"), "\n");
        assertTrue(refused("https://m.example", oidc(ISSUER, "c", ClientAuth.CLIENT_SECRET, empty, "RS256",
                List.of("openid"), "roles", "a", null, "SSO")).startsWith("svcmon.oidc.client-secret-file:"));

        Path secret = Files.writeString(dir.resolve("secret"), "s3cret-value\n");
        OidcSettings s = OidcSettings.from(props("https://m.example", oidc(ISSUER, "c", ClientAuth.CLIENT_SECRET, secret,
                "RS256", List.of("openid"), "roles", "a", null, "SSO")));
        assertEquals("s3cret-value", OidcSettings.readSecret(s.clientSecretFile()));
        String withKey = refused("https://m.example", oidc(ISSUER, "c", ClientAuth.PRIVATE_KEY_JWT, secret, "RS256",
                List.of("openid"), "roles", "a", null, "SSO"));
        assertTrue(withKey.startsWith("svcmon.oidc.client-secret-file:"), withKey);
        assertTrue(!withKey.contains("s3cret-value") && !refused("https://m.example", oidc(ISSUER, "c",
                ClientAuth.CLIENT_SECRET, dir, "RS256", List.of("openid"), "roles", "a", null, "SSO"))
                .contains("s3cret-value"), "a secret never appears in a message");
    }
}

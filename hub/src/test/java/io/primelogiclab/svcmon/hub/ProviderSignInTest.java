package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.primelogiclab.svcmon.hub.live.LiveClient;
import io.primelogiclab.svcmon.hub.oidc.FakeProvider;
import io.primelogiclab.svcmon.hub.oidc.HubKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real hub signing people in with an identity provider (a stand-in on 127.0.0.1): the code flow with PKCE and
 * the hub's private_key_jwt, roles mapped to hub roles, users made at the first sign-in and linked by subject, and
 * from outside the provider as the only way in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class ProviderSignInTest {

    private static final FakeProvider PROVIDER = FakeProvider.start();
    private static final String ADMIN_TOKEN = "provider-sign-in-test-token-0123456789abcdef";
    private static final String PASSWORD = "a first password here";
    private static final String NEW_PASSWORD = "correct horse battery staple";
    private static final String PUBLIC_URL = "https://monitor.test";
    private static final String OUTSIDE = NetworkAccessTest.OUTSIDE;
    private static final String[] HTTPS = {"X-Forwarded-Proto", "https"};
    private static Path dir;
    /** The plain-HTTP cookie of the password admin's sign-in, made inside. */
    private static String adminCookie;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        dir = Files.createTempDirectory("svcmon-provider-sign-in");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN_TOKEN);
        registry.add("svcmon.users.initial-admin-password", () -> PASSWORD);
        registry.add("svcmon.access.allow-outside", () -> "true");
        registry.add("svcmon.public-url", () -> PUBLIC_URL);
        registry.add("svcmon.oidc.issuer", () -> PROVIDER.issuer);
        registry.add("svcmon.oidc.client-id", () -> "svcmon-test");
        registry.add("svcmon.oidc.roles-claim", () -> "realm_access.roles");
        registry.add("svcmon.oidc.admin-role", () -> "platform-admin");
        registry.add("svcmon.oidc.read-role", () -> "monitor-viewer");
        registry.add("svcmon.oidc.button-label", () -> "Sign in with Test");
    }

    @AfterAll
    static void stop() {
        PROVIDER.close();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void pointTheProviderAtTheHub() {
        PROVIDER.reset();
        PROVIDER.hubJwks("http://127.0.0.1:" + port + "/api/v1/oidc/jwks");
    }

    /** How a sign-in with the provider ended: where the hub sent the browser, and the session cookie if any. */
    private record Flow(String location, String cookie) {

        String error() {
            int i = location.indexOf("signin_error=");
            return i < 0 ? null : location.substring(i + "signin_error=".length());
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("X-Svcmon-Request", "1");
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> admin(String method, String path, String body) throws Exception {
        return send(method, path, body, "Authorization", "Bearer " + ADMIN_TOKEN);
    }

    private static Map<String, Object> person(String subject, String name, String... roles) {
        return Map.of("sub", subject, "preferred_username", name, "realm_access", Map.of("roles", List.of(roles)));
    }

    /** Starts a sign-in, lets {@code claims} sign in at the provider, and comes back to the hub. */
    private Flow signIn(Map<String, Object> claims, String... headers) throws Exception {
        HttpResponse<String> start = send("GET", "/api/v1/oidc/start", null, headers);
        assertEquals(302, start.statusCode(), start.body());
        String to = start.headers().firstValue("Location").orElseThrow();
        if (to.startsWith(PUBLIC_URL)) {
            return new Flow(to, null); // refused before the provider
        }
        String binding = cookie(start, bindingName(headers)).orElseThrow();
        String code = PROVIDER.approve(URI.create(to), claims);
        String state = FakeProvider.query(URI.create(to).getRawQuery()).get("state");
        return back("code=" + code + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8), binding, headers);
    }

    private Flow back(String query, String binding, String... headers) throws Exception {
        List<String> h = new ArrayList<>(Arrays.asList(headers));
        if (binding != null) {
            h.addAll(List.of("Cookie", bindingName(headers) + "=" + binding));
        }
        HttpResponse<String> r = send("GET", "/api/v1/oidc/callback?" + query, null, h.toArray(String[]::new));
        assertEquals(302, r.statusCode(), r.body());
        return new Flow(r.headers().firstValue("Location").orElseThrow(), cookie(r, sessionName(headers)).orElse(null));
    }

    private static boolean secure(String... headers) {
        return Arrays.asList(headers).contains("https");
    }

    private static String bindingName(String... headers) {
        return secure(headers) ? "__Host-svcmon-oidc" : "svcmon-oidc";
    }

    private static String sessionName(String... headers) {
        return secure(headers) ? "__Host-svcmon" : "svcmon";
    }

    private static java.util.Optional<String> cookie(HttpResponse<String> r, String name) {
        return r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';'))).filter(v -> !v.isEmpty()).findFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> session(String cookie, String... headers) throws Exception {
        List<String> h = new ArrayList<>(Arrays.asList(headers));
        h.addAll(List.of("Cookie", sessionName(headers) + "=" + cookie));
        return map(send("GET", "/api/v1/session", null, h.toArray(String[]::new)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> user(String name) throws Exception {
        List<Map<String, Object>> users = json.readValue(admin("GET", "/api/v1/users", null).body(), List.class);
        return users.stream().filter(u -> name.equals(u.get("username"))).findFirst().orElse(null);
    }

    @Test
    @Order(1)
    void theHubsPublicKeyIsServedToAnyoneAndKeptInTheDataFolder() throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/oidc/jwks", null, "X-Forwarded-For", OUTSIDE);
        assertEquals(200, r.statusCode(), r.body());
        JWKSet keys = JWKSet.parse(r.body());
        assertEquals(1, keys.getKeys().size());
        RSAKey published = keys.getKeys().get(0).toRSAKey();
        assertFalse(published.isPrivate(), "only the public half is served");
        assertFalse(r.body().contains("\"d\""), r.body());
        assertEquals(3072, published.size());

        RSAKey stored = new HubKey(dir).current();
        assertTrue(stored.isPrivate());
        assertEquals(stored.getKeyID(), published.getKeyID());

        // The first administrator chooses a password, for the password tests below.
        HttpResponse<String> first = send("POST", "/api/v1/session",
                "{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}");
        String c = cookie(first, "svcmon").orElseThrow();
        HttpResponse<String> changed = send("PUT", "/api/v1/me/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}",
                "Cookie", "svcmon=" + c);
        adminCookie = cookie(changed, "svcmon").orElseThrow();
    }

    @Test
    @Order(2)
    void anAdministratorAtTheProviderBecomesAHubAdministrator() throws Exception {
        Flow f = signIn(person("sub-alice", "alice", "platform-admin", "offline_access"));
        assertEquals(PUBLIC_URL + "/", f.location());
        assertNotNull(f.cookie());

        Map<String, Object> s = session(f.cookie());
        assertEquals(Boolean.TRUE, s.get("signedIn"));
        assertEquals(Map.of("id", user("alice").get("id"), "name", "alice", "role", "ADMIN", "provider", true),
                s.get("user"));
        assertEquals(Boolean.TRUE, user("alice").get("provider"));
        assertEquals(200, send("GET", "/api/v1/users", null, "Cookie", "svcmon=" + f.cookie()).statusCode(),
                "an administrator's screens are open to them");

        assertEquals(new HubKey(dir).current().getKeyID(), PROVIDER.lastAssertionKeyId, "the hub signed with its key");
        assertEquals(List.of(PROVIDER.issuer + "/token"), PROVIDER.lastAssertionAudience);
    }

    @Test
    @Order(3)
    void aReadRoleGivesReadOnlyAndNeitherRoleIsRefused() throws Exception {
        Flow bob = signIn(person("sub-bob", "bob", "monitor-viewer"));
        assertNull(bob.error());
        assertEquals("READ", ((Map<?, ?>) session(bob.cookie()).get("user")).get("role"));
        assertEquals(200, send("GET", "/api/v1/status", null, "Cookie", "svcmon=" + bob.cookie()).statusCode());
        assertEquals(403, send("POST", "/api/v1/checks", "{}", "Cookie", "svcmon=" + bob.cookie()).statusCode());
        assertEquals(403, send("GET", "/api/v1/oidc", null, "Cookie", "svcmon=" + bob.cookie()).statusCode(),
                "the provider's settings are for administrators");

        Flow carol = signIn(person("sub-carol", "carol", "offline_access"));
        assertEquals("no_role", carol.error());
        assertEquals(PUBLIC_URL + "/?signin_error=no_role", carol.location());
        assertNull(carol.cookie());
        assertNull(user("carol"), "nobody is made for someone without a role");
        String audit = admin("GET", "/api/v1/audit?category=sign_in", null).body();
        assertTrue(audit.contains("\"outcome\":\"no_role\"") && audit.contains("\"actor\":\"carol\""), audit);
    }

    @Test
    @Order(4)
    void theRoleFollowsTheProviderAtEverySignIn() throws Exception {
        Flow before = signIn(person("sub-dan", "dan", "monitor-viewer"));
        Flow promoted = signIn(person("sub-dan", "dan", "platform-admin"));
        assertEquals("ADMIN", ((Map<?, ?>) session(promoted.cookie()).get("user")).get("role"));
        assertEquals("ADMIN", ((Map<?, ?>) session(before.cookie()).get("user")).get("role"),
                "the earlier sign-in has the new role at once");

        assertEquals("no_role", signIn(person("sub-dan", "dan")).error());
        assertEquals(Boolean.FALSE, session(promoted.cookie()).get("signedIn"), "losing the role ends the sign-ins");
        assertEquals(401, send("GET", "/api/v1/status", null, "Cookie", "svcmon=" + promoted.cookie()).statusCode());
    }

    @Test
    @Order(5)
    void aSignInFinishesOnceAndOnlyInTheBrowserThatStartedIt() throws Exception {
        HttpResponse<String> start = send("GET", "/api/v1/oidc/start", null);
        String to = start.headers().firstValue("Location").orElseThrow();
        String binding = cookie(start, "svcmon-oidc").orElseThrow();
        String code = PROVIDER.approve(URI.create(to), person("sub-eve", "eve", "platform-admin"));
        String query = "code=" + code + "&state=" + FakeProvider.query(URI.create(to).getRawQuery()).get("state");

        assertEquals("expired", back(query, null).error(), "another browser, without the cookie");
        assertEquals("expired", back(query, binding).error(), "the state was used up by that attempt");

        assertEquals("expired", back("code=x&state=never-issued", binding).error());
        assertEquals("expired", back("code=x", binding).error());

        Flow ok = signIn(person("sub-eve", "eve", "platform-admin"));
        assertNull(ok.error());
    }

    @Test
    @Order(6)
    void anIdTokenThatFailsItsChecksIsRefused() throws Exception {
        Map<String, Object> frank = person("sub-frank", "frank", "platform-admin");
        PROVIDER.nonceOverride = "someone-elses-nonce";
        assertEquals("invalid_response", signIn(frank).error());
        PROVIDER.reset();
        PROVIDER.audienceOverride = "another-client";
        assertEquals("invalid_response", signIn(frank).error());
        PROVIDER.reset();
        PROVIDER.issuerOverride = "https://evil.example/realms/test";
        assertEquals("invalid_response", signIn(frank).error());
        PROVIDER.reset();
        PROVIDER.idTokenSeconds = -300;
        assertEquals("invalid_response", signIn(frank).error());
        PROVIDER.reset();
        PROVIDER.signWithUnknownKey = true;
        assertEquals("invalid_response", signIn(frank).error());
        PROVIDER.reset();
        assertNull(user("frank"));
        assertNull(signIn(frank).error(), "and with nothing wrong it works");
    }

    @Test
    @Order(7)
    void theProviderSayingNoEndsTheSignIn(CapturedOutput output) throws Exception {
        HttpResponse<String> start = send("GET", "/api/v1/oidc/start", null);
        String to = start.headers().firstValue("Location").orElseThrow();
        String state = FakeProvider.query(URI.create(to).getRawQuery()).get("state");
        assertEquals("provider_refused", back("error=access_denied&state=" + state,
                cookie(start, "svcmon-oidc").orElseThrow()).error());

        PROVIDER.hubJwks("http://127.0.0.1:1/nowhere"); // the provider cannot check the hub's proof
        assertEquals("provider_refused", signIn(person("sub-gina", "gina", "platform-admin")).error());
        // The provider's reason is the only place that says why; Keycloak logs none.
        assertTrue(output.getOut().contains("the token endpoint answered 400 invalid_client: Unable to load the"
                + " client's keys"), "the provider's description is logged");
    }

    @Test
    @Order(8)
    void aNameAnotherUserHasIsNotTakenOver() throws Exception {
        assertEquals("username_taken", signIn(person("sub-mallory", "admin", "platform-admin")).error());
        Map<String, Object> admin = user("admin");
        assertEquals(Boolean.FALSE, admin.get("provider"), "the password administrator is untouched");
        assertEquals(200, send("GET", "/api/v1/status", null, "Cookie", "svcmon=" + adminCookie).statusCode());

        Flow viaEmail = signIn(Map.of("sub", "sub-hal", "preferred_username", "Hal 9000", "email", "hal@example.com",
                "realm_access", Map.of("roles", List.of("monitor-viewer"))));
        assertNull(viaEmail.error());
        assertNotNull(user("hal@example.com"), "a name that does not fit falls back to the email");
        assertEquals("username_unusable", signIn(Map.of("sub", "sub-ivy", "preferred_username", "Ivy Q",
                "realm_access", Map.of("roles", List.of("monitor-viewer")))).error());
    }

    @Test
    @Order(9)
    void aProviderUserHasNoPasswordAndTakesItsRoleFromTheProvider() throws Exception {
        String alice = (String) user("alice").get("id");
        HttpResponse<String> reset = admin("POST", "/api/v1/users/" + alice + "/password-reset", null);
        assertEquals(409, reset.statusCode(), reset.body());
        assertTrue(reset.body().contains("provider_account"), reset.body());
        HttpResponse<String> demote = admin("PATCH", "/api/v1/users/" + alice, "{\"role\":\"READ\"}");
        assertEquals(409, demote.statusCode(), demote.body());
        assertTrue(demote.body().contains("provider_managed"), demote.body());

        HttpResponse<String> password = send("POST", "/api/v1/session",
                "{\"username\":\"alice\",\"password\":\"" + PROVIDER.issuer + "\"}");
        assertEquals(401, password.statusCode(), "no password works for her");
        Flow f = signIn(person("sub-alice", "alice", "platform-admin"));
        HttpResponse<String> own = send("PUT", "/api/v1/me/password",
                "{\"currentPassword\":\"x\",\"newPassword\":\"" + NEW_PASSWORD + "\"}", "Cookie", "svcmon=" + f.cookie());
        assertEquals(409, own.statusCode(), own.body());

        assertEquals(200, admin("PATCH", "/api/v1/users/" + alice, "{\"disabled\":true}").statusCode());
        assertEquals("account_disabled", signIn(person("sub-alice", "alice", "platform-admin")).error(),
                "turned off on the hub, whatever the provider says");
        assertEquals(200, admin("PATCH", "/api/v1/users/" + alice, "{\"disabled\":false}").statusCode());
    }

    @Test
    @Order(10)
    void fromOutsideOnlyTheProvidersSignInWorks() throws Exception {
        String[] outside = {"X-Forwarded-For", OUTSIDE, HTTPS[0], HTTPS[1]};
        Map<String, Object> state = map(send("GET", "/api/v1/session", null, outside));
        assertEquals(Boolean.FALSE, state.get("passwordSignIn"));
        assertEquals(Boolean.TRUE, state.get("signInAllowed"));
        assertEquals(Map.of("label", "Sign in with Test", "allowed", true, "startUrl", PUBLIC_URL + "/api/v1/oidc/start"),
                state.get("provider"));
        assertEquals(Boolean.TRUE, map(send("GET", "/api/v1/session", null)).get("passwordSignIn"), "inside it may");

        HttpResponse<String> password = send("POST", "/api/v1/session",
                "{\"username\":\"admin\",\"password\":\"" + NEW_PASSWORD + "\"}", outside);
        assertEquals(403, password.statusCode(), password.body());
        assertTrue(password.body().contains("password_inside_only"), password.body());
        HttpResponse<String> passwordSession = send("GET", "/api/v1/status", null, "X-Forwarded-For", OUTSIDE,
                HTTPS[0], HTTPS[1], "Cookie", "__Host-svcmon=" + adminCookie);
        assertEquals(403, passwordSession.statusCode(), passwordSession.body());
        assertTrue(passwordSession.body().contains("password_inside_only"), "a password sign-in stays inside");

        Flow f = signIn(person("sub-alice", "alice", "platform-admin"), outside);
        assertNull(f.error());
        assertEquals(200, send("GET", "/api/v1/status", null, "X-Forwarded-For", OUTSIDE, HTTPS[0], HTTPS[1],
                "Cookie", "__Host-svcmon=" + f.cookie()).statusCode());

        assertEquals("insecure_transport", signIn(person("sub-alice", "alice", "platform-admin"),
                "X-Forwarded-For", OUTSIDE).error(), "from outside over plain HTTP the sign-in does not start");
    }

    @Test
    @Order(11)
    void aPasswordSignInsLiveTicketDoesNotWorkFromOutside() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/session/live-ticket", null, "Cookie", "svcmon=" + adminCookie);
        assertEquals(200, r.statusCode(), r.body());
        LiveClient live = LiveClient.connect(port, Map.of("X-Forwarded-For", OUTSIDE, "X-Forwarded-Proto", "https"));
        live.ticket((String) map(r).get("ticket"));
        assertEquals(1008, live.awaitClose(Duration.ofSeconds(5)));
        assertEquals("password_inside_only", live.closeReason());
    }

    @Test
    @Order(12)
    void signingOutOfAProviderSignInPointsAtTheProvidersSignOut() throws Exception {
        Flow f = signIn(person("sub-alice", "alice", "platform-admin"));
        HttpResponse<String> out = send("DELETE", "/api/v1/session", null, "Cookie", "svcmon=" + f.cookie());
        assertEquals(200, out.statusCode(), out.body());
        assertEquals(PROVIDER.issuer + "/logout?client_id=svcmon-test&post_logout_redirect_uri="
                + URLEncoder.encode(PUBLIC_URL + "/", StandardCharsets.UTF_8), map(out).get("providerSignOut"));
        assertEquals(Boolean.FALSE, session(f.cookie()).get("signedIn"));

        HttpResponse<String> password = send("POST", "/api/v1/session",
                "{\"username\":\"admin\",\"password\":\"" + NEW_PASSWORD + "\"}");
        String c = cookie(password, "svcmon").orElseThrow();
        assertEquals(204, send("DELETE", "/api/v1/session", null, "Cookie", "svcmon=" + c).statusCode());
    }

    @Test
    @Order(13)
    void administratorsSeeHowTheProviderIsSetUpButNoSecret() throws Exception {
        HttpResponse<String> r = admin("GET", "/api/v1/oidc", null);
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> v = map(r);
        RSAKey key = new HubKey(dir).current();
        assertEquals(Boolean.TRUE, v.get("configured"));
        assertEquals(PROVIDER.issuer, v.get("issuer"));
        assertEquals("svcmon-test", v.get("clientId"));
        assertEquals("private-key-jwt", v.get("clientAuth"));
        assertEquals("RS256", v.get("signingAlgorithm"));
        assertEquals(key.getKeyID(), v.get("keyId"));
        assertEquals(HubKey.pem(key), v.get("publicKeyPem"));
        assertEquals(PUBLIC_URL + "/api/v1/oidc/callback", v.get("redirectUri"));
        assertEquals(PUBLIC_URL + "/api/v1/oidc/jwks", v.get("jwksUri"));
        assertEquals(List.of("openid", "profile", "email"), v.get("scopes"));
        assertEquals("platform-admin", v.get("adminRole"));
        assertEquals("monitor-viewer", v.get("readRole"));
        assertEquals(Boolean.TRUE, v.get("reachable"));
        assertEquals(Boolean.TRUE, v.get("signOutAtProvider"));
        assertFalse(r.body().contains("PRIVATE"), r.body());
        assertFalse(r.body().contains(key.getPrivateExponent().toString()), "the private key never leaves the hub");
    }
}

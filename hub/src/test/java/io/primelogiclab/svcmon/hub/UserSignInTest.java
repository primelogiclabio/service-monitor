package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
 * The real hub on a new database: the first administrator signs in with the initial password, must change it before
 * doing anything else, and from then on is signed in by a cookie that scripts cannot read and other sites cannot use.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserSignInTest {

    private static final String ADMIN_TOKEN = "user-sign-in-test-token-0123456789abcdef01234";
    static final String NEW_PASSWORD = "correct horse battery staple";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-users");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN_TOKEN);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private static String cookie;

    /** A request as the dashboard sends it: the session cookie and, for changes, the header only it adds. */
    HttpResponse<String> send(String method, String path, String cookieValue, String body, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (!method.equals("GET")) {
            b.header("X-Svcmon-Request", "1");
        }
        if (cookieValue != null) {
            b.header("Cookie", "svcmon=" + cookieValue);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> signIn(String user, String password, String... headers) throws Exception {
        return send("POST", "/api/v1/session", null, json.writeValueAsString(Map.of("username", user, "password", password)),
                headers);
    }

    /** The value of the cookie this response sets, or null. */
    static String cookieFrom(HttpResponse<String> r, String name) {
        return r.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(HttpResponse<String> r) {
        return json.readValue(r.body(), Map.class);
    }

    @Test
    @Order(1)
    void theFirstAdministratorSignsInWithTheInitialPasswordAndMayOnlyChangeIt(CapturedOutput output) throws Exception {
        assertTrue(output.getAll().contains("Created administrator 'admin' with the initial password"), "said at start");
        assertFalse(output.getAll().matches("(?s).*password:\\s+admin.*"), "the password itself is never printed");

        HttpResponse<String> r = signIn("admin", "admin");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.startsWith("svcmon="), setCookie);
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && setCookie.contains("Path=/"),
                setCookie);
        assertFalse(setCookie.contains("Secure"), "plain HTTP inside: no Secure flag");
        assertFalse(setCookie.contains("Max-Age"), "it ends with the browser, or when the hub ends it");
        cookie = cookieFrom(r, "svcmon");
        assertFalse(r.body().contains(cookie), "the cookie's value is never in a body");
        Map<String, Object> state = map(r);
        assertEquals(Boolean.TRUE, state.get("mustChangePassword"));
        assertEquals("ADMIN", ((Map<?, ?>) state.get("user")).get("role"));

        HttpResponse<String> status = send("GET", "/api/v1/status", cookie, null);
        assertEquals(403, status.statusCode());
        assertTrue(status.body().contains("password_change_required"), status.body());
        assertEquals(200, send("GET", "/api/v1/session", cookie, null).statusCode());
        assertEquals(200, send("GET", "/api/v1/me", cookie, null).statusCode());
    }

    @Test
    @Order(2)
    void changingThePasswordChecksTheOldOneAndGivesTheSessionANewCookie() throws Exception {
        String change = json.writeValueAsString(Map.of("currentPassword", "admin", "newPassword", NEW_PASSWORD));
        HttpResponse<String> noHeader = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/me/password"))
                .header("Cookie", "svcmon=" + cookie).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(change)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noHeader.statusCode(), "another site's page cannot change it");

        HttpResponse<String> wrong = send("PUT", "/api/v1/me/password", cookie,
                json.writeValueAsString(Map.of("currentPassword", "nope", "newPassword", NEW_PASSWORD)));
        assertEquals(400, wrong.statusCode(), "400, not 401: the page must not sign out over a typo");
        assertTrue(wrong.body().contains("wrong_password"));
        HttpResponse<String> weak = send("PUT", "/api/v1/me/password", cookie,
                json.writeValueAsString(Map.of("currentPassword", "admin", "newPassword", "short")));
        assertTrue(weak.statusCode() == 400 && weak.body().contains("weak_password"), weak.body());

        HttpResponse<String> ok = send("PUT", "/api/v1/me/password", cookie, change);
        assertEquals(204, ok.statusCode(), ok.body());
        String renewed = cookieFrom(ok, "svcmon");
        assertNotNull(renewed);
        assertNotEquals(cookie, renewed);
        assertEquals(401, send("GET", "/api/v1/status", cookie, null).statusCode(), "the old cookie value is spent");
        assertEquals(200, send("GET", "/api/v1/status", renewed, null).statusCode(), "the session goes on");
        cookie = renewed;

        assertEquals(401, signIn("admin", "admin").statusCode(), "the initial password is gone");
        assertEquals(200, signIn("admin", NEW_PASSWORD).statusCode());
    }

    @Test
    @Order(3)
    void everyFailureGetsTheSameAnswerAndAnUnknownNameIsNotLogged(CapturedOutput output) throws Exception {
        HttpResponse<String> unknown = signIn("hunter2-typed-in-the-wrong-box", NEW_PASSWORD);
        HttpResponse<String> wrong = signIn("admin", NEW_PASSWORD + "x");
        assertEquals(401, unknown.statusCode());
        assertEquals(401, wrong.statusCode());
        assertEquals(unknown.body(), wrong.body(), "nothing says which part was wrong");
        assertTrue(wrong.body().contains("wrong_credentials"));
        assertFalse(output.getAll().contains("hunter2"), "a name nobody has may be a password: not logged");
        assertTrue(output.getAll().contains("Wrong sign-in from 127.0.0.1 for an unknown user"));
    }

    @Test
    @Order(4)
    void signingInNeedsJsonAndThePagesHeader() throws Exception {
        HttpResponse<String> form = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
                .header("X-Svcmon-Request", "1").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=x")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(415, form.statusCode(), "an HTML form cannot sign anyone in");
        HttpResponse<String> bare = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, bare.statusCode());
    }

    @Test
    @Order(5)
    void overHttpsTheCookieIsSecureAndBoundToTheHost() throws Exception {
        HttpResponse<String> r = signIn("admin", NEW_PASSWORD, "X-Forwarded-Proto", "https");
        assertEquals(200, r.statusCode(), r.body());
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.startsWith("__Host-svcmon=") && setCookie.contains("Secure"), setCookie);
        assertFalse(setCookie.contains("Domain"), setCookie);
        String secure = cookieFrom(r, "__Host-svcmon");
        HttpResponse<String> asPlain = send("GET", "/api/v1/status", secure, null, "X-Forwarded-Proto", "https");
        assertEquals(401, asPlain.statusCode(), "over HTTPS only the __Host- cookie counts, not one named svcmon");
    }

    @Test
    @Order(6)
    void aPasswordSomeoneElseSetWorksOnlyFromInside() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/users", cookie,
                json.writeValueAsString(Map.of("username", "dave", "role", "READ")));
        assertEquals(201, created.statusCode(), created.body());
        String temporary = (String) map(created).get("temporaryPassword");

        HttpResponse<String> outside = signIn("dave", temporary, "X-Forwarded-For", "203.0.113.7");
        assertEquals(403, outside.statusCode(), outside.body());
        assertTrue(outside.body().contains("initial_password_inside_only"));
        assertEquals(200, signIn("dave", temporary).statusCode(), "from inside it works");
    }

    @Test
    @Order(7)
    void signingOutEndsTheSessionAndClearsTheCookie() throws Exception {
        String mine = cookieFrom(signIn("admin", NEW_PASSWORD), "svcmon");
        assertEquals(200, send("GET", "/api/v1/status", mine, null).statusCode());
        HttpResponse<String> out = send("DELETE", "/api/v1/session", mine, null);
        assertEquals(204, out.statusCode());
        assertTrue(out.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
        assertEquals(401, send("GET", "/api/v1/status", mine, null).statusCode());
        assertEquals(Boolean.FALSE, map(send("GET", "/api/v1/session", mine, null)).get("signedIn"));
        assertEquals(204, send("DELETE", "/api/v1/session", null, null).statusCode(), "signing out twice is fine");
    }

    @Test
    @Order(8)
    void apiTokensKeepWorkingAndAreNotUsers() throws Exception {
        HttpResponse<String> me = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(me.body().contains("\"method\":\"token\""), me.body());
        HttpResponse<String> password = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/me/password"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, password.statusCode());
        assertTrue(password.body().contains("not_a_user"), password.body());
        int old = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/login"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        assertTrue(old >= 400 && old < 500, "the old sign-in is gone: " + old);
    }
}

package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Drives the real hub: channels are created over HTTP and real receivers (HTTP and SMTP) see what arrives. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlertDeliveryTest {

    private static final String ADMIN = "alerts-admin-token-0123456789abcdef0123456789ab";

    private static Receivers receivers;
    private static FakeSmtpServer smtp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        receivers = new Receivers();
        smtp = new FakeSmtpServer();
        Path root = Files.createTempDirectory("svcmon-alerts");
        registry.add("svcmon.data-dir", () -> root.resolve("data").toString());
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.allow-insecure-alert-urls", () -> "true"); // the receivers are plain http
        registry.add("svcmon.alert-retry-delays-ms", () -> "0,100");
        registry.add("svcmon.agent-stale-seconds", () -> "2");
        registry.add("svcmon.watchdog-initial-delay-ms", () -> "300");
        registry.add("svcmon.watchdog-interval-ms", () -> "300");
    }

    @AfterAll
    static void stopReceivers() throws IOException {
        receivers.close();
        smtp.close();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> checkIds = new ArrayList<>();
    private final List<ServerSocket> listeners = new ArrayList<>();
    private final List<String> serverIds = new ArrayList<>();

    record Resp(int status, String raw, Map<String, Object> map) {
    }

    record PortCheck(String id, int port) {
    }

    // ---- plumbing ----

    private Resp call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), parse(r.body()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String text) {
        return text != null && text.startsWith("{") ? json.readValue(text, Map.class) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String channel(String type, String name, Map<String, Object> config, List<String> statuses, Boolean enabled) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("config", config);
        if (statuses != null) {
            body.put("statuses", statuses);
        }
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        Resp r = call("POST", "/api/v1/alert-channels", ADMIN, body);
        assertEquals(201, r.status(), r.raw());
        return (String) r.map().get("id");
    }

    private String webhook(String name, String path) throws Exception {
        return channel("WEBHOOK", name, Map.of("url", receivers.url(path)), null, null);
    }

    /** A TCP check on a port nobody listens on, so running it gives DOWN; listen() later makes it UP. */
    private PortCheck closedPortCheck(String name) throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            free = s.getLocalPort();
        }
        Resp r = call("POST", "/api/v1/checks", ADMIN, Map.of("name", name, "type", "TCP",
                "target", "127.0.0.1:" + free, "intervalSeconds", 3600));
        assertEquals(201, r.status(), r.raw());
        String id = (String) r.map().get("id");
        checkIds.add(id);
        return new PortCheck(id, free);
    }

    private String run(PortCheck check) throws Exception {
        Resp r = call("POST", "/api/v1/checks/" + check.id() + "/run", ADMIN, null);
        assertEquals(200, r.status(), r.raw());
        return (String) r.map().get("status");
    }

    private ServerSocket listen(PortCheck check) throws IOException {
        ServerSocket s = new ServerSocket(check.port(), 5, InetAddress.getLoopbackAddress());
        listeners.add(s);
        return s;
    }

    private Map<String, Object> channelView(String channelId) {
        try {
            return call("GET", "/api/v1/alert-channels/" + channelId, ADMIN, null).map();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T await(String what, Supplier<T> value, Predicate<T> done) throws Exception {
        long deadline = System.currentTimeMillis() + 12_000;
        T current = value.get();
        while (!done.test(current)) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(50);
            current = value.get();
        }
        return current;
    }

    private List<Receivers.Hit> awaitHits(String path, int atLeast) throws Exception {
        return await(atLeast + " delivery(ies) to " + path, () -> receivers.hits(path), h -> h.size() >= atLeast);
    }

    private FakeSmtpServer.Mail awaitMail(String containing, int nth) throws Exception {
        List<FakeSmtpServer.Mail> found = await("mail #" + nth + " containing " + containing,
                () -> smtp.mails().stream().filter(m -> m.data().contains(containing)).toList(), l -> l.size() >= nth);
        return found.get(nth - 1);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (ServerSocket s : listeners) {
            s.close();
        }
        listeners.clear();
        for (String id : checkIds) {
            call("DELETE", "/api/v1/checks/" + id, ADMIN, null);
        }
        checkIds.clear();
        for (String id : serverIds) {
            call("DELETE", "/api/v1/servers/" + id, ADMIN, null);
        }
        serverIds.clear();
        Resp list = call("GET", "/api/v1/alert-channels", ADMIN, null);
        for (Object c : json.readValue(list.raw(), List.class)) {
            call("DELETE", "/api/v1/alert-channels/" + obj(c).get("id"), ADMIN, null);
        }
    }

    // ---- delivery ----

    @Test
    void everyChannelTypeReceivesTheChangeAndTheRecovery() throws Exception {
        String id = uid();
        String name = "chk-" + id;
        channel("WEBHOOK", "wh-" + id, Map.of("url", receivers.url("/hook/" + id),
                "headers", Map.of("X-Token", "abc"), "signingSecret", "s3cret"), null, null);
        channel("SLACK", "sl-" + id, Map.of("url", receivers.url("/slack/" + id)), null, null);
        channel("DISCORD", "dc-" + id, Map.of("url", receivers.url("/discord/" + id)), null, null);
        channel("EMAIL", "em-" + id, Map.of("smtpHost", "127.0.0.1", "smtpPort", smtp.port(), "security", "NONE",
                "from", "svcmon@example.test", "to", List.of("ops@example.test", "oncall@example.test")), null, null);

        PortCheck check = closedPortCheck(name);
        assertEquals("DOWN", run(check));

        // Webhook: JSON, custom header, HMAC signature.
        Receivers.Hit hook = awaitHits("/hook/" + id, 1).get(0);
        Map<String, Object> body = parse(hook.body());
        assertEquals("status_changed", body.get("event"));
        assertEquals("check", body.get("kind"));
        assertEquals("UNKNOWN", body.get("from"));
        assertEquals("DOWN", body.get("to"));
        assertEquals(name, obj(body.get("subject")).get("name"));
        assertEquals("TCP", obj(body.get("subject")).get("type"));
        assertEquals("127.0.0.1:" + check.port(), obj(body.get("subject")).get("target"));
        assertTrue(((String) body.get("message")).toLowerCase(Locale.ROOT).contains("refused"), String.valueOf(body.get("message")));
        assertEquals("abc", hook.headers().get("x-token"));
        assertEquals("sha256=" + WebhookSender.sign("s3cret", hook.body()), hook.headers().get("x-svcmon-signature"));
        assertTrue(hook.headers().get("content-type").startsWith("application/json"));

        // Slack: red attachment, text says what happened.
        Map<String, Object> slack = parse(awaitHits("/slack/" + id, 1).get(0).body());
        assertTrue(((String) slack.get("text")).contains("DOWN") && ((String) slack.get("text")).contains(name));
        assertEquals("#d03b3b", obj(arr(slack.get("attachments")).get(0)).get("color"));

        // Discord: embed, no mentions.
        Map<String, Object> discord = parse(awaitHits("/discord/" + id, 1).get(0).body());
        assertEquals(List.of(), obj(discord.get("allowed_mentions")).get("parse"));
        Map<String, Object> embed = obj(arr(discord.get("embeds")).get(0));
        assertTrue(((String) embed.get("title")).contains("DOWN") && ((String) embed.get("title")).contains(name));

        // Email: both recipients, subject carries the status.
        FakeSmtpServer.Mail mail = awaitMail(name, 1);
        assertEquals("svcmon@example.test", mail.from());
        assertEquals(java.util.Set.of("ops@example.test", "oncall@example.test"), new java.util.HashSet<>(mail.to()));
        assertTrue(mail.data().contains("Subject: [DOWN] " + name), mail.data());

        // Recovery is announced too.
        listen(check);
        assertEquals("UP", run(check));
        Receivers.Hit recovered = awaitHits("/hook/" + id, 2).get(1);
        Map<String, Object> second = parse(recovered.body());
        assertEquals("DOWN", second.get("from"));
        assertEquals("UP", second.get("to"));
        assertTrue(awaitMail(name, 2).data().contains("Subject: [UP] " + name));
    }

    @Test
    void channelsHonourTheirStatusFilterAndTheEnabledFlag() throws Exception {
        String id = uid();
        webhook("all-" + id, "/hook/all-" + id);
        channel("WEBHOOK", "up-" + id, Map.of("url", receivers.url("/hook/up-" + id)), List.of("UP"), null);
        String offId = channel("WEBHOOK", "off-" + id, Map.of("url", receivers.url("/hook/off-" + id)), null, false);

        PortCheck check = closedPortCheck("chk-" + id);
        assertEquals("DOWN", run(check));
        awaitHits("/hook/all-" + id, 1);
        Thread.sleep(400);
        assertEquals(0, receivers.hits("/hook/up-" + id).size(), "an UP-only channel ignores DOWN");
        assertEquals(0, receivers.hits("/hook/off-" + id).size(), "a disabled channel sends nothing");

        ServerSocket listener = listen(check);
        assertEquals("UP", run(check));
        List<Receivers.Hit> up = awaitHits("/hook/up-" + id, 1);
        assertEquals("UP", parse(up.get(0).body()).get("to"));
        assertEquals(0, receivers.hits("/hook/off-" + id).size());

        // Switching a channel on only needs the flag; its stored URL is kept.
        assertEquals(200, call("PUT", "/api/v1/alert-channels/" + offId, ADMIN, Map.of("enabled", true)).status());
        listener.close();
        assertEquals("DOWN", run(check));
        assertEquals("DOWN", parse(awaitHits("/hook/off-" + id, 1).get(0).body()).get("to"));
        assertEquals(1, receivers.hits("/hook/up-" + id).size());
    }

    @Test
    void aFailingReceiverIsRetriedThenReportedWithoutLeakingItsUrl() throws Exception {
        String id = uid();
        String channelId = webhook("bad-" + id, "/fail/" + id);
        PortCheck check = closedPortCheck("chk-" + id);
        assertEquals("DOWN", run(check));

        awaitHits("/fail/" + id, 2); // retry delays are 0 and 100 ms in this test
        Map<String, Object> channel = await("the failure to be recorded", () -> channelView(channelId), c -> c.get("lastError") != null);
        String error = (String) channel.get("lastError");
        assertTrue(error.contains("HTTP 500") && error.contains("receiver says no"), error);
        assertFalse(error.contains("/fail/"), "the URL is a secret and must not appear in errors");
        assertNull(channel.get("lastSuccessAt"));
        assertNotNull(channel.get("lastAttemptAt"));
        Thread.sleep(300);
        assertEquals(2, receivers.hits("/fail/" + id).size(), "no more than the configured attempts");
    }

    @Test
    void theTestEndpointSendsOneAlertAndReports() throws Exception {
        String id = uid();
        String good = webhook("good-" + id, "/hook/test-" + id);
        String bad = webhook("bad-" + id, "/fail/test-" + id);

        Resp ok = call("POST", "/api/v1/alert-channels/" + good + "/test", ADMIN, null);
        assertEquals(200, ok.status());
        assertEquals(true, ok.map().get("ok"));
        Map<String, Object> delivered = parse(awaitHits("/hook/test-" + id, 1).get(0).body());
        assertEquals("test", delivered.get("kind"));
        Map<String, Object> view = call("GET", "/api/v1/alert-channels/" + good, ADMIN, null).map();
        assertNotNull(view.get("lastSuccessAt"));
        assertNull(view.get("lastError"));

        Resp failed = call("POST", "/api/v1/alert-channels/" + bad + "/test", ADMIN, null);
        assertEquals(200, failed.status());
        assertEquals(false, failed.map().get("ok"));
        assertTrue(((String) failed.map().get("error")).contains("HTTP 500"));
        Thread.sleep(300);
        assertEquals(1, receivers.hits("/fail/test-" + id).size(), "a test is a single attempt");

        assertEquals(404, call("POST", "/api/v1/alert-channels/nope/test", ADMIN, null).status());
    }

    @Test
    void secretsAreMaskedInResponsesAndKeptOnUpdate() throws Exception {
        String id = uid();
        String path = "/hook/secret-" + id + "/token123";
        String channelId = channel("WEBHOOK", "wh-" + id, Map.of("url", receivers.url(path),
                "headers", Map.of("Authorization", "Bearer abc"), "signingSecret", "s3cret"), null, null);

        Resp got = call("GET", "/api/v1/alert-channels/" + channelId, ADMIN, null);
        for (String secret : List.of("token123", "Bearer abc", "s3cret")) {
            assertFalse(got.raw().contains(secret), "response leaked " + secret);
        }
        Map<String, Object> config = obj(got.map().get("config"));
        assertTrue(((String) config.get("url")).endsWith("/********"));
        assertEquals("********", config.get("signingSecret"));
        assertEquals("********", obj(config.get("headers")).get("Authorization"));

        Resp list = call("GET", "/api/v1/alert-channels", ADMIN, null);
        assertFalse(list.raw().contains("token123"));

        // Send the masked values back with a new name: the real secrets must survive.
        Map<String, Object> update = new HashMap<>();
        update.put("name", "renamed-" + id);
        update.put("statuses", List.of("DOWN"));
        update.put("config", config);
        assertEquals(200, call("PUT", "/api/v1/alert-channels/" + channelId, ADMIN, update).status());
        assertEquals(true, call("POST", "/api/v1/alert-channels/" + channelId + "/test", ADMIN, null).map().get("ok"));
        Receivers.Hit hit = awaitHits(path, 1).get(0);
        assertEquals("Bearer abc", hit.headers().get("authorization"));
        assertEquals("sha256=" + WebhookSender.sign("s3cret", hit.body()), hit.headers().get("x-svcmon-signature"));

        String mailId = channel("EMAIL", "mail-" + id, Map.of("smtpHost", "127.0.0.1", "smtpPort", smtp.port(),
                "security", "NONE", "username", "svc", "password", "hunter2", "from", "a@example.test", "to", "b@example.test"), null, null);
        Resp mail = call("GET", "/api/v1/alert-channels/" + mailId, ADMIN, null);
        assertFalse(mail.raw().contains("hunter2"));
        assertEquals("********", obj(mail.map().get("config")).get("password"));
        assertEquals("svc", obj(mail.map().get("config")).get("username"));
    }

    @Test
    void permissionsAndValidationAreEnforced() throws Exception {
        String id = uid();
        Resp viewer = call("POST", "/api/v1/tokens", ADMIN, Map.of("name", "viewer-" + id, "scope", "READ"));
        String read = (String) viewer.map().get("token");
        assertEquals(403, call("GET", "/api/v1/alert-channels", read, null).status(), "settings hold secrets: admin only");
        assertEquals(401, call("GET", "/api/v1/alert-channels", null, null).status());

        String url = receivers.url("/hook/v-" + id);
        assertEquals(400, call("POST", "/api/v1/alert-channels", ADMIN, Map.of("name", "x", "type", "WEBHOOK", "config", Map.of("url", url, "chanel", "#ops"))).status());
        assertEquals(400, call("POST", "/api/v1/alert-channels", ADMIN, Map.of("name", "x", "type", "SLACK", "config", Map.of())).status());
        assertEquals(400, call("POST", "/api/v1/alert-channels", ADMIN, Map.of("name", "x", "config", Map.of("url", url))).status());
        assertEquals(400, call("POST", "/api/v1/alert-channels", ADMIN, Map.of("name", "x", "type", "EMAIL", "config",
                Map.of("smtpHost", "smtp.example.test", "from", "nope", "to", "b@example.test"))).status());
        assertEquals(400, call("POST", "/api/v1/alert-channels", ADMIN, Map.of("name", "x", "type", "WEBHOOK", "statuses", List.of(), "config", Map.of("url", url))).status());
        assertEquals(400, call("POST", "/api/v1/alert-channels", ADMIN, "not json").status());

        String channelId = webhook("dup-" + id, "/hook/dup-" + id);
        assertEquals(409, call("POST", "/api/v1/alert-channels", ADMIN, Map.of("name", "dup-" + id, "type", "WEBHOOK", "config", Map.of("url", url))).status());
        assertEquals(400, call("PUT", "/api/v1/alert-channels/" + channelId, ADMIN, Map.of("type", "SLACK")).status(), "type cannot change");
        assertEquals(404, call("GET", "/api/v1/alert-channels/nope", ADMIN, null).status());
        assertEquals(204, call("DELETE", "/api/v1/alert-channels/" + channelId, ADMIN, null).status());
        assertEquals(404, call("DELETE", "/api/v1/alert-channels/" + channelId, ADMIN, null).status());
    }

    @Test
    void aSilentAgentRaisesAnOfflineAlertAndARecoveryAlert() throws Exception {
        String id = uid();
        String path = "/hook/watch-" + id;
        webhook("watch-" + id, path);

        String agentName = "agent-" + id;
        String enroll = (String) call("POST", "/api/v1/enrollment-tokens", ADMIN, Map.of("ttlMinutes", 5)).map().get("token");
        Resp registered = call("POST", "/api/v1/agent/register", enroll, Map.of("name", agentName));
        assertEquals(201, registered.status(), registered.raw());
        serverIds.add((String) registered.map().get("serverId"));
        String agentToken = (String) registered.map().get("token");
        String heartbeat = "{\"results\":[]}";
        assertEquals(200, call("POST", "/api/v1/agent/report", agentToken, heartbeat).status());

        Thread.sleep(1000); // let the watchdog see the agent online before it goes quiet
        Receivers.Hit offline = await("an offline alert", () -> receivers.hits(path).stream()
                .filter(h -> "DOWN".equals(parse(h.body()).get("to"))).toList(), l -> !l.isEmpty()).get(0);
        Map<String, Object> down = parse(offline.body());
        assertEquals("server", down.get("kind"));
        assertEquals(agentName, obj(obj(down.get("subject")).get("server")).get("name"));
        assertTrue(((String) down.get("message")).startsWith("No report from the agent"), String.valueOf(down.get("message")));

        assertEquals(200, call("POST", "/api/v1/agent/report", agentToken, heartbeat).status());
        Receivers.Hit back = await("a recovery alert", () -> receivers.hits(path).stream()
                .filter(h -> "UP".equals(parse(h.body()).get("to"))).toList(), l -> !l.isEmpty()).get(0);
        assertEquals("The agent is reporting again", parse(back.body()).get("message"));
    }
}

package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maintenance windows on the real hub: agents register and report over HTTP, a real receiver sees what is sent.
 * Agents here go offline after 2 s without a report, so each one that must stay online has a heartbeat running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaintenanceTest {

    private static final String ADMIN = "maint-admin-token-0123456789abcdef0123456789abcd";

    private static Receivers receivers;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        receivers = new Receivers();
        Path root = Files.createTempDirectory("svcmon-maintenance");
        registry.add("svcmon.data-dir", () -> root.resolve("data").toString());
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        registry.add("svcmon.allow-insecure-alert-urls", () -> "true");
        registry.add("svcmon.alert-retry-delays-ms", () -> "0");
        registry.add("svcmon.agent-stale-seconds", () -> "2");
        registry.add("svcmon.watchdog-initial-delay-ms", () -> "300");
        registry.add("svcmon.watchdog-interval-ms", () -> "300");
    }

    @AfterAll
    static void stopReceivers() throws IOException {
        receivers.close();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    MaintenanceService maintenance;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Agent> agents = new ArrayList<>();

    record Resp(int status, String raw, Map<String, Object> map) {
    }

    /** A registered agent; {@link #beat} keeps it online until {@link #stop}. */
    final class Agent {
        final String serverId;
        final String name;
        final String token;
        private volatile Thread heart;

        Agent(String serverId, String name, String token) {
            this.serverId = serverId;
            this.name = name;
            this.token = token;
        }

        Agent beat() {
            stop();
            heart = Thread.ofVirtual().start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        call("POST", "/api/v1/agent/report", token, "{\"results\":[]}");
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        // the hub is shutting down
                    }
                }
            });
            return this;
        }

        void stop() {
            Thread t = heart;
            if (t != null) {
                t.interrupt();
                heart = null;
            }
        }
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

    private Agent agent(String name) throws Exception {
        String enroll = (String) call("POST", "/api/v1/enrollment-tokens", ADMIN, Map.of("ttlMinutes", 5)).map().get("token");
        Resp r = call("POST", "/api/v1/agent/register", enroll, Map.of("name", name));
        assertEquals(201, r.status(), r.raw());
        Agent a = new Agent((String) r.map().get("serverId"), name, (String) r.map().get("token"));
        agents.add(a);
        return a;
    }

    private String agentCheck(Agent a, String name) throws Exception {
        Resp r = call("POST", "/api/v1/checks", ADMIN, Map.of("name", name, "type", "SERVICE", "target", name,
                "runFrom", "AGENT", "serverId", a.serverId, "intervalSeconds", 60));
        assertEquals(201, r.status(), r.raw());
        return (String) r.map().get("id");
    }

    private void report(Agent a, String checkId, String status) throws Exception {
        Map<String, Object> result = Map.of("checkId", checkId, "ts", System.currentTimeMillis(), "status", status,
                "message", "service is " + status.toLowerCase(java.util.Locale.ROOT));
        Resp r = call("POST", "/api/v1/agent/report", a.token, Map.of("results", List.of(result)));
        assertEquals(200, r.status(), r.raw());
        assertEquals(1, ((Number) r.map().get("accepted")).intValue(), r.raw());
    }

    private Resp open(Agent a, int minutes, String reason, Boolean untilBack) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("minutes", minutes);
        if (reason != null) {
            body.put("reason", reason);
        }
        if (untilBack != null) {
            body.put("untilBack", untilBack);
        }
        return call("POST", "/api/v1/servers/" + a.serverId + "/maintenance", ADMIN, body);
    }

    /** The server's maintenance window as /status shows it, or null. */
    private Map<String, Object> window(Agent a) {
        try {
            Map<String, Object> status = call("GET", "/api/v1/status", ADMIN, null).map();
            for (Object c : arr(status.get("clusters"))) {
                for (Object s : arr(obj(c).get("servers"))) {
                    if (a.serverId.equals(obj(s).get("id"))) {
                        Object m = obj(s).get("maintenance");
                        return m == null ? null : obj(m);
                    }
                }
            }
            throw new IllegalStateException("server not in /status: " + a.name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Alerts delivered to {@code path} about this server (its agent or its checks). */
    private List<Map<String, Object>> about(String path, Agent a) {
        return receivers.hits(path).stream()
                .map(h -> parse(h.body()))
                .filter(b -> obj(b.get("subject")).get("server") != null
                        && a.serverId.equals(obj(obj(b.get("subject")).get("server")).get("id")))
                .toList();
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

    private void awaitOffline(Agent a) throws Exception {
        await(a.name + " to go offline", () -> {
            try {
                Map<String, Object> s = call("GET", "/api/v1/status", ADMIN, null).map();
                return arr(s.get("clusters")).stream().flatMap(c -> arr(obj(c).get("servers")).stream())
                        .map(MaintenanceTest::obj).filter(s2 -> a.serverId.equals(s2.get("id")))
                        .anyMatch(s2 -> Boolean.FALSE.equals(s2.get("agentOnline")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, b -> b);
        Thread.sleep(700); // a couple of watchdog and maintenance rounds see it too
    }

    private List<Map<String, Object>> audit(String kind) throws Exception {
        Map<String, Object> page = call("GET", "/api/v1/audit?category=change&limit=500", ADMIN, null).map();
        return arr(page.get("events")).stream().map(MaintenanceTest::obj).filter(e -> kind.equals(e.get("kind"))).toList();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Agent a : agents) {
            a.stop();
            call("DELETE", "/api/v1/servers/" + a.serverId, ADMIN, null);
        }
        agents.clear();
        Resp list = call("GET", "/api/v1/alert-channels", ADMIN, null);
        for (Object c : json.readValue(list.raw(), List.class)) {
            call("DELETE", "/api/v1/alert-channels/" + obj(c).get("id"), ADMIN, null);
        }
    }

    private String webhook(String id) throws Exception {
        String path = "/hook/maint-" + id;
        Resp r = call("POST", "/api/v1/alert-channels", ADMIN, Map.of("name", "maint-" + id, "type", "WEBHOOK",
                "config", Map.of("url", receivers.url(path))));
        assertEquals(201, r.status(), r.raw());
        return path;
    }

    // ---- tests ----

    @Test
    void aServerInMaintenanceSendsNothingWhileAnotherStillAlerts() throws Exception {
        String id = uid();
        String hook = webhook(id);
        Agent a = agent("a-" + id).beat();
        Agent b = agent("b-" + id).beat();
        String ca = agentCheck(a, "svc-a-" + id);
        String cb = agentCheck(b, "svc-b-" + id);
        report(a, ca, "UP");
        report(b, cb, "UP");

        Resp opened = open(a, 30, "kernel update", false);
        assertEquals(200, opened.status(), opened.raw());
        assertEquals(a.serverId, opened.map().get("serverId"));
        assertEquals("kernel update", opened.map().get("reason"));
        assertEquals(false, opened.map().get("untilBack"));
        assertEquals("token", opened.map().get("startedByKind"));
        long endsAt = ((Number) opened.map().get("endsAt")).longValue();
        assertTrue(Math.abs(endsAt - System.currentTimeMillis() - 30 * 60_000L) < 10_000, "ends in 30 minutes");
        assertEquals("kernel update", window(a).get("reason"));
        assertNull(window(b), "only the server it was opened for");

        report(a, ca, "DOWN");
        report(b, cb, "DOWN");
        await("b's alert", () -> about(hook, b), l -> !l.isEmpty());
        Thread.sleep(500);
        assertEquals(List.of(), about(hook, a), "nothing about a server in maintenance");

        // Ended by hand: what is not as it was alerts once.
        assertEquals(204, call("DELETE", "/api/v1/servers/" + a.serverId + "/maintenance", ADMIN, null).status());
        Map<String, Object> after = await("a's alert after maintenance", () -> about(hook, a), l -> !l.isEmpty()).get(0);
        assertEquals("check", after.get("kind"));
        assertEquals(ca, obj(after.get("subject")).get("id"));
        assertEquals("UP", after.get("from"));
        assertEquals("DOWN", after.get("to"));
        assertEquals(MaintenanceService.AFTER + "service is down", after.get("message"));
        Thread.sleep(500);
        assertEquals(1, about(hook, a).size(), "once");
        assertNull(window(a));
        assertEquals(404, call("DELETE", "/api/v1/servers/" + a.serverId + "/maintenance", ADMIN, null).status());

        // Its alerts go out again.
        report(a, ca, "UP");
        assertEquals("UP", await("a's recovery", () -> about(hook, a), l -> l.size() == 2).get(1).get("to"));
    }

    @Test
    void aRestartEndsTheWindowAsSoonAsEverythingIsBackAndSendsNothing() throws Exception {
        String id = uid();
        String hook = webhook(id);
        Agent a = agent("reboot-" + id).beat();
        String ca = agentCheck(a, "svc-" + id);
        report(a, ca, "UP");

        // The server asks for itself, with its own credential.
        Resp opened = call("POST", "/api/v1/agent/maintenance", a.token, Map.of("minutes", 20, "reason", "patching"));
        assertEquals(200, opened.status(), opened.raw());
        assertEquals("agent", opened.map().get("startedByKind"));
        assertEquals(a.name, opened.map().get("startedBy"));
        assertEquals(true, opened.map().get("untilBack"));

        // The restart: the agent goes quiet, then comes back and its check runs again.
        a.stop();
        awaitOffline(a);
        assertTrue(window(a) != null, "still open while the server is away");
        a.beat();
        report(a, ca, "UP");

        await("the window to end", () -> window(a), w -> w == null);
        Thread.sleep(500);
        assertEquals(List.of(), about(hook, a), "a restart that came back sends nothing");

        Map<String, Object> started = audit("maintenance_started").stream()
                .filter(e -> a.name.equals(e.get("target"))).findFirst().orElseThrow();
        assertEquals("agent", started.get("actorKind"));
        assertEquals("20 min, ends early once everything is back", started.get("detail"));
        Map<String, Object> ended = audit("maintenance_ended").stream()
                .filter(e -> a.name.equals(e.get("target"))).findFirst().orElseThrow();
        assertEquals("hub", ended.get("actorKind"));
        assertEquals("everything is back", ended.get("detail"));
        String everything = call("GET", "/api/v1/audit?limit=500", ADMIN, null).raw();
        assertFalse(everything.contains("patching"), "the reason is free text from outside and stays out of the audit");
    }

    @Test
    void aFullTimeWindowStaysOpenAfterEverythingIsBack() throws Exception {
        String id = uid();
        String hook = webhook(id);
        Agent a = agent("full-" + id).beat();
        String ca = agentCheck(a, "svc-" + id);
        report(a, ca, "UP");
        assertEquals(200, open(a, 30, null, false).status());

        a.stop();
        awaitOffline(a);
        a.beat();
        report(a, ca, "UP");
        Thread.sleep(1500);
        assertTrue(window(a) != null, "runs its full time");

        assertEquals(204, call("DELETE", "/api/v1/servers/" + a.serverId + "/maintenance", ADMIN, null).status());
        Thread.sleep(500);
        assertEquals(List.of(), about(hook, a), "everything is as it was: nothing to say");
    }

    @Test
    void whatIsNotBackAtTheLimitAlertsOnce() throws Exception {
        String id = uid();
        String hook = webhook(id);
        Agent a = agent("stuck-" + id).beat();
        Agent b = agent("gone-" + id).beat();
        String ca = agentCheck(a, "svc-" + id);
        report(a, ca, "UP");
        Thread.sleep(700); // the watchdog sees both online
        assertEquals(200, open(a, 1, null, null).status());
        assertEquals(200, open(b, 1, null, null).status());

        report(a, ca, "DOWN");     // a's service does not come back
        b.stop();                  // b never comes back
        awaitOffline(b);
        assertEquals(List.of(), about(hook, a));
        assertEquals(List.of(), about(hook, b));

        maintenance.tickAt(System.currentTimeMillis() + 61_000);

        Map<String, Object> check = await("a's check alert", () -> about(hook, a), l -> !l.isEmpty()).get(0);
        assertEquals("check", check.get("kind"));
        assertEquals("UP", check.get("from"));
        assertEquals("DOWN", check.get("to"));
        assertTrue(((String) check.get("message")).startsWith(MaintenanceService.AFTER), String.valueOf(check.get("message")));
        Map<String, Object> agent = await("b's agent alert", () -> about(hook, b), l -> !l.isEmpty()).get(0);
        assertEquals("server", agent.get("kind"));
        assertEquals("UP", agent.get("from"));
        assertEquals("DOWN", agent.get("to"));
        assertTrue(((String) agent.get("message")).startsWith(MaintenanceService.AFTER + "no report from the agent"),
                String.valueOf(agent.get("message")));

        Thread.sleep(700);
        assertEquals(1, about(hook, a).size(), "once, and a's agent was online: nothing about it");
        assertEquals(1, about(hook, b).size(), "once, not again from the watchdog");
        assertNull(window(a));
        assertNull(window(b));
        assertTrue(audit("maintenance_ended").stream().anyMatch(e -> b.name.equals(e.get("target"))
                && "time limit".equals(e.get("detail"))));
    }

    @Test
    void openingIsCheckedAndNeverCutsAWindowShort() throws Exception {
        String id = uid();
        Agent a = agent("rules-" + id);
        String path = "/api/v1/servers/" + a.serverId + "/maintenance";

        String read = (String) call("POST", "/api/v1/tokens", ADMIN, Map.of("name", "viewer-" + id, "scope", "READ")).map().get("token");
        assertEquals(403, call("POST", path, read, Map.of("minutes", 10)).status(), "admins only");
        assertEquals(403, call("POST", "/api/v1/agent/maintenance", ADMIN, Map.of("minutes", 10)).status(),
                "the agent call takes an agent's credential");
        assertEquals(404, call("POST", "/api/v1/servers/nope/maintenance", ADMIN, Map.of("minutes", 10)).status());

        Resp none = call("POST", path, ADMIN, Map.of());
        assertEquals(400, none.status());
        assertTrue(none.raw().contains("minutes is required"), none.raw());
        assertEquals(400, call("POST", path, ADMIN, Map.of("minutes", 0)).status());
        Resp tooLong = call("POST", path, ADMIN, Map.of("minutes", 1441));
        assertEquals(400, tooLong.status());
        assertTrue(tooLong.raw().contains("between 1 and 1440"), tooLong.raw());
        assertEquals(400, call("POST", path, ADMIN, Map.of("minutes", 10, "reason", "two\nlines")).status());
        assertEquals(400, call("POST", path, ADMIN, Map.of("minutes", 10, "reason", "x".repeat(201))).status());
        Resp agentTooLong = call("POST", "/api/v1/agent/maintenance", a.token, Map.of("minutes", 61));
        assertEquals(400, agentTooLong.status());
        assertTrue(agentTooLong.raw().contains("between 1 and 60"), agentTooLong.raw());
        assertNull(window(a), "nothing was opened");

        // An admin's long window is not cut short by the agent opening a short one.
        Resp long2h = open(a, 120, "  datacenter move  ", null);
        assertEquals(200, long2h.status(), long2h.raw());
        assertEquals("datacenter move", long2h.map().get("reason"));
        long endsAt = ((Number) long2h.map().get("endsAt")).longValue();
        Resp shorter = call("POST", "/api/v1/agent/maintenance", a.token, Map.of("minutes", 10, "reason", "reboot"));
        assertEquals(200, shorter.status(), shorter.raw());
        assertEquals(endsAt, ((Number) shorter.map().get("endsAt")).longValue());
        assertEquals("reboot", shorter.map().get("reason"));
        assertEquals("token", shorter.map().get("startedByKind"), "who opened it is kept");

        Map<String, Object> opened = audit("api_call").stream()
                .filter(e -> "POST /api/v1/servers/{id}/maintenance".equals(e.get("action")) && a.name.equals(e.get("target"))
                        && "ok".equals(e.get("outcome")))
                .findFirst().orElseThrow();
        assertEquals("120 min, ends early once everything is back", opened.get("detail"));

        assertEquals(204, call("DELETE", "/api/v1/agent/maintenance", a.token, null).status());
        assertEquals(404, call("DELETE", "/api/v1/agent/maintenance", a.token, null).status());
        assertEquals(404, call("DELETE", path, ADMIN, null).status());
        assertTrue(audit("maintenance_ended").stream().anyMatch(e -> a.name.equals(e.get("target"))
                && "agent".equals(e.get("actorKind"))));

        // Deleting the server takes its window with it.
        assertEquals(200, open(a, 10, null, null).status());
        assertEquals(204, call("DELETE", "/api/v1/servers/" + a.serverId, ADMIN, null).status());
        assertEquals(404, call("DELETE", path, ADMIN, null).status());
    }
}

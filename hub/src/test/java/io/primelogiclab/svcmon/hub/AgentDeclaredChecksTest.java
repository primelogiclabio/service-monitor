package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Checks declared by agents over real HTTP: the file wins, the hub keeps the switch, bad entries do no harm. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentDeclaredChecksTest {

    private static final String ADMIN = "test-admin-token-0123456789abcdef0123456789abcdef";
    private static final AtomicInteger SERVERS = new AtomicInteger();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("svcmon-declared");
        registry.add("svcmon.data-dir", dir::toString);
        registry.add("svcmon.bootstrap-admin-token", () -> ADMIN);
        // svcmon.agent-declared-checks is not set: declaring is on by default
        registry.add("svcmon.agent-declared-checks-limit", () -> "4");
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    record Resp(int status, String body) {
    }

    private Resp call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Resp r) {
        return json.readValue(r.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Resp r) {
        return json.readValue(r.body(), List.class);
    }

    /** An enrolled server: its id and its agent token. */
    record Agent(String serverId, String token) {
    }

    private Agent enroll() throws Exception {
        Map<String, Object> enroll = map(call("POST", "/api/v1/enrollment-tokens", ADMIN, "{\"ttlMinutes\":5}"));
        Resp reg = call("POST", "/api/v1/agent/register", (String) enroll.get("token"),
                "{\"name\":\"declare-" + SERVERS.incrementAndGet() + "\"}");
        assertEquals(201, reg.status(), reg.body());
        Map<String, Object> m = map(reg);
        return new Agent((String) m.get("serverId"), (String) m.get("token"));
    }

    private List<Map<String, Object>> declare(Agent a, String checksJson) throws Exception {
        Resp r = call("PUT", "/api/v1/agent/checks", a.token(), "{\"checks\":" + checksJson + "}");
        assertEquals(200, r.status(), r.body());
        return list(new Resp(200, json.writeValueAsString(map(r).get("results"))));
    }

    private Map<String, Object> checkOf(String id) throws Exception {
        return map(call("GET", "/api/v1/checks/" + id, ADMIN, null));
    }

    private static List<String> results(List<Map<String, Object>> outcomes) {
        return outcomes.stream().map(o -> (String) o.get("result")).toList();
    }

    private static String editBody(String name, boolean enabled, Agent a) {
        return "{\"name\":\"" + name + "\",\"type\":\"CONTAINER\",\"target\":\"web\",\"serverId\":\"" + a.serverId()
                + "\",\"runFrom\":\"AGENT\",\"intervalSeconds\":60,\"enabled\":" + enabled + "}";
    }

    @Test
    void declaringCreatesUpdatesAndSwitchesChecksOff() throws Exception {
        Agent a = enroll();
        String two = "[{\"type\":\"CONTAINER\",\"target\":\"web\",\"intervalSeconds\":20,\"failuresBeforeDown\":2},"
                + "{\"type\":\"SERVICE\",\"target\":\"nginx\",\"name\":\"Nginx\"}]";

        List<Map<String, Object>> first = declare(a, two);
        assertEquals(List.of("created", "created"), results(first));
        String webId = (String) first.get(0).get("checkId");
        String nginxId = (String) first.get(1).get("checkId");
        Map<String, Object> web = checkOf(webId);
        assertEquals("AGENT", web.get("source"));
        assertEquals("AGENT", web.get("runFrom"));
        assertEquals(a.serverId(), web.get("serverId"));
        assertEquals("container web", web.get("name"), "a name is made up when the file gives none");
        assertEquals(20, web.get("intervalSeconds"));
        assertEquals(2, web.get("failuresBeforeDown"));

        // The agent gets declared checks in its ordinary list.
        Map<String, Object> cfg = map(call("GET", "/api/v1/agent/config", a.token(), null));
        assertEquals(2, ((List<?>) cfg.get("checks")).size());

        assertEquals(List.of("unchanged", "unchanged"), results(declare(a, two)), "the same file changes nothing");

        List<Map<String, Object>> edited = declare(a, two.replace("\"intervalSeconds\":20", "\"intervalSeconds\":40"));
        assertEquals(List.of("updated", "unchanged"), results(edited));
        assertEquals(webId, edited.get(0).get("checkId"), "an edit keeps the check, so its history stays");
        assertEquals(40, checkOf(webId).get("intervalSeconds"));

        // Left out of the file: switched off and kept, not deleted.
        Resp onlyWeb = call("PUT", "/api/v1/agent/checks", a.token(),
                "{\"checks\":[{\"type\":\"CONTAINER\",\"target\":\"web\",\"intervalSeconds\":40,\"failuresBeforeDown\":2}]}");
        assertEquals(200, onlyWeb.status());
        assertEquals(List.of("Nginx"), map(onlyWeb).get("removed"));
        Map<String, Object> nginx = checkOf(nginxId);
        assertEquals(Boolean.FALSE, nginx.get("enabled"));
        assertEquals(Boolean.TRUE, nginx.get("removedFromFile"));
        assertEquals(1, ((List<?>) map(call("GET", "/api/v1/agent/config", a.token(), null)).get("checks")).size());

        // Back in the file: the same check comes back on, with its history.
        List<Map<String, Object>> back = declare(a, two.replace("\"intervalSeconds\":20", "\"intervalSeconds\":40"));
        assertEquals(List.of("unchanged", "updated"), results(back));
        assertEquals(nginxId, back.get(1).get("checkId"));
        nginx = checkOf(nginxId);
        assertEquals(Boolean.TRUE, nginx.get("enabled"));
        assertEquals(Boolean.FALSE, nginx.get("removedFromFile"));
    }

    @Test
    void theFileWinsAndTheHubKeepsOnlyTheSwitch() throws Exception {
        Agent a = enroll();
        Resp made = call("POST", "/api/v1/checks", ADMIN, "{\"name\":\"Made in the hub\",\"type\":\"CONTAINER\","
                + "\"target\":\"web\",\"serverId\":\"" + a.serverId() + "\"}");
        assertEquals(201, made.status(), made.body());
        String id = (String) map(made).get("id");
        assertEquals("HUB", map(made).get("source"));

        // The declaration takes the hub's check over: same check and history, the file's settings.
        List<Map<String, Object>> taken = declare(a,
                "[{\"type\":\"CONTAINER\",\"target\":\"web\",\"name\":\"Web proxy\",\"intervalSeconds\":60}]");
        assertEquals(List.of("taken over"), results(taken));
        assertEquals(id, taken.get(0).get("checkId"));
        Map<String, Object> def = checkOf(id);
        assertEquals("AGENT", def.get("source"));
        assertEquals("Web proxy", def.get("name"));
        assertEquals(60, def.get("intervalSeconds"));

        // An edit in the hub is refused, since the next sync would undo it; the switch (muting) is allowed.
        Resp edit = call("PUT", "/api/v1/checks/" + id, ADMIN, editBody("Renamed in the hub", true, a));
        assertEquals(409, edit.status(), edit.body());
        assertTrue(map(edit).get("message").toString().contains("agent's checks file"));
        assertEquals(200, call("PUT", "/api/v1/checks/" + id, ADMIN, editBody("Web proxy", false, a)).status());
        assertEquals(List.of("unchanged"), results(declare(a,
                "[{\"type\":\"CONTAINER\",\"target\":\"web\",\"name\":\"Web proxy\",\"intervalSeconds\":60}]")),
                "a sync leaves a muted check muted");
        assertEquals(Boolean.FALSE, checkOf(id).get("enabled"));

        // Deleting is refused while the file still declares it; once removed it can go.
        assertEquals(409, call("DELETE", "/api/v1/checks/" + id, ADMIN, null).status());
        Resp none = call("PUT", "/api/v1/agent/checks", a.token(), "{\"checks\":[]}");
        assertEquals(List.of("Web proxy"), map(none).get("removed"));
        assertEquals(409, call("PUT", "/api/v1/checks/" + id, ADMIN, editBody("Web proxy", true, a)).status(),
                "a check removed from the file cannot be switched on by hand");
        assertEquals(204, call("DELETE", "/api/v1/checks/" + id, ADMIN, null).status());
    }

    @Test
    void badEntriesAreRefusedAndDoNoHarm() throws Exception {
        Agent a = enroll();
        List<Map<String, Object>> out = declare(a, "[{\"type\":\"CONTAINER\",\"target\":\"web\"},"
                + "{\"type\":\"CONTAINER\",\"target\":\"../etc\"},"
                + "{\"type\":\"CONTAINER\",\"target\":\"web\"},"
                + "{\"id\":\"bad id!\",\"type\":\"CPU\"}]");
        assertEquals(List.of("created", "rejected", "rejected", "rejected"), results(out));
        assertTrue(out.get(1).get("error").toString().contains("container name"), out.get(1).toString());
        assertTrue(out.get(2).get("error").toString().contains("twice"), out.get(2).toString());
        assertTrue(out.get(3).get("error").toString().contains("id must be"), out.get(3).toString());
        assertNull(out.get(1).get("checkId"));

        // A working check that is mistyped in the file stays as it was; it is not switched off.
        String id = (String) declare(a, "[{\"id\":\"w\",\"type\":\"CONTAINER\",\"target\":\"db\"}]").get(0).get("checkId");
        List<Map<String, Object>> typo = declare(a, "[{\"id\":\"w\",\"type\":\"CONTAINER\",\"target\":\"d b\"},"
                + "{\"type\":\"CPU\",\"config\":{\"warn\":500}}]");
        assertEquals(List.of("rejected", "rejected"), results(typo));
        assertTrue(typo.get(1).get("error").toString().contains("warn"), typo.get(1).toString());
        Map<String, Object> kept = checkOf(id);
        assertEquals(Boolean.TRUE, kept.get("enabled"));
        assertEquals(Boolean.FALSE, kept.get("removedFromFile"));
        assertEquals("db", kept.get("target"));
    }

    @Test
    void anAgentOnlyDeclaresForItselfAndOnlyWhatItRuns() throws Exception {
        Agent a = enroll();
        Agent b = enroll();
        // A check that the hub could run is declared as an agent check: a server cannot make the hub call addresses.
        List<Map<String, Object>> http = declare(a, "[{\"type\":\"HTTP\",\"target\":\"http://127.0.0.1:1/x\","
                + "\"runFrom\":\"HUB\",\"serverId\":\"" + b.serverId() + "\"}]");
        assertEquals(List.of("created"), results(http));
        Map<String, Object> def = checkOf((String) http.get(0).get("checkId"));
        assertEquals("AGENT", def.get("runFrom"));
        assertEquals(a.serverId(), def.get("serverId"));

        // Another server's declaration, even an empty one, leaves it alone.
        assertEquals(200, call("PUT", "/api/v1/agent/checks", b.token(), "{\"checks\":[]}").status());
        assertEquals(Boolean.TRUE, checkOf((String) http.get(0).get("checkId")).get("enabled"));

        // Too many entries are refused as a whole (the limit is 4 here).
        Resp many = call("PUT", "/api/v1/agent/checks", a.token(), "{\"checks\":[{},{},{},{},{}]}");
        assertEquals(400, many.status());
        assertTrue(many.body().contains("At most 4"));
        assertEquals(400, call("PUT", "/api/v1/agent/checks", a.token(), "{}").status(), "a missing list is not an empty one");

        // Only an agent may declare; an admin or no token is turned away.
        assertEquals(403, call("PUT", "/api/v1/agent/checks", ADMIN, "{\"checks\":[]}").status());
        assertEquals(401, call("PUT", "/api/v1/agent/checks", null, "{\"checks\":[]}").status());
    }

    @Test
    void theStatusShowsWhereACheckComesFrom() throws Exception {
        Agent a = enroll();
        declare(a, "[{\"type\":\"CPU\"}]");
        Map<String, Object> status = map(call("GET", "/api/v1/status", ADMIN, null));
        boolean found = false;
        for (Object cluster : (List<?>) status.get("clusters")) {
            for (Object server : (List<?>) ((Map<?, ?>) cluster).get("servers")) {
                for (Object check : (List<?>) ((Map<?, ?>) server).get("checks")) {
                    Map<?, ?> c = (Map<?, ?>) check;
                    if ("CPU".equals(c.get("type")) && "AGENT".equals(c.get("source"))) {
                        found = true;
                        assertFalse((Boolean) c.get("removedFromFile"));
                    }
                }
            }
        }
        assertTrue(found, "the dashboard's status should carry the source of a check");
    }
}

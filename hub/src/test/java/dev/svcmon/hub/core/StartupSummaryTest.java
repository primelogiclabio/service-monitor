package dev.svcmon.hub.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.svcmon.hub.model.AlertChannel;
import dev.svcmon.hub.model.AlertType;
import dev.svcmon.hub.model.CheckDef;
import dev.svcmon.hub.model.CheckType;
import dev.svcmon.hub.model.RunFrom;
import dev.svcmon.hub.model.ServerInfo;
import dev.svcmon.hub.model.Status;
import org.junit.jupiter.api.Test;

/** The start-up summary's layout: what it shows, what it caps, and what it must never print. */
class StartupSummaryTest {

    private static final List<String> URLS = List.of("http://localhost:8080", "http://hub01:8080");
    private static final Path DB = Path.of("/var/lib/svcmon-hub/svcmon.db");

    private static StartupSummary.Facts facts(List<CheckDef> checks, List<ServerInfo> servers,
                                              List<AlertChannel> channels) {
        return new StartupSummary.Facts("0.1.0", URLS, DB, null, List.of(), checks, servers, channels);
    }

    private static CheckDef check(String name, CheckType type, RunFrom from, String serverId, boolean enabled) {
        return new CheckDef("id-" + name, name, type, "https://user:hunter2@internal.example/hook?token=abc123",
                Map.of("headers", Map.of("Authorization", "Bearer sekrit-header")), 60, 5000, 2, from, serverId,
                enabled, 0);
    }

    private static ServerInfo server(String id, String name, String cluster) {
        return new ServerInfo(id, name, cluster, "linux", "0.1.0", null, 0);
    }

    private static AlertChannel channel(String name, AlertType type, boolean enabled) {
        return new AlertChannel("c-" + name, name, type, Map.of("url", "https://hooks.example/T000/B000/sekrit-url"),
                Set.of(Status.DOWN), enabled, 0, null, null, null);
    }

    @Test
    void emptyHubShowsWhereItListensAndThatNothingIsWatched() {
        String text = StartupSummary.render(facts(List.of(), List.of(), List.of()));

        assertTrue(text.startsWith("Service Monitor hub 0.1.0 is ready"), text);
        assertTrue(text.contains("http://localhost:8080, http://hub01:8080"), text);
        assertTrue(text.contains(DB.toString()), text);
        assertTrue(text.contains("not served"), text);
        assertTrue(text.contains("CORS:      off"), text);
        assertTrue(text.contains("none enabled"), text);
        assertTrue(text.contains("nothing yet"), text);
    }

    @Test
    void populatedHubCountsChecksByTypeAndWhoRunsThem() {
        List<CheckDef> checks = List.of(
                check("Website", CheckType.HTTP, RunFrom.HUB, null, true),
                check("db-port", CheckType.TCP, RunFrom.HUB, null, true),
                check("IIS", CheckType.SERVICE, RunFrom.AGENT, "s1", true),
                check("cpu", CheckType.CPU, RunFrom.AGENT, "s1", true),
                check("old", CheckType.PING, RunFrom.HUB, null, false));
        List<ServerInfo> servers = List.of(server("s1", "web01", "prod"), server("s2", "idle01", null));
        String text = StartupSummary.render(facts(checks, servers,
                List.of(channel("ops-slack", AlertType.SLACK, true), channel("spare", AlertType.EMAIL, false))));

        assertTrue(text.contains("4 check(s) (HTTP 1, TCP 1, SERVICE 1, CPU 1)"), text);
        assertTrue(text.contains("2 run by the hub, 2 by agents on 2 enrolled server(s); 1 disabled"), text);
        assertTrue(text.contains("HTTP  Website"), text);
        assertFalse(text.contains("old"), "a disabled check is counted but not listed: " + text);
        assertTrue(text.contains("web01 [prod]: 2 check(s): IIS (SERVICE), cpu (CPU)"), text);
        assertTrue(text.contains("idle01: no checks yet"), text);
        assertTrue(text.contains("1 enabled of 2: ops-slack (SLACK)"), text);
        assertFalse(text.contains("spare"), "a disabled channel is not listed: " + text);
    }

    @Test
    void neverPrintsTargetsHeadersOrAlertSettings() {
        String text = StartupSummary.render(facts(
                List.of(check("Website", CheckType.HTTP, RunFrom.HUB, null, true)),
                List.of(), List.of(channel("ops-slack", AlertType.SLACK, true))));

        for (String secret : List.of("hunter2", "abc123", "internal.example", "sekrit-header", "sekrit-url", "hooks.example")) {
            assertFalse(text.contains(secret), secret + " leaked into: " + text);
        }
    }

    @Test
    void namesFromAgentsCannotForgeLogLines() {
        String hostile = "web01\n2026-01-01 INFO  Admin token created\r\n[ok] ";
        String text = StartupSummary.render(facts(
                List.of(check("x\ny", CheckType.CPU, RunFrom.AGENT, "s1", true)),
                List.of(server("s1", hostile, "prod\nwest")), List.of()));

        for (String l : text.split("\n")) {
            assertFalse(l.startsWith("2026-01-01"), "forged line: " + l);
            assertFalse(l.startsWith("[ok]"), "forged line: " + l);
        }
        assertTrue(text.contains("web01 2026-01-01 INFO  Admin token created [ok] [prod west]"), text);
        assertEquals("a".repeat(60) + "...", StartupSummary.clean("a".repeat(100)));
    }

    @Test
    void longListsAreCapped() {
        List<CheckDef> checks = new ArrayList<>();
        List<ServerInfo> servers = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            checks.add(check("hub-" + i, CheckType.HTTP, RunFrom.HUB, null, true));
            servers.add(server("s" + i, "srv-" + i, null));
        }
        String text = StartupSummary.render(facts(checks, servers, List.of()));

        assertTrue(text.contains("hub-19"), text);
        assertFalse(text.contains("hub-20"), text);
        assertFalse(text.contains("srv-20"), text);
        assertEquals(2, text.split("\\.\\.\\. and 5 more", -1).length - 1, text);
    }

    @Test
    void servedDashboardAndCorsOriginsAreShown() {
        String text = StartupSummary.render(new StartupSummary.Facts("0.1.0", URLS, DB, Path.of("/srv/ui"),
                List.of("https://monitor.example.com"), List.of(), List.of(), List.of()));

        assertTrue(text.contains("served from " + Path.of("/srv/ui")), text);
        assertTrue(text.contains("browser access allowed from https://monitor.example.com"), text);
    }
}

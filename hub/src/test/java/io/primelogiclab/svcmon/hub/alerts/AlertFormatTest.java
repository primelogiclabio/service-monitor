package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.model.Status;
import org.junit.jupiter.api.Test;

class AlertFormatTest {

    private static final long TS = 1_761_312_067_000L; // 2025-10-24T13:21:07Z

    private static AlertEvent check(String name, Status from, Status to, String message) {
        return new AlertEvent(AlertEvent.Kind.CHECK, "c1", name, "CPU", null, "s1", "web01", from, to, message, TS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    @Test
    void titlesAndHeadlinesSayWhatChanged() {
        AlertEvent e = check("CPU", Status.UP, Status.DOWN, "CPU 96.2% used");
        assertEquals("[DOWN] web01 / CPU", AlertFormat.title(e));
        assertEquals("web01 / CPU is now DOWN (was UP)", AlertFormat.headline(e));

        AlertEvent hubCheck = new AlertEvent(AlertEvent.Kind.CHECK, "c2", "Public site", "HTTP", "https://x", null, null,
                Status.DOWN, Status.UP, "HTTP 200", TS);
        assertEquals("[UP] Public site", AlertFormat.title(hubCheck));
    }

    @Test
    void serverEventsTalkAboutTheAgent() {
        AlertEvent down = new AlertEvent(AlertEvent.Kind.SERVER, "s1", "agent", "AGENT", null, "s1", "web01",
                Status.UP, Status.DOWN, "No report for 95s", TS);
        AlertEvent up = new AlertEvent(AlertEvent.Kind.SERVER, "s1", "agent", "AGENT", null, "s1", "web01",
                Status.DOWN, Status.UP, "reporting again", TS);
        assertEquals("[DOWN] web01 / agent", AlertFormat.title(down));
        assertEquals("web01 stopped reporting", AlertFormat.headline(down));
        assertEquals("web01 is reporting again", AlertFormat.headline(up));
    }

    @Test
    void controlCharactersCannotInjectMailHeaders() {
        assertEquals("a Bcc: evil@example.com", AlertFormat.oneLine("a\r\nBcc: evil@example.com"));
        AlertEvent e = check("CPU\r\nBcc: evil@example.com", Status.UP, Status.DOWN, "x");
        String title = AlertFormat.title(e);
        assertFalse(title.contains("\r") || title.contains("\n"), title);
    }

    @Test
    void slackTextCannotPingOrLinkAnywhere() {
        AlertEvent e = check("<!channel> & <http://evil|click>", Status.UP, Status.DOWN, "<!here> hi");
        Map<String, Object> p = AlertFormat.slackPayload(e);
        String text = (String) p.get("text");
        String attachment = (String) map(list(p.get("attachments")).get(0)).get("text");
        for (String s : List.of(text, attachment)) {
            assertFalse(s.contains("<!"), s);
            assertFalse(s.contains("<http"), s);
        }
        assertTrue(text.contains("&lt;!channel&gt; &amp; &lt;http://evil|click&gt;"), text);
        assertEquals("#d03b3b", map(list(p.get("attachments")).get(0)).get("color"));
    }

    @Test
    void discordMessagesNeverMentionAnyone() {
        AlertEvent e = check("@everyone", Status.UP, Status.WARN, "@here " + "x".repeat(6000));
        Map<String, Object> p = AlertFormat.discordPayload(e);
        assertEquals(List.of(), map(p.get("allowed_mentions")).get("parse"));
        Map<String, Object> embed = map(list(p.get("embeds")).get(0));
        assertEquals(Integer.parseInt("FAB219", 16), embed.get("color"));
        assertTrue(((String) embed.get("description")).length() <= 4000);
        assertTrue(((String) embed.get("title")).length() <= 256);
        assertEquals("2025-10-24T13:21:07Z", embed.get("timestamp"));
    }

    @Test
    void colorsMatchTheDashboardPalette() {
        assertEquals(Integer.parseInt("D03B3B", 16), AlertFormat.color(Status.DOWN));
        assertEquals(Integer.parseInt("FAB219", 16), AlertFormat.color(Status.WARN));
        assertEquals(Integer.parseInt("0CA30C", 16), AlertFormat.color(Status.UP));
        assertEquals(Integer.parseInt("898781", 16), AlertFormat.color(Status.UNKNOWN));
    }

    @Test
    void webhookPayloadDescribesTheChange() {
        AlertEvent e = new AlertEvent(AlertEvent.Kind.CHECK, "c1", "CPU", "CPU", "x", "s1", "web01",
                Status.UP, Status.DOWN, "CPU 96% used", TS);
        Map<String, Object> p = AlertFormat.webhookPayload(e, "hub-1");
        assertEquals("status_changed", p.get("event"));
        assertEquals("check", p.get("kind"));
        assertEquals("hub-1", p.get("hub"));
        assertEquals(TS, p.get("time"));
        assertEquals("UP", p.get("from"));
        assertEquals("DOWN", p.get("to"));
        Map<String, Object> subject = map(p.get("subject"));
        assertEquals("CPU", subject.get("name"));
        assertEquals("web01", map(subject.get("server")).get("name"));

        Map<String, Object> hubLevel = AlertFormat.webhookPayload(
                new AlertEvent(AlertEvent.Kind.CHECK, "c2", "Site", "HTTP", null, null, null, Status.UP, Status.DOWN, "m", TS), "h");
        assertFalse(map(hubLevel.get("subject")).containsKey("server"));
    }

    @Test
    void plainBodyIsReadableAndOmitsAbsentParts() {
        String body = AlertFormat.plainBody(check("CPU", Status.UP, Status.DOWN, "CPU 96.2% used"), null);
        assertTrue(body.contains("web01 / CPU is now DOWN (was UP)"));
        assertTrue(body.contains("CPU 96.2% used"));
        assertTrue(body.contains("Status: UP -> DOWN"));
        assertTrue(body.contains("Time: 2025-10-24 13:21:07 UTC"));
        assertFalse(body.contains("Hub:"));
        assertTrue(AlertFormat.plainBody(check("CPU", Status.UP, Status.DOWN, "m"), "hub-1").contains("Hub: hub-1"));
    }

    @Test
    void testAlertsAreLabelled() {
        AlertEvent t = AlertEvent.test(TS);
        assertEquals("[TEST] svcmon alert channel", AlertFormat.title(t));
        assertEquals("test", AlertFormat.webhookPayload(t, "h").get("kind"));
    }

    @Test
    void signatureMatchesAStandardHmacVector() {
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                WebhookSender.sign("key", "The quick brown fox jumps over the lazy dog"));
    }

    @Test
    void errorTextNeverContainsTheSecretUrl() {
        assertEquals("failed for <url> twice", HttpPoster.scrub("failed for https://hooks.example/abc twice", "https://hooks.example/abc") .replace("<url> twice", "<url> twice"));
        assertFalse(HttpPoster.scrub("bad https://hooks.example/abc", "https://hooks.example/abc").contains("abc"));
    }
}

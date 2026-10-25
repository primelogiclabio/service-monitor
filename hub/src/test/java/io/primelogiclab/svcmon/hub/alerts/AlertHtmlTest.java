package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.model.Status;
import org.junit.jupiter.api.Test;

class AlertHtmlTest {

    private static final long TS = 1_761_312_067_000L; // 2025-10-24T13:21:07Z
    private static final String HUB = "1b45cdc6-3a12-4077-8eda-250c02677cdb";
    private static final Set<String> TAGS = Set.of("!DOCTYPE", "html", "head", "meta", "title", "style", "body", "div",
            "table", "tr", "td", "span", "a", "br");

    private static AlertEvent check(Status from, Status to, String message) {
        return new AlertEvent(AlertEvent.Kind.CHECK, "c1", "CPU", "CPU", null, "s1", "web01", from, to, message, TS);
    }

    private static String page(AlertEvent e) {
        return AlertHtml.email(e, HUB, "Email: andrew", null);
    }

    @Test
    void aDownAlertShowsTheStatusTheHeadlineTheMessageAndTheDetails() {
        String html = page(check(Status.UP, Status.DOWN, "CPU 96.2% used"));
        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        assertTrue(html.contains(">DOWN</td>"), "the status word is written out, not only coloured");
        assertTrue(html.contains(">web01 / CPU is now DOWN (was UP)<"), html);
        assertTrue(html.contains("CPU 96.2% used"), html);
        for (String label : new String[] {"Server", "Check", "Change", "Time"}) {
            assertTrue(html.contains(">" + label + "<"), label);
        }
        assertTrue(html.contains(">web01<"), html);
        assertTrue(html.contains(">CPU<") && !html.contains("CPU (CPU)"), "a check named like its type is not shown twice");
        assertTrue(page(new AlertEvent(AlertEvent.Kind.CHECK, "c2", "Keycloak realm", "HTTP", null, "s1", "web01",
                Status.UP, Status.DOWN, "x", TS)).contains(">Keycloak realm (HTTP)<"));
        assertTrue(html.contains("UP &rarr; DOWN"), html);
        assertTrue(html.contains("2025-10-24 13:21:07 UTC"), html);
        assertTrue(html.contains("Email: andrew"), "the footer names the channel");
        assertTrue(html.contains("1b45cdc6"), "and the hub");
        assertTrue(html.contains("prefers-color-scheme: dark"), "a dark-mode block is there");
    }

    @Test
    void recoveriesTestsAndAgentEventsHaveTheirOwnWording() {
        assertTrue(page(check(Status.DOWN, Status.UP, "HTTP 200")).contains("UP &middot; recovered"));
        assertFalse(page(check(Status.UNKNOWN, Status.UP, "first result")).contains("recovered"),
                "a first result is not a recovery");
        assertTrue(page(check(Status.UP, Status.WARN, "85% used")).contains(">WARN</td>"));

        String test = page(AlertEvent.test(TS));
        assertTrue(test.contains(">TEST</td>"), test);
        assertTrue(test.contains("If you can read it, this channel works."), test);
        assertFalse(test.contains(">Server<"), "a test has no server");

        AlertEvent silent = new AlertEvent(AlertEvent.Kind.SERVER, "s1", "agent", "AGENT", null, "s1", "web01",
                Status.UP, Status.DOWN, "No report for 95s", TS);
        String down = page(silent);
        assertTrue(down.contains(">web01 stopped reporting<"), down);
        assertTrue(down.contains("No report for 95s"), down);
        assertFalse(down.contains(">Check<"), "an agent is not a check");
    }

    @Test
    void everythingAnAgentCanInfluenceIsEscaped() {
        AlertEvent hostile = new AlertEvent(AlertEvent.Kind.CHECK, "c1", "<script>alert(1)</script>", "HTTP",
                "https://x.example/?a=1&b=<b>", "s1", "\"><img src=x onerror=alert(1)>", Status.UP, Status.DOWN,
                "</div><script>steal()</script> & \"quoted\" 'single'", TS);
        String html = page(hostile);

        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertTrue(html.contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;"), html);
        assertTrue(html.contains("?a=1&amp;b=&lt;b&gt;"), html);
        assertTrue(html.contains("&amp; &quot;quoted&quot; &#39;single&#39;"), html);

        // Whatever the input, only the tags this template writes may appear, and none carries an event handler.
        Matcher tags = Pattern.compile("<\\s*/?\\s*([!a-zA-Z][a-zA-Z0-9]*)").matcher(html);
        while (tags.find()) {
            assertTrue(TAGS.contains(tags.group(1)) || TAGS.contains(tags.group(1).toLowerCase()),
                    "unexpected tag " + tags.group(1));
        }
        assertFalse(Pattern.compile("<[^>]*\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find(), html);
    }

    @Test
    void textDirectionTricksAndControlCharactersAreRemoved() {
        AlertEvent e = new AlertEvent(AlertEvent.Kind.CHECK, "c1", "evil‮exe.txt\r\nBcc: x@example.test", "CPU", null,
                "s1", "web01", Status.UP, Status.DOWN, "line one\r\nline\u0007 two⁦", TS);
        String html = page(e);
        assertFalse(html.contains("‮") || html.contains("⁦") || html.contains("\u0007"), "direction overrides and bells");
        assertFalse(html.contains("\r"), "no carriage returns");
        assertTrue(html.contains("line one<br>line two"), html);
    }

    @Test
    void longMessagesAreCutAndLineBreaksKept() {
        String html = page(check(Status.UP, Status.DOWN, "x".repeat(5000)));
        assertFalse(html.contains("x".repeat(2001)), "cut at 2000");
        assertTrue(html.contains("x".repeat(1999) + "…"), "and marked as cut");
        assertTrue(page(check(Status.UP, Status.DOWN, "a\nb")).contains("a<br>b"));
    }

    @Test
    void withoutAPublicAddressThereIsNoLinkAndNoRemoteContent() {
        String html = page(check(Status.UP, Status.DOWN, "x"));
        for (String forbidden : new String[] {"href=", "src=", "url(", "@import", "<link", "<img", "<script", "<iframe", "<form", "http"}) {
            assertFalse(html.contains(forbidden), forbidden + " in " + html);
        }
    }

    @Test
    void theDashboardButtonIsBuiltOnlyFromTheConfiguredAddressAndTheServerId() {
        String base = "https://monitor.example.test";
        assertEquals("https://monitor.example.test/#/server/s1", AlertHtml.dashboardLink(base, check(Status.UP, Status.DOWN, "x")));
        assertEquals("https://monitor.example.test/#/server/a%20b%2Fc%22%3E",
                AlertHtml.dashboardLink(base, new AlertEvent(AlertEvent.Kind.CHECK, "c1", "n", "CPU", null, "a b/c\">", "w",
                        Status.UP, Status.DOWN, "x", TS)));
        assertEquals("https://monitor.example.test/", AlertHtml.dashboardLink(base,
                new AlertEvent(AlertEvent.Kind.CHECK, "c2", "Site", "HTTP", "https://x.example", null, null,
                        Status.UP, Status.DOWN, "x", TS)), "a check the hub runs has no server page");
        assertEquals("https://monitor.example.test/", AlertHtml.dashboardLink(base, AlertEvent.test(TS)));
        assertNull(AlertHtml.dashboardLink(null, check(Status.UP, Status.DOWN, "x")));

        String html = AlertHtml.email(check(Status.UP, Status.DOWN, "x"), HUB, "mail", base);
        assertEquals(1, html.split("href=\"", -1).length - 1, "exactly one link");
        assertTrue(html.contains("href=\"https://monitor.example.test/#/server/s1\""), html);
        assertTrue(html.contains(">Open in dashboard<"), html);
        assertFalse(html.contains("src="), "still no remote content");
    }

    @Test
    void theInboxPreviewSummarisesTheEvent() {
        String html = page(check(Status.UP, Status.DOWN, "CPU 96.2% used"));
        int hidden = html.indexOf("display:none");
        assertTrue(hidden > 0 && hidden < html.indexOf(">web01 / CPU is now DOWN"), "the hidden preview comes first");
        assertTrue(html.substring(hidden, html.indexOf("</div>", hidden)).contains("[DOWN] web01 / CPU"), html);
    }

    @Test
    void theStatusBandKeepsReadableTextOnEveryColour() {
        assertEquals("#ffffff", AlertHtml.textColorFor(AlertFormat.color(Status.DOWN)));
        for (Status s : new Status[] {Status.WARN, Status.UP, Status.UNKNOWN}) {
            assertEquals("#111827", AlertHtml.textColorFor(AlertFormat.color(s)), s.name());
        }
    }

    /** Writes sample emails to look at in a browser: mvn test -Dtest=AlertHtmlTest -Dsvcmon.emailPreviewDir=<folder>. */
    @Test
    void writesPreviewPagesWhenAskedTo() throws IOException {
        String dir = System.getProperty("svcmon.emailPreviewDir");
        assumeTrue(dir != null && !dir.isBlank(), "no preview folder given");
        Path out = Path.of(dir);
        Files.createDirectories(out);
        String base = "https://monitor.example.test";
        write(out, "down", AlertHtml.email(check(Status.UP, Status.DOWN, "memory 96.1% used (0.1 GiB free of 3.8 GiB)"), HUB, "Email: andrew", base));
        write(out, "warn", AlertHtml.email(check(Status.UP, Status.WARN, "memory 86.4% used (0.5 GiB free of 3.8 GiB)"), HUB, "Email: andrew", base));
        write(out, "recovered", AlertHtml.email(check(Status.DOWN, Status.UP, "memory 54.0% used (1.7 GiB free of 3.8 GiB)"), HUB, "Email: andrew", base));
        write(out, "http-down", AlertHtml.email(new AlertEvent(AlertEvent.Kind.CHECK, "c2", "Keycloak realm", "HTTP",
                "https://auth.example.test/realms/main/.well-known/openid-configuration", "s1", "primelogiclab",
                Status.UP, Status.DOWN, "HTTP 502 not in expected range\nsecond line of detail", TS), HUB, "Email: andrew", base));
        write(out, "agent-silent", AlertHtml.email(new AlertEvent(AlertEvent.Kind.SERVER, "s1", "agent", "AGENT", null, "s1",
                "web01", Status.UP, Status.DOWN, "No report for 95s", TS), HUB, "Email: andrew", base));
        write(out, "test", AlertHtml.email(AlertEvent.test(TS), HUB, "Email: andrew", base));
        write(out, "hostile", AlertHtml.email(new AlertEvent(AlertEvent.Kind.CHECK, "c3", "<script>alert(1)</script>", "HTTP",
                "https://x.example/?a=1&b=<b>", "s1", "\"><img src=x onerror=alert(1)>", Status.UP, Status.DOWN,
                "</div><script>steal()</script> & \"quoted\"", TS), HUB, "Email: andrew", base));
    }

    private static void write(Path dir, String name, String html) throws IOException {
        Files.writeString(dir.resolve(name + ".html"), html, StandardCharsets.UTF_8);
    }
}

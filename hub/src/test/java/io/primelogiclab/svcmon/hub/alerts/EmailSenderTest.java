package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.AlertChannel;
import io.primelogiclab.svcmon.hub.model.AlertType;
import io.primelogiclab.svcmon.hub.model.Status;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the email sender puts on the wire, checked by parsing the mail a fake SMTP server received. */
class EmailSenderTest {

    private static final long TS = 1_761_312_067_000L;
    private static final String HUB = "1b45cdc6-3a12-4077-8eda-250c02677cdb";

    private FakeSmtpServer smtp;

    @BeforeEach
    void startServer() throws Exception {
        smtp = new FakeSmtpServer();
    }

    @AfterEach
    void stopServer() throws Exception {
        smtp.close();
    }

    private static EmailSender sender(String publicUrl) {
        return new EmailSender(new HubProperties(Path.of("."), 30, 30, 90, null, false, null, List.of(), false, 50,
                publicUrl, HubProperties.Jwt.DISABLED, HubProperties.LocalAdmin.DEFAULT));
    }

    private AlertChannel channel(String format) {
        Map<String, Object> config = new HashMap<>(Map.of("smtpHost", "127.0.0.1", "smtpPort", smtp.port(),
                "security", "NONE", "from", "svcmon@example.test", "to", List.of("ops@example.test")));
        if (format != null) {
            config.put("format", format);
        }
        return new AlertChannel("ch1", "Email: ops", AlertType.EMAIL, config, Set.of(Status.DOWN), true, TS, null, null, null);
    }

    private static AlertEvent event(String name) {
        return new AlertEvent(AlertEvent.Kind.CHECK, "c1", name, "CPU", null, "s1", "web01", Status.UP, Status.DOWN,
                "CPU 96.2% used", TS);
    }

    private MimeMessage received() throws Exception {
        for (int i = 0; i < 60 && smtp.mails().isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertEquals(1, smtp.mails().size(), "one mail should have arrived");
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(smtp.mails().get(0).data().getBytes(StandardCharsets.UTF_8)));
    }

    /** Mail text travels with CRLF line ends; compare it with the text the hub built. */
    private static String lf(Object content) {
        return ((String) content).replace("\r\n", "\n");
    }

    @Test
    void aChannelWithoutAFormatSendsPlainTextAndHtmlAsAlternatives() throws Exception {
        AlertEvent e = event("CPU");
        sender("https://monitor.example.test").send(channel(null), e, HUB);
        MimeMessage mail = received();

        assertEquals("[DOWN] web01 / CPU", mail.getSubject());
        assertEquals("auto-generated", mail.getHeader("Auto-Submitted", null));
        assertTrue(mail.isMimeType("multipart/alternative"), mail.getContentType());
        Multipart parts = (Multipart) mail.getContent();
        assertEquals(2, parts.getCount());

        Part plain = parts.getBodyPart(0);
        assertTrue(plain.isMimeType("text/plain"), "plain text first: " + plain.getContentType());
        assertEquals(AlertFormat.plainBody(e, HUB), lf(plain.getContent()), "the text part is the one every client has always got");

        Part html = parts.getBodyPart(1);
        assertTrue(html.isMimeType("text/html"), "HTML last, the part clients prefer: " + html.getContentType());
        String page = (String) html.getContent();
        assertTrue(page.startsWith("<!DOCTYPE html>"), page);
        assertTrue(page.contains("href=\"https://monitor.example.test/#/server/s1\""), page);
        assertTrue(page.contains("Email: ops"), "the footer names the channel");
    }

    @Test
    void aTextChannelSendsOnePlainTextPart() throws Exception {
        AlertEvent e = event("CPU");
        sender("https://monitor.example.test").send(channel("TEXT"), e, HUB);
        MimeMessage mail = received();

        assertTrue(mail.isMimeType("text/plain"), mail.getContentType());
        assertEquals(AlertFormat.plainBody(e, HUB), lf(mail.getContent()));
        assertEquals("auto-generated", mail.getHeader("Auto-Submitted", null));
    }

    @Test
    void withoutAPublicAddressTheHtmlHasNoLink() throws Exception {
        sender(null).send(channel("HTML"), event("CPU"), HUB);
        Part html = ((Multipart) received().getContent()).getBodyPart(1);
        assertFalse(((String) html.getContent()).contains("href="));
    }

    @Test
    void nonAsciiNamesSurviveInTheSubjectAndBothParts() throws Exception {
        sender(null).send(channel("HTML"), event("Café ☕ ünïcode"), HUB);
        MimeMessage mail = received();
        assertEquals("[DOWN] web01 / Café ☕ ünïcode", mail.getSubject());
        Multipart parts = (Multipart) mail.getContent();
        assertTrue(((String) parts.getBodyPart(0).getContent()).contains("Café ☕ ünïcode"));
        assertTrue(((String) parts.getBodyPart(1).getContent()).contains("Café ☕ ünïcode"));
    }
}

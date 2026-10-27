package io.primelogiclab.svcmon.hub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.core.ApiException;
import io.primelogiclab.svcmon.hub.model.AlertType;
import org.junit.jupiter.api.Test;

/** Validation, masking and secret handling, without a database or network. */
class AlertChannelServiceTest {

    private static HubProperties props(boolean insecureUrls) {
        return new HubProperties(Path.of("."), 30, 30, 90, null, false, null, List.of(), insecureUrls, 50, null, false, 200,
                HubProperties.Jwt.DISABLED, HubProperties.Users.DEFAULT, HubProperties.Sessions.DEFAULT, true, "classpath:/ui/", HubProperties.Ui.DEFAULT,
                HubProperties.Access.DEFAULT);
    }

    private final AlertChannelService strict = new AlertChannelService(null, null, props(false));
    private final AlertChannelService lax = new AlertChannelService(null, null, props(true));

    private static String rejected(AlertChannelService svc, AlertType type, Map<String, Object> config) {
        return assertThrows(ApiException.class, () -> svc.normalize(type, config)).getMessage();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    // ---- URLs ----

    @Test
    void slackAndDiscordNeedHttpsUnlessExplicitlyRelaxed() {
        for (AlertType type : List.of(AlertType.SLACK, AlertType.DISCORD)) {
            assertEquals(Map.of("url", "https://hooks.example.test/a/b"),
                    strict.normalize(type, Map.of("url", "https://hooks.example.test/a/b")));
            assertTrue(rejected(strict, type, Map.of("url", "http://hooks.example.test/a")).contains("https"));
            assertEquals("http://127.0.0.1:9/a", lax.normalize(type, Map.of("url", "http://127.0.0.1:9/a")).get("url"));
        }
    }

    @Test
    void webhookAcceptsHttpForInternalReceivers() {
        assertEquals("http://alerts.internal:9000/hook",
                strict.normalize(AlertType.WEBHOOK, Map.of("url", "http://alerts.internal:9000/hook")).get("url"));
    }

    @Test
    void badUrlsAreRejectedWithoutEchoingTheSecret() {
        for (String bad : new String[] {"", "not a url", "ftp://x.example/a", "https://user:pw@x.example/secret-token", "https:///secret-token", "javascript:alert(1)"}) {
            String message = rejected(strict, AlertType.WEBHOOK, Map.of("url", bad));
            assertFalse(message.contains("secret-token"), message);
            assertFalse(message.contains("user:pw"), message);
        }
        assertTrue(rejected(strict, AlertType.SLACK, Map.of()).contains("url is required"));
    }

    @Test
    void unknownSettingsAreRejectedSoTyposAreNotSilent() {
        String message = rejected(strict, AlertType.SLACK, Map.of("url", "https://h.example/a", "channel", "#ops"));
        assertTrue(message.contains("Unknown setting 'channel'"), message);
        assertTrue(message.contains("url"), message);
    }

    // ---- webhook headers ----

    @Test
    void webhookHeadersAreChecked() {
        Map<String, Object> ok = strict.normalize(AlertType.WEBHOOK,
                Map.of("url", "https://h.example/a", "headers", Map.of("Authorization", "Bearer abc"), "signingSecret", "s3cret"));
        assertEquals(Map.of("Authorization", "Bearer abc"), ok.get("headers"));
        assertEquals("s3cret", ok.get("signingSecret"));

        for (String name : new String[] {"Host", "content-length", "Content-Type", "Bad Header", "x:y", ""}) {
            assertThrows(ApiException.class,
                    () -> strict.normalize(AlertType.WEBHOOK, Map.of("url", "https://h.example/a", "headers", Map.of(name, "v"))), name);
        }
        assertThrows(ApiException.class, () -> strict.normalize(AlertType.WEBHOOK,
                Map.of("url", "https://h.example/a", "headers", Map.of("X-A", "line1\r\nX-B: injected"))));

        Map<String, Object> many = new LinkedHashMap<>();
        IntStream.range(0, 11).forEach(i -> many.put("X-H" + i, "v"));
        assertThrows(ApiException.class, () -> strict.normalize(AlertType.WEBHOOK, Map.of("url", "https://h.example/a", "headers", many)));
    }

    // ---- email ----

    private static Map<String, Object> email() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("smtpHost", "smtp.example.test");
        c.put("from", "svcmon@example.test");
        c.put("to", List.of("ops@example.test"));
        return c;
    }

    @Test
    void emailGetsSafeDefaults() {
        Map<String, Object> n = strict.normalize(AlertType.EMAIL, email());
        assertEquals(587, n.get("smtpPort"));
        assertEquals("STARTTLS", n.get("security"));
        assertEquals("HTML", n.get("format"));
        assertEquals(List.of("ops@example.test"), n.get("to"));
        assertFalse(n.containsKey("username"));
    }

    @Test
    void emailFormatIsHtmlOrTextInAnyCase() {
        for (String[] pair : new String[][] {{"html", "HTML"}, {"Text", "TEXT"}, {"TEXT", "TEXT"}, {" html ", "HTML"}}) {
            Map<String, Object> c = email();
            c.put("format", pair[0]);
            assertEquals(pair[1], strict.normalize(AlertType.EMAIL, c).get("format"), pair[0]);
        }
        for (String bad : new String[] {"pdf", "rich", "html5"}) {
            Map<String, Object> c = email();
            c.put("format", bad);
            String message = rejected(strict, AlertType.EMAIL, c);
            assertTrue(message.contains("format must be HTML or TEXT"), message);
        }
        Map<String, Object> injected = email();
        injected.put("format", "TEXT\r\nBcc: x@y.test");
        assertThrows(ApiException.class, () -> strict.normalize(AlertType.EMAIL, injected));
        Map<String, Object> slack = new LinkedHashMap<>(Map.of("url", "https://h.example/a", "format", "text"));
        assertTrue(rejected(strict, AlertType.SLACK, slack).contains("Unknown setting 'format'"), "only email has a format");
    }

    @Test
    void emailAcceptsCommaSeparatedRecipientsAndMixedCase() {
        Map<String, Object> c = email();
        c.put("to", "ops@example.test, oncall@example.test ,");
        c.put("security", "ssl");
        c.put("smtpPort", "465");
        Map<String, Object> n = strict.normalize(AlertType.EMAIL, c);
        assertEquals(List.of("ops@example.test", "oncall@example.test"), n.get("to"));
        assertEquals("SSL", n.get("security"));
        assertEquals(465, n.get("smtpPort"));
    }

    @Test
    void emailRejectsBadSettings() {
        for (String[] bad : new String[][] {
                {"smtpHost", "not a host"}, {"smtpHost", "-bad.example"}, {"smtpPort", "0"}, {"smtpPort", "70000"},
                {"smtpPort", "abc"}, {"security", "TLS13"}, {"from", "no-at-sign"}, {"from", "Name <a@b.test>"},
                {"from", "a@b.test\r\nBcc: x@y.test"}, {"password", "pw"}}) {
            Map<String, Object> c = email();
            c.put(bad[0], bad[1]);
            assertThrows(ApiException.class, () -> strict.normalize(AlertType.EMAIL, c), bad[0] + "=" + bad[1]);
        }
        Map<String, Object> noRecipients = email();
        noRecipients.put("to", List.of());
        assertThrows(ApiException.class, () -> strict.normalize(AlertType.EMAIL, noRecipients));

        Map<String, Object> tooMany = email();
        tooMany.put("to", IntStream.range(0, 21).mapToObj(i -> "u" + i + "@example.test").toList());
        assertThrows(ApiException.class, () -> strict.normalize(AlertType.EMAIL, tooMany));

        Map<String, Object> badRecipient = email();
        badRecipient.put("to", List.of("ok@example.test", "evil@example.test, other@example.test;x"));
        assertThrows(ApiException.class, () -> strict.normalize(AlertType.EMAIL, badRecipient));
    }

    // ---- secrets ----

    @Test
    void secretsAreMaskedInResponses() {
        Map<String, Object> webhook = new LinkedHashMap<>();
        webhook.put("url", "https://hooks.example.test:8443/services/T0/B0/token123");
        webhook.put("signingSecret", "s3cret");
        webhook.put("headers", Map.of("Authorization", "Bearer abc"));
        Map<String, Object> masked = AlertChannelService.mask(AlertType.WEBHOOK, webhook);
        assertEquals("https://hooks.example.test:8443/********", masked.get("url"));
        assertEquals("********", masked.get("signingSecret"));
        assertEquals(Map.of("Authorization", "********"), masked.get("headers"));
        assertFalse(masked.toString().contains("token123") || masked.toString().contains("s3cret") || masked.toString().contains("Bearer"));

        Map<String, Object> mail = email();
        mail.put("username", "svc");
        mail.put("password", "hunter2");
        Map<String, Object> maskedMail = AlertChannelService.mask(AlertType.EMAIL, mail);
        assertEquals("********", maskedMail.get("password"));
        assertEquals("svc", maskedMail.get("username"));
        assertEquals("svcmon@example.test", maskedMail.get("from"));

        assertEquals("********", AlertChannelService.maskUrl("::not a url::"));
    }

    @Test
    void maskedOrMissingSecretsKeepTheStoredValueOnUpdate() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("url", "https://hooks.example.test/real-secret");
        existing.put("signingSecret", "old-signing");
        existing.put("headers", Map.of("Authorization", "Bearer old", "X-Team", "ops"));

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("url", "https://hooks.example.test/********");
        incoming.put("headers", Map.of("Authorization", "********", "X-Team", "platform"));
        Map<String, Object> merged = AlertChannelService.mergeSecrets(AlertType.WEBHOOK, incoming, existing);
        assertEquals("https://hooks.example.test/real-secret", merged.get("url"));
        assertEquals("old-signing", merged.get("signingSecret"), "an omitted secret is kept");
        assertEquals(Map.of("Authorization", "Bearer old", "X-Team", "platform"), map(merged.get("headers")));

        Map<String, Object> replaced = AlertChannelService.mergeSecrets(AlertType.WEBHOOK,
                Map.of("url", "https://new.example.test/x", "signingSecret", "new-signing"), existing);
        assertEquals("https://new.example.test/x", replaced.get("url"));
        assertEquals("new-signing", replaced.get("signingSecret"));
    }

    @Test
    void emailPasswordIsKeptWhenOmitted() {
        Map<String, Object> existing = email();
        existing.put("username", "svc");
        existing.put("password", "hunter2");
        Map<String, Object> incoming = email();
        incoming.put("username", "svc");
        incoming.put("password", "********");
        Map<String, Object> merged = AlertChannelService.mergeSecrets(AlertType.EMAIL, incoming, existing);
        assertEquals("hunter2", merged.get("password"));
    }
}

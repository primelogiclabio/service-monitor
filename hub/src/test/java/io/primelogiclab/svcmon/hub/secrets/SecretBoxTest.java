package io.primelogiclab.svcmon.hub.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.model.AlertType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sealing and opening stored secrets, and which values of a config count as secrets. */
class SecretBoxTest {

    @TempDir
    Path dir;

    private SecretBox box() {
        SecretKeys keys = new SecretKeys(dir.resolve(SecretKeys.FILE));
        keys.create();
        return new SecretBox(keys);
    }

    @Test
    void aSealedValueOpensToTheSameAndIsDifferentEachTime() {
        SecretBox box = box();
        String a = box.seal("Bearer abc-123");
        String b = box.seal("Bearer abc-123");
        assertTrue(a.startsWith(SecretBox.PREFIX + box.keys().ring().current() + ":"), a);
        assertNotEquals(a, b, "a new nonce each time");
        assertFalse(a.contains("abc-123"));
        assertEquals("Bearer abc-123", box.open(a));
        assertEquals("Bearer abc-123", box.open(b));
        assertEquals("", box.seal(""), "an empty value holds nothing");
        assertEquals(null, box.seal(null));
    }

    @Test
    void aValueStoredBeforeEncryptionPassesThrough() {
        assertEquals("https://hooks.example/abc", box().open("https://hooks.example/abc"));
    }

    @Test
    void aChangedValueOrAnotherKeyIsRefusedWithoutTheValue() throws Exception {
        SecretBox box = box();
        String sealed = box.seal("smtp-password");
        String head = SecretBox.PREFIX + SecretBox.keyId(sealed) + ":";
        // Every character of nonce, ciphertext and tag counts, whatever the random nonce. The last one is left out:
        // it also carries padding bits the decoder ignores, so some changes to it decode to the same bytes.
        for (int round = 0; round < 20; round++) {
            String value = box.seal("smtp-password");
            for (int i = head.length(); i < value.length() - 1; i++) {
                String changed = value.substring(0, i) + (value.charAt(i) == 'A' ? 'B' : 'A') + value.substring(i + 1);
                assertFalse(box.opens(changed), "a change at " + i + " went unnoticed");
            }
        }
        String changed = head + (sealed.charAt(head.length()) == 'A' ? 'B' : 'A') + sealed.substring(head.length() + 1);
        assertUnreadable(box, changed, "cannot be decrypted");
        assertUnreadable(box, sealed.replace(SecretBox.keyId(sealed), "00000000"), "not in");

        // Another hub's key file, with its own key under the same name, does not open it either.
        Path other = Files.createTempDirectory(dir, "other").resolve(SecretKeys.FILE);
        SecretKeys theirs = new SecretKeys(other);
        SecretKeys.Ring ring = theirs.create();
        theirs.replace(new SecretKeys.Ring(SecretBox.keyId(sealed), Map.of(SecretBox.keyId(sealed), ring.currentKey())));
        assertUnreadable(new SecretBox(theirs), sealed, "cannot be decrypted");
    }

    private static void assertUnreadable(SecretBox box, String value, String says) {
        String message = assertThrows(SecretBox.Unreadable.class, () -> box.open(value)).getMessage();
        assertTrue(message.contains(says), message);
        assertFalse(message.contains("smtp-password") || message.contains(value), message);
    }

    @Test
    void theSecretsOfAChannelAndACheckAreTheOnesTheApiMasks() {
        Map<String, Object> webhook = Map.of("url", "u", "signingSecret", "s", "headers", Map.of("X-Token", "t"),
                "statuses", List.of("DOWN"));
        assertEquals(Map.of("url", "*u", "signingSecret", "*s", "headers", Map.of("X-Token", "*t"),
                "statuses", List.of("DOWN")), SecretFields.channel(AlertType.WEBHOOK, webhook, v -> "*" + v));
        assertEquals(Map.of("smtpHost", "h", "username", "n", "password", "*p"), SecretFields.channel(AlertType.EMAIL,
                Map.of("smtpHost", "h", "username", "n", "password", "p"), v -> "*" + v));
        assertEquals(Map.of("url", "*u"), SecretFields.channel(AlertType.SLACK, Map.of("url", "u"), v -> "*" + v));
        assertEquals(Map.of("keyword", "ok", "headers", Map.of("Authorization", "*a")),
                SecretFields.check(Map.of("keyword", "ok", "headers", Map.of("Authorization", "a")), v -> "*" + v));
        assertEquals(Map.of("keyword", "ok"), SecretFields.check(Map.of("keyword", "ok"), v -> "*" + v));
    }
}

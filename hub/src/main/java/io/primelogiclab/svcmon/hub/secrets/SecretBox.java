package io.primelogiclab.svcmon.hub.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Encrypts the secrets the hub stores (alert channel secrets, HTTP check header values) with AES-256-GCM and the key
 * in {@link SecretKeys}. A sealed value is {@code enc:v1:<key id>:<base64url of nonce, ciphertext and tag>}; the part
 * before the base64 is authenticated too, so a value cannot be moved under another key's name. Values stored before
 * the hub encrypted them carry no prefix and are read as they are, until the hub seals them at start.
 */
@Component
public class SecretBox {

    public static final String PREFIX = "enc:v1:";

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeys keys;

    @Autowired
    public SecretBox(HubProperties props) {
        this(SecretKeys.of(props));
    }

    public SecretBox(SecretKeys keys) {
        this.keys = keys;
    }

    public SecretKeys keys() {
        return keys;
    }

    /** The value sealed with the current key. An empty value holds nothing and stays as it is. */
    public String seal(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        SecretKeys.Ring ring = keys.ring();
        String head = PREFIX + ring.current() + ":";
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, ring.currentKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(head.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array();
            return head + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a secret: " + e.getClass().getSimpleName());
        }
    }

    /** The plain value. One stored before the hub encrypted secrets passes through. */
    public String open(String stored) {
        if (!isSealed(stored)) {
            return stored;
        }
        String id = keyId(stored);
        SecretKey key = keys.ring().keys().get(id);
        if (key == null) {
            throw new Unreadable("A stored secret was encrypted with key " + id + ", which is not in "
                    + keys.file());
        }
        String head = PREFIX + id + ":";
        try {
            byte[] in = Base64.getUrlDecoder().decode(stored.substring(head.length()));
            if (in.length <= NONCE_BYTES) {
                throw new IllegalArgumentException();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
            cipher.updateAAD(head.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Unreadable("A stored secret cannot be decrypted with key " + id + " from " + keys.file()
                    + ": it was changed, or that key is not the one that encrypted it");
        }
    }

    /** Whether the value can be read with the keys in the file; never throws for a value that cannot. */
    public boolean opens(String stored) {
        try {
            open(stored);
            return true;
        } catch (Unreadable e) {
            return false;
        }
    }

    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** The id of the key that sealed the value; empty when the value names none. */
    public static String keyId(String sealed) {
        int end = sealed.indexOf(':', PREFIX.length());
        return end < 0 ? "" : sealed.substring(PREFIX.length(), end);
    }

    /** A stored secret that no key in the file opens. The message names the key, never the value. */
    public static final class Unreadable extends IllegalStateException {

        Unreadable(String message) {
            super(message);
        }
    }
}

package io.primelogiclab.svcmon.hub.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Stores passwords as PBKDF2-HMAC-SHA256 with a random salt, so a database leak does not reveal them and each one
 * has to be guessed separately. Nothing but the JDK is needed. The stored text is
 * {@code pbkdf2-sha256$iterations$salt$hash} (salt and hash in base64), so the cost can be raised later and old
 * hashes still verify; the iteration count follows OWASP's advice for this function.
 */
@Component
public class PasswordHasher {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    static final int ITERATIONS = 600_000;
    private static final int MAX_ITERATIONS = 5_000_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    @Autowired
    public PasswordHasher() {
        this(ITERATIONS);
    }

    /** Tests use a low count so they stay quick. */
    PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    /** Why this password is not acceptable, or null when it is. Length is what matters; no composition rules. */
    public static String problem(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return "The password must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters";
        }
        return null;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return PREFIX + "$" + iterations + "$" + b64(salt) + "$" + b64(derive(password, salt, iterations));
    }

    /** True when the password matches the stored text. Anything malformed or unknown is simply false. */
    public boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
            return false;
        }
        try {
            int count = Integer.parseInt(parts[1]);
            if (count < 1 || count > MAX_ITERATIONS) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, count));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int count) {
        // The same word can be typed composed or decomposed (an accented letter); treat both as one.
        char[] chars = Normalizer.normalize(password, Normalizer.Form.NFKC).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, count, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available in this Java runtime", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** A random password for first start and recovery: 144 bits, easy to paste. */
    public static String generate() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}

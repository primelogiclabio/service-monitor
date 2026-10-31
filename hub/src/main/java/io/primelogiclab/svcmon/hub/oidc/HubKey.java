package io.primelogiclab.svcmon.hub.oidc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;

/**
 * The hub's own key pair, with which it proves who it is to the identity provider (private_key_jwt) instead of a
 * client secret: RSA, {@value #BITS} bits. It is made the first time it is needed and kept in the data folder as
 * {@value #FILE}, readable only by the hub's account where the file system has POSIX permissions (elsewhere the data
 * folder's own permissions protect it, as they protect the database). The private key never leaves the hub; the
 * public key is served at /api/v1/oidc/jwks for the provider. {@code oidc rotate-key} writes a new one, and a running
 * hub notices that the file changed.
 */
public final class HubKey implements SigningKey {

    public static final String FILE = "oidc-key.json";
    static final int BITS = 3072;

    private final Path file;
    private RSAKey cached;
    private FileTime cachedTime;

    public HubKey(Path dataDir) {
        this.file = dataDir.resolve(FILE);
    }

    public Path file() {
        return file;
    }

    public synchronized boolean exists() {
        return Files.exists(file);
    }

    /** The hub makes no certificate for its own key; a provider that wants one gets a {@link FileKey}. */
    @Override
    public X509Certificate certificate() {
        return null;
    }

    /** The key, read again when the file changed; made and saved now when there is none. */
    @Override
    public synchronized RSAKey current() {
        try {
            if (!Files.exists(file)) {
                return save(generate(), false);
            }
            FileTime time = Files.getLastModifiedTime(file);
            if (cached == null || !time.equals(cachedTime)) {
                cached = read();
                cachedTime = time;
            }
            return cached;
        } catch (IOException e) {
            throw new IllegalStateException("The hub's key file " + file + " cannot be read or written: " + e);
        }
    }

    /** Makes a new key in place of the old one, which stops working at once; returns the new key. */
    public synchronized RSAKey rotate() {
        try {
            return save(generate(), true);
        } catch (IOException e) {
            throw new IllegalStateException("The hub's key file " + file + " cannot be written: " + e);
        }
    }

    /** The public key as PEM (SubjectPublicKeyInfo), for a provider that wants it pasted or uploaded. */
    public static String pem(RSAKey key) {
        try {
            byte[] der = key.toRSAPublicKey().getEncoded();
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                    + "\n-----END PUBLIC KEY-----\n";
        } catch (JOSEException e) {
            throw new IllegalStateException("The hub's public key cannot be written as PEM", e);
        }
    }

    private static RSAKey generate() {
        try {
            // No "alg" in the key, so the provider may take RS256 or PS256 from it; the key id is its thumbprint.
            return new RSAKeyGenerator(BITS).keyUse(KeyUse.SIGNATURE).keyIDFromThumbprint(true).issueTime(new Date())
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not make the hub's key pair", e);
        }
    }

    private RSAKey read() throws IOException {
        try {
            JWKSet set = JWKSet.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (set.getKeys().size() == 1 && set.getKeys().get(0) instanceof RSAKey rsa && rsa.isPrivate()
                    && rsa.size() >= 2048) {
                return rsa;
            }
        } catch (ParseException e) {
            // reported below, without the file's content
        }
        throw new IllegalStateException("The hub's key file " + file + " does not hold one private RSA key; move it"
                + " away and the hub makes a new one (then give the provider the new public key)");
    }

    /** Writes the key next to the old file and moves it into place, so a reader never sees half a file. */
    private RSAKey save(RSAKey key, boolean replace) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE + ".tmp");
        Files.deleteIfExists(tmp);
        if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, new JWKSet((JWK) key).toString(false), StandardCharsets.UTF_8);
        if (replace) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } else {
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException e) {
                // another process made one first: use that
                Files.deleteIfExists(tmp);
                cached = null;
                return current();
            }
        }
        cached = key;
        cachedTime = Files.getLastModifiedTime(file);
        return key;
    }
}

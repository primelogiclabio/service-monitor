package io.primelogiclab.svcmon.hub.oidc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.Base64URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A key pair the hub is given instead of making one (svcmon.oidc.private-key-file and certificate-file): the files
 * signing-keys-tool writes, a PKCS#8 private key ({@code -----BEGIN PRIVATE KEY-----}, no password) and a self-signed
 * X.509 certificate for it. The certificate is what a provider such as Entra ID wants uploaded; it also says when the
 * pair expires, which the hub reports but does not enforce (the provider does). Both files are checked when the hub
 * starts, and read again when either changes, so a new pair needs no restart; a new pair that does not load is
 * reported and the old one kept.
 */
public final class FileKey implements SigningKey {

    static final int MIN_BITS = 2048;
    /** From this many days before the certificate expires, the hub warns. */
    public static final long WARN_DAYS = 30;

    private static final Logger log = LoggerFactory.getLogger(FileKey.class);
    private static final Pattern PEM_KEY = Pattern.compile(
            "-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----");

    private record Loaded(RSAKey key, X509Certificate certificate, FileTime keyTime, FileTime certTime) {
    }

    private final Path keyFile;
    private final Path certFile;
    private Loaded loaded;
    private FileTime failedKeyTime;
    private FileTime failedCertTime;

    /** Loads both files now; a problem stops the start-up with the setting's name. */
    public FileKey(Path keyFile, Path certFile) {
        this.keyFile = keyFile;
        this.certFile = certFile;
        this.loaded = load();
        warnIfReadableByOthers(keyFile);
    }

    public Path keyFile() {
        return keyFile;
    }

    public Path certificateFile() {
        return certFile;
    }

    @Override
    public synchronized RSAKey current() {
        refresh();
        return loaded.key();
    }

    @Override
    public synchronized X509Certificate certificate() {
        refresh();
        return loaded.certificate();
    }

    /** When the certificate stops being valid. */
    public Instant expiresAt() {
        return certificate().getNotAfter().toInstant();
    }

    /** Calendar days (UTC) until the certificate expires, as signing-keys-tool counts them; negative once it has. */
    public static long daysLeft(Instant expiresAt, Instant now) {
        return ChronoUnit.DAYS.between(LocalDate.ofInstant(now, ZoneOffset.UTC),
                LocalDate.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    /** base64url(SHA-1) and base64url(SHA-256) of the certificate: the x5t and x5t#S256 a provider matches it by. */
    static Base64URL thumbprint(X509Certificate cert, String algorithm) {
        try {
            return Base64URL.encode(MessageDigest.getInstance(algorithm).digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateException e) {
            throw new IllegalStateException("Could not take the certificate's thumbprint", e);
        }
    }

    private void refresh() {
        FileTime keyTime;
        FileTime certTime;
        try {
            keyTime = Files.getLastModifiedTime(keyFile);
            certTime = Files.getLastModifiedTime(certFile);
        } catch (IOException e) {
            return; // the files went away for a moment (being replaced): keep using what was loaded
        }
        if (keyTime.equals(loaded.keyTime()) && certTime.equals(loaded.certTime())
                || keyTime.equals(failedKeyTime) && certTime.equals(failedCertTime)) {
            return;
        }
        try {
            String before = loaded.key().getKeyID();
            loaded = load();
            failedKeyTime = null;
            failedCertTime = null;
            if (!loaded.key().getKeyID().equals(before)) {
                log.info("The hub signs in to the identity provider with the new key pair in {} (key id {})", keyFile,
                        loaded.key().getKeyID());
            }
            warnIfReadableByOthers(keyFile);
        } catch (IllegalStateException e) {
            failedKeyTime = keyTime;
            failedCertTime = certTime;
            log.error("{}; the hub keeps using the key it loaded before (key id {})", e.getMessage(),
                    loaded.key().getKeyID());
        }
    }

    private Loaded load() {
        try {
            FileTime keyTime = Files.getLastModifiedTime(keyFile);
            FileTime certTime = Files.getLastModifiedTime(certFile);
            RSAPrivateCrtKey privateKey = privateKey(keyFile);
            X509Certificate cert = certificate(certFile);
            if (!(cert.getPublicKey() instanceof RSAPublicKey publicKey)) {
                throw OidcSettings.invalid("certificate-file", "the certificate in " + certFile + " is not for an RSA key");
            }
            if (!publicKey.getModulus().equals(privateKey.getModulus())
                    || !publicKey.getPublicExponent().equals(privateKey.getPublicExponent())) {
                throw OidcSettings.invalid("certificate-file", "the certificate in " + certFile + " is for another key"
                        + " than the one in " + keyFile);
            }
            if (publicKey.getModulus().bitLength() < MIN_BITS) {
                throw OidcSettings.invalid("private-key-file", "the key in " + keyFile + " has "
                        + publicKey.getModulus().bitLength() + " bits; it needs at least " + MIN_BITS + " (3072 is"
                        + " recommended: signing-keys-tool generate --bits 3072)");
            }
            RSAKey key = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .x509CertChain(List.of(Base64.encode(cert.getEncoded())))
                    .x509CertSHA256Thumbprint(thumbprint(cert, "SHA-256"))
                    .keyIDFromThumbprint()
                    .build();
            return new Loaded(key, cert, keyTime, certTime);
        } catch (IOException e) {
            throw OidcSettings.invalid("private-key-file", "the key pair's files cannot be read: " + e);
        } catch (CertificateException | JOSEException e) {
            throw OidcSettings.invalid("certificate-file", "the certificate in " + certFile + " cannot be used ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private static RSAPrivateCrtKey privateKey(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII);
        if (text.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw OidcSettings.invalid("private-key-file", "the key in " + file + " has a password; the hub needs it"
                    + " without one, protected by the file's permissions instead");
        }
        if (text.contains("BEGIN RSA PRIVATE KEY")) {
            throw OidcSettings.invalid("private-key-file", "the key in " + file + " is in the older PKCS#1 form;"
                    + " convert it with: openssl pkcs8 -topk8 -nocrypt -in <old.pem> -out <new.pem>");
        }
        Matcher m = PEM_KEY.matcher(text);
        if (!m.find()) {
            throw OidcSettings.invalid("private-key-file", file + " holds no PEM private key (-----BEGIN PRIVATE"
                    + " KEY-----)");
        }
        try {
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(java.util.Base64.getMimeDecoder().decode(m.group(1))));
            if (key instanceof RSAPrivateCrtKey crt) {
                return crt;
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // reported below, without the file's content
        }
        throw OidcSettings.invalid("private-key-file", "the key in " + file + " is not an RSA private key");
    }

    private static X509Certificate certificate(Path file) throws IOException, CertificateException {
        try (InputStream in = Files.newInputStream(file)) {
            if (CertificateFactory.getInstance("X.509").generateCertificate(in) instanceof X509Certificate cert) {
                return cert;
            }
        }
        throw OidcSettings.invalid("certificate-file", file + " holds no X.509 certificate");
    }

    /** The private key should be readable by the hub's account alone, as signing-keys-tool leaves it. */
    private static void warnIfReadableByOthers(Path file) {
        try {
            if (!Files.getFileStore(file).supportsFileAttributeView("posix")) {
                return;
            }
            Set<PosixFilePermission> p = Files.getPosixFilePermissions(file);
            if (p.contains(PosixFilePermission.GROUP_READ) || p.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("The hub's private key {} can be read by other accounts; limit it to the hub's (chmod 600)",
                        file);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // no permissions to look at
        }
    }
}

package io.primelogiclab.svcmon.hub.oidc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Key pairs in the files signing-keys-tool writes (a PKCS#8 private key and a self-signed certificate, both PEM), made
 * for tests the way the tool makes them: with the JDK's own keytool, which signs the certificate.
 */
public final class KeyFiles {

    /** The two files of one pair. */
    public record Pair(Path key, Path certificate) {
    }

    private KeyFiles() {
    }

    /** A pair of {@code bits} that is valid from {@code startDays} (negative is in the past) for {@code days}. */
    public static Pair make(Path dir, String name, int bits, int startDays, int days) throws Exception {
        Files.createDirectories(dir);
        Path store = dir.resolve(name + "-" + UUID.randomUUID() + ".p12");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        List<String> cmd = new ArrayList<>(List.of(keytool.toString(), "-genkeypair", "-alias", "k", "-keyalg", "RSA",
                "-keysize", String.valueOf(bits), "-sigalg", "SHA256withRSA", "-dname", "CN=svcmon-test",
                "-validity", String.valueOf(days), "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit", "-keypass", "changeit"));
        if (startDays != 0) {
            cmd.addAll(List.of("-startdate", (startDays > 0 ? "+" : "") + startDays + "d"));
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        Files.delete(store);
        PrivateKey key = (PrivateKey) ks.getKey("k", "changeit".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("k");
        Path keyFile = dir.resolve(name + "-private.pem");
        Path certFile = dir.resolve(name + "-cert.pem");
        Files.writeString(keyFile, pem("PRIVATE KEY", key.getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(certFile, pem("CERTIFICATE", cert.getEncoded()), StandardCharsets.US_ASCII);
        return new Pair(keyFile, certFile);
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** Copies a pair over another's files with a later modification time, as a new pair dropped in place. */
    public static void replace(Pair target, Pair with, long secondsLater) throws IOException {
        Files.copy(with.key(), target.key(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(with.certificate(), target.certificate(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        bump(target, secondsLater);
    }

    public static void bump(Pair pair, long secondsLater) throws IOException {
        var later = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + secondsLater * 1000);
        Files.setLastModifiedTime(pair.key(), later);
        Files.setLastModifiedTime(pair.certificate(), later);
    }
}

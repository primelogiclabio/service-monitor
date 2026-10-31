package io.primelogiclab.svcmon.hub.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.id.ClientID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A key pair given to the hub in signing-keys-tool's files instead of the one it makes. */
class FileKeyTest {

    private static final URI TOKEN = URI.create("https://auth.example/realms/main/protocol/openid-connect/token");

    @Test
    void theToolsFilesLoadAndTheCertificateIsPublished(@TempDir Path dir) throws Exception {
        KeyFiles.Pair pair = KeyFiles.make(dir, "signing", 3072, 0, 1825);
        FileKey key = new FileKey(pair.key(), pair.certificate());
        RSAKey k = key.current();
        assertTrue(k.isPrivate());
        assertEquals(3072, k.size());
        assertEquals(k.computeThumbprint().toString(), k.getKeyID());
        assertNotNull(key.certificate());
        long days = FileKey.daysLeft(key.expiresAt(), Instant.now());
        assertTrue(days >= 1823 && days <= 1825, "about five years: " + days);

        String published = key.publicKeys().toString();
        assertFalse(published.contains("\"d\"") || published.contains("\"p\""), "never the private half");
        assertTrue(published.contains("\"x5c\"") && published.contains("\"x5t#S256\""), published);
    }

    @Test
    void theProofNamesTheCertificateForProvidersThatMatchByIt(@TempDir Path dir) throws Exception {
        KeyFiles.Pair pair = KeyFiles.make(dir, "signing", 2048, 0, 365);
        FileKey key = new FileKey(pair.key(), pair.certificate());
        SignedJWT proof = ProviderClient.clientAssertion(new ClientID("service-monitor"), TOKEN, JWSAlgorithm.RS256, key);

        byte[] der = key.certificate().getEncoded();
        assertEquals(b64url(MessageDigest.getInstance("SHA-256").digest(der)), proof.getHeader().getX509CertSHA256Thumbprint().toString());
        @SuppressWarnings("deprecation")
        String x5t = proof.getHeader().getX509CertThumbprint().toString();
        assertEquals(b64url(MessageDigest.getInstance("SHA-1").digest(der)), x5t);
        assertEquals(key.current().getKeyID(), proof.getHeader().getKeyID());
        assertTrue(proof.verify(new RSASSAVerifier(key.current().toRSAPublicKey())));
        assertEquals("service-monitor", proof.getJWTClaimsSet().getIssuer());
        assertEquals("service-monitor", proof.getJWTClaimsSet().getSubject());
        assertEquals(List.of(TOKEN.toString()), proof.getJWTClaimsSet().getAudience());
        assertNotNull(proof.getJWTClaimsSet().getJWTID());
        assertTrue(proof.getJWTClaimsSet().getExpirationTime().toInstant().isBefore(Instant.now().plus(10, ChronoUnit.MINUTES)));

        SignedJWT own = ProviderClient.clientAssertion(new ClientID("service-monitor"), TOKEN, JWSAlgorithm.PS256,
                new HubKey(dir.resolve("own")));
        assertNull(own.getHeader().getX509CertSHA256Thumbprint(), "the hub's own key has no certificate to name");
        assertEquals(JWSAlgorithm.PS256, own.getHeader().getAlgorithm());
    }

    @Test
    void aCertificateForAnotherKeyIsRefused(@TempDir Path dir) throws Exception {
        KeyFiles.Pair one = KeyFiles.make(dir.resolve("one"), "signing", 2048, 0, 365);
        KeyFiles.Pair two = KeyFiles.make(dir.resolve("two"), "signing", 2048, 0, 365);
        String message = assertThrows(IllegalStateException.class, () -> new FileKey(one.key(), two.certificate()))
                .getMessage();
        assertTrue(message.startsWith("svcmon.oidc.certificate-file:") && message.contains("another key"), message);
    }

    @Test
    void oldFormsPasswordsAndSmallKeysAreRefusedWithAWayOut(@TempDir Path dir) throws Exception {
        KeyFiles.Pair good = KeyFiles.make(dir, "signing", 2048, 0, 365);
        Path pkcs1 = Files.writeString(dir.resolve("old.pem"), KeyFiles.pem("RSA PRIVATE KEY", new byte[] {1, 2, 3}));
        assertTrue(refused(pkcs1, good.certificate()).contains("openssl pkcs8 -topk8 -nocrypt"));
        Path encrypted = Files.writeString(dir.resolve("enc.pem"), KeyFiles.pem("ENCRYPTED PRIVATE KEY", new byte[] {1}));
        assertTrue(refused(encrypted, good.certificate()).contains("has a password"));
        Path junk = Files.writeString(dir.resolve("junk.pem"), "not a key at all");
        assertTrue(refused(junk, good.certificate()).contains("holds no PEM private key"));

        KeyFiles.Pair small = KeyFiles.make(dir.resolve("small"), "signing", 1024, 0, 365);
        String tooSmall = refused(small.key(), small.certificate());
        assertTrue(tooSmall.contains("1024 bits") && tooSmall.contains("--bits 3072"), tooSmall);
        String secret = Files.readString(good.key()).lines().skip(1).findFirst().orElseThrow();
        assertFalse(refused(good.key(), small.certificate()).contains(secret), "a key's content never appears");
    }

    @Test
    void aNewPairIsPickedUpAndABrokenOneLeavesTheOldInUse(@TempDir Path dir) throws Exception {
        KeyFiles.Pair pair = KeyFiles.make(dir.resolve("live"), "signing", 2048, 0, 365);
        FileKey key = new FileKey(pair.key(), pair.certificate());
        String first = key.current().getKeyID();

        KeyFiles.replace(pair, KeyFiles.make(dir.resolve("next"), "signing", 2048, 0, 365), 5);
        String second = key.current().getKeyID();
        assertNotEquals(first, second, "the new pair, without a restart");

        Files.writeString(pair.key(), "broken");
        KeyFiles.bump(pair, 10);
        assertEquals(second, key.current().getKeyID(), "the pair that worked is kept");
    }

    @Test
    void theExpiryIsCountedInDays(@TempDir Path dir) throws Exception {
        KeyFiles.Pair soon = KeyFiles.make(dir.resolve("soon"), "signing", 2048, -5, 15);
        long left = FileKey.daysLeft(new FileKey(soon.key(), soon.certificate()).expiresAt(), Instant.now());
        assertTrue(left >= 8 && left <= 10 && left <= FileKey.WARN_DAYS, "soon: " + left);
        KeyFiles.Pair gone = KeyFiles.make(dir.resolve("gone"), "signing", 2048, -10, 2);
        assertTrue(FileKey.daysLeft(new FileKey(gone.key(), gone.certificate()).expiresAt(), Instant.now()) < 0,
                "an expired pair still loads; the provider refuses it, the hub warns");
    }

    private static String refused(Path key, Path cert) {
        return assertThrows(IllegalStateException.class, () -> new FileKey(key, cert)).getMessage();
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

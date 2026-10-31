package io.primelogiclab.svcmon.hub.oidc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hub's key pair for the identity provider: made once, kept private, replaced on demand. */
class HubKeyTest {

    @Test
    void theKeyIsMadeOnceAndKeptInTheDataFolder(@TempDir Path dir) throws Exception {
        HubKey key = new HubKey(dir);
        assertFalse(key.exists());
        RSAKey made = key.current();
        assertTrue(key.exists());
        assertTrue(made.isPrivate());
        assertEquals(HubKey.BITS, made.size());
        assertEquals(made.computeThumbprint().toString(), made.getKeyID(), "the key id is its thumbprint");
        assertEquals(made.getKeyID(), new HubKey(dir).current().getKeyID(), "another start reads the same key");
        assertFalse(Files.exists(dir.resolve(HubKey.FILE + ".tmp")));

        String published = key.publicKeys().toString();
        assertFalse(published.contains("\"d\"") || published.contains("\"p\""), published);
    }

    @Test
    void onlyTheHubsAccountMayReadTheFile(@TempDir Path dir) throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "POSIX permissions only");
        new HubKey(dir).current();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(HubKey.FILE))));
    }

    @Test
    void thePemIsTheStandardPublicKeyForm(@TempDir Path dir) throws Exception {
        RSAKey k = new HubKey(dir).current();
        String pem = HubKey.pem(k);
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n") && pem.endsWith("\n-----END PUBLIC KEY-----\n"), pem);
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replace("\n", "");
        var parsed = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        assertEquals(k.toRSAPublicKey(), parsed);
        assertTrue(pem.lines().allMatch(l -> l.length() <= 64), "64 characters a line");
    }

    @Test
    void aNewKeyReplacesTheOldAndARunningHubNotices(@TempDir Path dir) {
        HubKey running = new HubKey(dir);
        String before = running.current().getKeyID();
        String after = new HubKey(dir).rotate().getKeyID(); // as `oidc rotate-key` does, from another process
        assertNotEquals(before, after);
        assertEquals(after, running.current().getKeyID());
        assertEquals(1, running.publicKeys().getKeys().size(), "the old key is no longer published");
    }

    @Test
    void aDamagedFileIsReportedWithoutItsContent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(HubKey.FILE), "{\"keys\":[{\"kty\":\"oct\",\"k\":\"c2VjcmV0LWJ5dGVz\"}]}");
        String message = assertThrows(IllegalStateException.class, () -> new HubKey(dir).current()).getMessage();
        assertTrue(message.contains("does not hold one private RSA key"), message);
        assertFalse(message.contains("c2VjcmV0"), message);
    }
}

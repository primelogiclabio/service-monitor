package io.primelogiclab.svcmon.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.oidc.HubKey;
import io.primelogiclab.svcmon.hub.oidc.KeyFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hub's key pair from the command line, for setting up the identity provider. */
class OidcCommandTest {

    private record Result(int code, String out, String err) {

        String keyId() {
            Matcher m = Pattern.compile("Key id: (\\S+)").matcher(out);
            assertTrue(m.find(), out);
            return m.group(1);
        }
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = OidcCommand.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void keyMakesThePairWhenNeededAndPrintsOnlyThePublicHalf(@TempDir Path dir) {
        Result first = run("oidc", "key", "--svcmon.data-dir=" + dir);
        assertEquals(0, first.code(), first.err());
        assertTrue(first.out().contains("Made the hub's key pair"), first.out());
        assertTrue(first.out().contains("-----BEGIN PUBLIC KEY-----"), first.out());
        assertTrue(first.out().contains("\"kty\":\"RSA\""), first.out());
        assertFalse(first.out().contains("\"d\"") || first.out().contains("PRIVATE"), "never the private key");
        assertEquals(new HubKey(dir).current().getKeyID(), first.keyId());

        Result again = run("oidc", "key", "--svcmon.data-dir=" + dir);
        assertFalse(again.out().contains("Made"), again.out());
        assertEquals(first.keyId(), again.keyId());
    }

    @Test
    void rotateKeyReplacesThePair(@TempDir Path dir) {
        String before = run("oidc", "key", "--svcmon.data-dir=" + dir).keyId();
        Result rotated = run("oidc", "rotate-key", "--svcmon.data-dir=" + dir);
        assertEquals(0, rotated.code(), rotated.err());
        assertTrue(rotated.out().contains("the old key no longer works"), rotated.out());
        assertNotEquals(before, rotated.keyId());
        assertEquals(rotated.keyId(), new HubKey(dir).current().getKeyID());
    }

    @Test
    void aPairFromFilesIsShownWithItsExpiryAndReplacedWithTheTool(@TempDir Path dir) throws Exception {
        KeyFiles.Pair pair = KeyFiles.make(dir.resolve("keys"), "signing", 3072, 0, 1825);
        String[] files = {"--svcmon.data-dir=" + dir, "--svcmon.oidc.private-key-file=" + pair.key(),
                "--svcmon.oidc.certificate-file=" + pair.certificate()};
        Result key = run("oidc", "key", files[0], files[1], files[2]);
        assertEquals(0, key.code(), key.err());
        assertTrue(key.out().contains("Certificate expires") && key.out().contains("days left"), key.out());
        assertTrue(key.out().contains("\"x5c\""), "the JWK set carries the certificate");
        assertFalse(key.out().contains("PRIVATE KEY"), key.out());
        assertFalse(Files.exists(dir.resolve(HubKey.FILE)), "no key of the hub's own is made");

        Result rotate = run("oidc", "rotate-key", files[0], files[1], files[2]);
        assertEquals(1, rotate.code());
        assertTrue(rotate.err().contains("signing-keys-tool"), rotate.err());
    }

    @Test
    void badArgumentsAndSettingsAreRefused(@TempDir Path dir) {
        assertEquals(2, run("oidc").code());
        assertEquals(2, run("oidc", "delete-key").code());
        assertEquals(2, run("oidc", "key", "extra").code());
        assertTrue(run("oidc", "--help").out().contains("Usage:"));
        Result typo = run("oidc", "key", "--svcmon.data-dri=" + dir);
        assertEquals(1, typo.code());
        assertTrue(typo.err().contains("svcmon.data-dri"), typo.err());
    }
}

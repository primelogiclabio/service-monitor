package io.primelogiclab.svcmon.hub.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key file for stored secrets: made once, kept private, read again when it changes. */
class SecretKeysTest {

    @TempDir
    Path dir;

    @Test
    void theKeyIsMadeOnceAndReadTheSameByAnotherStart() {
        Path file = dir.resolve(SecretKeys.FILE);
        SecretKeys keys = new SecretKeys(file);
        assertFalse(keys.exists());
        SecretKeys.Ring made = keys.create();
        assertEquals(32, made.currentKey().getEncoded().length, "AES-256");
        assertEquals(1, made.keys().size());
        SecretKeys.Ring again = new SecretKeys(file).create();
        assertEquals(made.current(), again.current(), "a file that is there is kept");
        assertArrayEquals(made.currentKey().getEncoded(), again.currentKey().getEncoded());
        assertFalse(Files.exists(dir.resolve(SecretKeys.FILE + ".tmp")));
    }

    @Test
    void onlyTheHubsAccountMayReadTheFile() throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "POSIX permissions only");
        new SecretKeys(dir.resolve(SecretKeys.FILE)).create();
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(SecretKeys.FILE))));
    }

    @Test
    void theSettingPutsTheFileWhereItSays() {
        Path data = dir.resolve("data");
        Path elsewhere = dir.resolve("keys").resolve("hub-secrets.json");
        assertEquals(elsewhere.toAbsolutePath().normalize(), SecretKeys.of(props(data, elsewhere)).file());
        assertEquals(data.resolve(SecretKeys.FILE).toAbsolutePath().normalize(), SecretKeys.of(props(data, null)).file());
    }

    private static HubProperties props(Path dataDir, Path keyFile) {
        return new HubProperties(dataDir, 30, 30, 90, null, false, null, List.of(), false, 50, null, true, 200,
                HubProperties.Users.DEFAULT, HubProperties.Sessions.DEFAULT, true, "classpath:/ui/",
                HubProperties.Access.DEFAULT, HubProperties.Audit.DEFAULT, HubProperties.Oidc.DEFAULT,
                new HubProperties.Secrets(keyFile), HubProperties.Maintenance.DEFAULT);
    }

    @Test
    void aMissingFileIsAnErrorUntilItIsMade() {
        SecretKeys keys = new SecretKeys(dir.resolve(SecretKeys.FILE));
        String message = assertThrows(IllegalStateException.class, keys::ring).getMessage();
        assertTrue(message.contains("is missing") && message.contains(SecretKeys.FILE), message);
    }

    @Test
    void aNewKeyIsSeenByARunningHub() {
        Path file = dir.resolve(SecretKeys.FILE);
        SecretKeys running = new SecretKeys(file);
        SecretKeys.Ring before = running.create();
        SecretKeys other = new SecretKeys(file); // as 'secrets rotate-key' does, from another process
        SecretKeys.Ring next = SecretKeys.withNewKey(other.ring());
        other.replace(next);
        assertNotEquals(before.current(), next.current());
        assertEquals(next.current(), running.ring().current());
        assertEquals(2, running.ring().keys().size(), "the old key stays until nothing uses it");

        other.replace(new SecretKeys.Ring(next.current(), Map.of(next.current(), next.currentKey())));
        assertEquals(1, running.ring().keys().size());
    }

    @Test
    void aDamagedFileIsReportedWithoutItsContent() throws Exception {
        Path file = dir.resolve(SecretKeys.FILE);
        Files.writeString(file, "{\"version\":1,\"current\":\"a\",\"keys\":{\"a\":\"c2hvcnQta2V5LWJ5dGVz\"}}");
        String message = assertThrows(IllegalStateException.class, () -> new SecretKeys(file).ring()).getMessage();
        assertTrue(message.contains("is damaged"), message);
        assertFalse(message.contains("c2hvcnQ"), message);
    }
}

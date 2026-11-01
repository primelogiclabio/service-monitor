package io.primelogiclab.svcmon.hub.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes a file that holds a key: readable only by the hub's account where the file system has POSIX permissions
 * (elsewhere the data folder's own permissions protect it, as they protect the database), and moved into place in one
 * step, so a reader never sees half a file.
 */
public final class PrivateFile {

    private PrivateFile() {
    }

    /** Writes content to file. Without replace, a file already there is kept and false is returned. */
    public static boolean write(Path file, String content, boolean replace) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        if (replace) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException e) {
            // another process made one first
            Files.deleteIfExists(tmp);
            return false;
        }
    }
}

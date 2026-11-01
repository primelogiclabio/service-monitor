package io.primelogiclab.svcmon.hub.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.config.PrivateFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The keys the hub encrypts its stored secrets with: AES, 256 bits, made by the hub and kept in {@value #FILE} in the
 * data folder, or in the file {@code svcmon.secrets.key-file} names, written like its other key files ({@link
 * PrivateFile}). The file holds the current key and, while a rotation is under way, the ones before it, by id; each
 * stored value names the key that sealed it. A running hub reads the file again when it changes.
 */
public final class SecretKeys {

    public static final String FILE = "secrets-key.json";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** The keys by id, and the one that seals new values. */
    public record Ring(String current, Map<String, SecretKey> keys) {

        public SecretKey currentKey() {
            return keys.get(current);
        }
    }

    private final Path file;
    private Ring cached;
    private String cachedStamp;

    public SecretKeys(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    /** The file the hub's settings name. */
    public static SecretKeys of(HubProperties props) {
        Path keyFile = props.secrets() == null ? null : props.secrets().keyFile();
        return new SecretKeys(keyFile != null ? keyFile : props.dataDir().resolve(FILE));
    }

    public Path file() {
        return file;
    }

    public synchronized boolean exists() {
        return Files.exists(file);
    }

    /** The keys, read again when the file changed. There must be a file: {@link #create()} makes one. */
    public synchronized Ring ring() {
        try {
            String stamp = stamp();
            if (cached == null || !stamp.equals(cachedStamp)) {
                cached = read();
                cachedStamp = stamp;
            }
            return cached;
        } catch (NoSuchFileException e) {
            throw new IllegalStateException("The key file for the hub's stored secrets, " + file + ", is missing");
        } catch (IOException e) {
            throw new IllegalStateException("The key file " + file + " cannot be read: " + e);
        }
    }

    /** Makes the file with one new key unless there is one already; returns the keys either way. */
    public synchronized Ring create() {
        if (Files.exists(file)) {
            return ring();
        }
        String id = newId(Map.of());
        Ring ring = new Ring(id, Map.of(id, newKey()));
        try {
            if (!PrivateFile.write(file, render(ring), false)) {
                // another process made one first: use that
                cached = null;
                return ring();
            }
            cached = ring;
            cachedStamp = stamp();
            return ring;
        } catch (IOException e) {
            throw new IllegalStateException("The key file " + file + " cannot be written: " + e);
        }
    }

    /** Writes these keys in place of the file's, in one step. */
    public synchronized void replace(Ring ring) {
        try {
            PrivateFile.write(file, render(ring), true);
            cached = ring;
            cachedStamp = stamp();
        } catch (IOException e) {
            throw new IllegalStateException("The key file " + file + " cannot be written: " + e);
        }
    }

    /** These keys and a new one, which becomes the current key. */
    public static Ring withNewKey(Ring ring) {
        Map<String, SecretKey> keys = new LinkedHashMap<>(ring.keys());
        String id = newId(keys);
        keys.put(id, newKey());
        return new Ring(id, keys);
    }

    private static SecretKey newKey() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }

    private static String newId(Map<String, ?> taken) {
        String id;
        do {
            byte[] raw = new byte[4];
            RANDOM.nextBytes(raw);
            id = HexFormat.of().formatHex(raw);
        } while (taken.containsKey(id));
        return id;
    }

    /** Modification time and size: two writes in one clock tick still differ by size when a key is added or dropped. */
    private String stamp() throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        return a.lastModifiedTime() + "/" + a.size();
    }

    private Ring read() throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            Map<String, Object> doc = JSON.readValue(text, MAP_TYPE);
            if (Integer.valueOf(1).equals(doc.get("version")) && doc.get("current") instanceof String current
                    && doc.get("keys") instanceof Map<?, ?> listed) {
                Map<String, SecretKey> keys = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : listed.entrySet()) {
                    byte[] raw = e.getValue() instanceof String s ? Base64.getDecoder().decode(s) : new byte[0];
                    if (raw.length != 32) {
                        throw new IllegalArgumentException();
                    }
                    keys.put(String.valueOf(e.getKey()), new SecretKeySpec(raw, "AES"));
                }
                if (keys.containsKey(current)) {
                    return new Ring(current, Map.copyOf(keys));
                }
            }
        } catch (JacksonException | IllegalArgumentException e) {
            // reported below, without the file's content
        }
        throw new IllegalStateException("The key file " + file + " is damaged: it does not hold the hub's keys for"
                + " stored secrets. Restore it from a backup.");
    }

    private static String render(Ring ring) {
        Map<String, String> keys = new LinkedHashMap<>();
        ring.keys().forEach((id, key) -> keys.put(id, Base64.getEncoder().encodeToString(key.getEncoded())));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("current", ring.current());
        doc.put("keys", keys);
        return JSON.writeValueAsString(doc) + "\n";
    }
}

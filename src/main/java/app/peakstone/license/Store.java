package app.peakstone.license;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The two small files the SDK keeps in the plugin's data directory. */
final class Store {
    static final String CACHE_FILE = "peakstone-license.json";
    static final String INSTANCE_FILE = "peakstone-instance";

    private static final long MAX_CACHE_BYTES = 64 * 1024;

    private final Path dir;

    Store(Path dir) {
        this.dir = dir;
    }

    /** The stable random id of this server: read from disk, or generated and saved on first use. */
    String instanceId() throws IOException {
        Path file = dir.resolve(INSTANCE_FILE);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).strip();
            UUID id = UUID.fromString(text);
            if (id.toString().equals(text.toLowerCase(Locale.ROOT))) {
                return id.toString();
            }
        } catch (NoSuchFileException | CharacterCodingException | IllegalArgumentException e) {
            // missing or unusable: create a fresh one below
        }
        String id = UUID.randomUUID().toString();
        writeAtomic(file, id.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    /** The last stored response, or empty if there is none or it cannot be read. */
    Optional<Wire.Signed> loadCache() {
        Path file = dir.resolve(CACHE_FILE);
        try {
            if (Files.size(file) > MAX_CACHE_BYTES) {
                return Optional.empty();
            }
            return Optional.of(Wire.Signed.parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | Rejection e) {
            return Optional.empty();
        }
    }

    void saveCache(Wire.Signed response) throws IOException {
        writeAtomic(dir.resolve(CACHE_FILE), response.toJson().getBytes(StandardCharsets.UTF_8));
    }

    void deleteCache() throws IOException {
        Files.deleteIfExists(dir.resolve(CACHE_FILE));
    }

    /**
     * Writes {@code bytes} to a temporary file next to {@code target}, flushes it to disk and
     * moves it into place, so readers see either the old or the new content, never a partial file.
     * The temporary file is removed again if anything fails.
     */
    static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".peakstone-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

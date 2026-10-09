package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
    @TempDir
    Path dir;

    private List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void instanceIdIsCreatedOnFirstUseAndReusedAfter() throws IOException {
        Store store = new Store(dir);

        String first = store.instanceId();

        assertEquals(first, UUID.fromString(first).toString());
        assertEquals(first, Files.readString(dir.resolve(Store.INSTANCE_FILE)).strip());
        assertEquals(first, store.instanceId());
        assertEquals(first, new Store(dir).instanceId());
        assertEquals(List.of(Store.INSTANCE_FILE), names());
    }

    @Test
    void differentDirectoriesGetDifferentInstanceIds() throws IOException {
        Path other = Files.createDirectory(dir.resolve("other"));

        assertNotEquals(new Store(dir).instanceId(), new Store(other).instanceId());
    }

    @Test
    void anExistingValidInstanceIdIsKept() throws IOException {
        Files.writeString(dir.resolve(Store.INSTANCE_FILE), "7a3b9d1e-5c2f-4e8a-9b6d-1f0e2d3c4b5a\n");

        assertEquals("7a3b9d1e-5c2f-4e8a-9b6d-1f0e2d3c4b5a", new Store(dir).instanceId());
    }

    @Test
    void anUnusableInstanceFileIsReplaced() throws IOException {
        for (String junk : new String[] {"", "garbage", "1-1-1-1-1", "7a3b9d1e5c2f4e8a9b6d1f0e2d3c4b5a"}) {
            Files.writeString(dir.resolve(Store.INSTANCE_FILE), junk);

            String id = new Store(dir).instanceId();

            assertEquals(id, UUID.fromString(id).toString(), junk);
            assertEquals(id, Files.readString(dir.resolve(Store.INSTANCE_FILE)).strip(), junk);
        }
        Files.write(dir.resolve(Store.INSTANCE_FILE), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd});
        String id = new Store(dir).instanceId();
        assertEquals(id, UUID.fromString(id).toString());
    }

    @Test
    void writeAtomicReplacesTheTargetAndLeavesNothingBehind() throws IOException {
        Path target = dir.resolve("file.json");

        Store.writeAtomic(target, "one".getBytes(StandardCharsets.UTF_8));
        Store.writeAtomic(target, "two, a bit longer".getBytes(StandardCharsets.UTF_8));

        assertEquals("two, a bit longer", Files.readString(target));
        assertEquals(List.of("file.json"), names());
    }

    @Test
    void readersNeverSeeAPartialOrMissingFileWhileItIsReplaced() throws Exception {
        Path target = dir.resolve("file.json");
        int size = 128 * 1024;
        byte[] a = new byte[size];
        byte[] b = new byte[size];
        Arrays.fill(a, (byte) 'a');
        Arrays.fill(b, (byte) 'b');
        Store.writeAtomic(target, a);

        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    Store.writeAtomic(target, i % 2 == 0 ? b : a);
                }
            } catch (Throwable t) {
                writerFailure.set(t);
            } finally {
                done.set(true);
            }
        });

        int reads = 0;
        while (!done.get()) {
            byte[] seen;
            try {
                seen = Files.readAllBytes(target);
            } catch (NoSuchFileException e) {
                throw new AssertionError("file disappeared while being replaced", e);
            } catch (IOException e) {
                continue; // e.g. a sharing violation on Windows while the file is being swapped
            }
            assertEquals(size, seen.length, "a reader saw a partially written file");
            assertEquals(seen[0], seen[size - 1], "a reader saw a mix of old and new content");
            reads++;
        }
        writer.join();

        assertEquals(null, writerFailure.get());
        assertTrue(reads > 0);
    }

    @Test
    void writeAtomicCreatesMissingDirectories() throws IOException {
        Path target = dir.resolve("a/b/c/file.json");

        Store.writeAtomic(target, new byte[] {1, 2, 3});

        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
    }

    @Test
    void writeAtomicCleansUpWhenTheMoveFails() throws IOException {
        Path target = dir.resolve("blocked");
        Files.createDirectory(target);
        Files.writeString(target.resolve("child"), "x"); // a non-empty directory cannot be replaced

        assertThrows(IOException.class, () -> Store.writeAtomic(target, new byte[] {1}));

        assertEquals(List.of("blocked"), names());
    }

    @Test
    void cacheRoundTrips() throws Exception {
        Store store = new Store(dir);
        assertTrue(store.loadCache().isEmpty());

        Wire.Signed response = new Wire.Signed("cGF5bG9hZA", "c2ln", "ps-1");
        store.saveCache(response);

        assertEquals(response, store.loadCache().orElseThrow());
        store.deleteCache();
        assertTrue(store.loadCache().isEmpty());
        store.deleteCache(); // deleting nothing is fine
    }

    @Test
    void oversizedCacheFileIsIgnored() throws IOException {
        Files.writeString(dir.resolve(Store.CACHE_FILE), "{\"payload\":\"" + "A".repeat(100_000) + "\",\"signature\":\"x\"}");

        assertTrue(new Store(dir).loadCache().isEmpty());
    }

    @Test
    void secretsStayOutOfToString() {
        Wire.Signed signed = new Wire.Signed("c2VjcmV0LXBheWxvYWQ", "c2lnbmF0dXJl", "ps-1");

        assertEquals("Signed[keyId=ps-1]", signed.toString());
        assertFalse(signed.toString().contains("c2VjcmV0"));
    }
}

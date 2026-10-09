package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/** Shared setup: a fake Peakstone server, a controllable clock and a fresh data directory per test. */
abstract class ServerTestBase {
    static final String KEY = "PS-7K3M-9QXA-2HDF-W8ZN";
    static final String PRODUCT = "my-plugin";
    static final Instant START = Instant.parse("2026-10-05T12:00:00Z");

    /** Held strongly: java.util.logging only keeps loggers weakly, which would drop the level below. */
    private static final Logger SDK_LOGGER = Logger.getLogger("app.peakstone.license");

    @TempDir
    Path dir;

    TestClock clock;
    FakePeakstone fake;
    private Level savedLogLevel;

    @BeforeEach
    void startServer() throws IOException {
        savedLogLevel = SDK_LOGGER.getLevel();
        SDK_LOGGER.setLevel(Level.OFF); // keep the build output quiet; LoggingTest turns it back on
        clock = new TestClock(START);
        fake = new FakePeakstone(clock);
    }

    @AfterEach
    void stopServer() {
        fake.close();
        SDK_LOGGER.setLevel(savedLogLevel);
    }

    /** A builder pointing at the fake server, trusting its key as "ps-1". */
    PeakstoneLicense.Builder builder() {
        return PeakstoneLicense.builder()
                .product(PRODUCT)
                .key(KEY)
                .publicKey(FakePeakstone.KEY_ID, fake.publicKeyBase64())
                .dataDirectory(dir)
                .baseUrl(fake.baseUrl())
                .timeout(Duration.ofSeconds(5))
                .clock(clock);
    }

    PeakstoneLicense license() {
        return builder().build();
    }

    Path cacheFile() {
        return dir.resolve(Store.CACHE_FILE);
    }

    Path instanceFile() {
        return dir.resolve(Store.INSTANCE_FILE);
    }

    List<String> filesInDir() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    static <T extends LicenseResult> T as(Class<T> type, LicenseResult result) {
        return assertInstanceOf(type, result, () -> "unexpected result: " + result);
    }
}

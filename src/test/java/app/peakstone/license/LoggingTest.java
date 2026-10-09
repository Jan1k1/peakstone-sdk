package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rule 5: the full licence key must never reach a log, whatever happens. */
class LoggingTest extends ServerTestBase {
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger("app.peakstone.license");
    private final Handler handler = new Handler() {
        private final SimpleFormatter formatter = new SimpleFormatter();

        @Override
        public void publish(LogRecord record) {
            StringBuilder line = new StringBuilder(formatter.formatMessage(record));
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append('\n').append(trace);
            }
            lines.add(record.getLevel() + " " + line);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private Level previousLevel;
    private boolean previousUseParent;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        previousUseParent = logger.getUseParentHandlers();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
    }

    @AfterEach
    void releaseLogs() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
        logger.setUseParentHandlers(previousUseParent);
    }

    @Test
    void limitReachedIsLoggedAsAWarningWithTheReason() {
        fake.respond(p -> {
            p.put("valid", false);
            p.put("status", "limit_reached");
            p.put("reason", "This licence is already in use on 2 servers.");
            return p;
        });
        builder().build().verify();

        assertTrue(
                lines.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("limit") && l.contains("already in use on 2 servers")),
                lines.toString());
        for (String line : lines) {
            assertFalse(line.contains(KEY), line);
        }
    }

    @Test
    void anUnacceptablePluginVersionIsWarnedAboutWithoutTheKey() {
        builder().pluginVersion("not a version").build().verify();

        assertTrue(lines.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("plugin version")), lines.toString());
        for (String line : lines) {
            assertFalse(line.contains(KEY), line);
        }
    }

    @Test
    void noLogLineContainsTheKeyAcrossAllCodePaths() throws Exception {
        PeakstoneLicense license = license();

        license.verify(); // valid, cached
        fake.respondRaw(r -> FakePeakstone.Reply.status(503));
        license.verify(); // offline from cache (logged at INFO)
        fake.respond(p -> {
            p.put("license", "PS-AAAA-AAAA-AAAA-AAAA");
            return p;
        });
        license.verify(); // rejected (logged at DEBUG)
        fake.respondValid();
        Files.delete(cacheFile());
        Files.createDirectories(cacheFile());
        Files.writeString(cacheFile().resolve("blocker"), "x");
        license.verify(); // cache write fails (logged at WARNING)
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        builder().dataDirectory(blocker.resolve("data")).build().verify(); // instance id fails (WARNING)
        builder().build().startPeriodicChecks(Duration.ofMillis(20), r -> {
            throw new IllegalStateException("callback failure"); // WARNING with a stack trace
        }).close();
        Thread.sleep(50);

        assertTrue(lines.size() >= 4, "expected log output, got " + lines);
        for (String line : lines) {
            assertFalse(line.contains(KEY), line);
            assertFalse(line.contains("9QXA"), line);
            assertFalse(line.contains("2HDF"), line);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("PS-7K3M-****-****-W8ZN")), "masked key should be used: " + lines);
    }
}

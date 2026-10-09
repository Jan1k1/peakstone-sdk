package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.peakstone.license.FakePeakstone.Reply;
import app.peakstone.license.LicenseResult.Unavailable;
import app.peakstone.license.LicenseResult.Valid;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PeriodicChecksTest extends ServerTestBase {

    @Test
    void deliversResultsOnVirtualThreadsUntilClosed() throws Exception {
        BlockingQueue<LicenseResult> results = new LinkedBlockingQueue<>();
        Set<Boolean> onVirtualThread = ConcurrentHashMap.newKeySet();

        try (PeakstoneLicense.PeriodicChecks checks = license().startPeriodicChecks(Duration.ofMillis(50), result -> {
            onVirtualThread.add(Thread.currentThread().isVirtual());
            results.add(result);
        })) {
            as(Valid.class, results.poll(10, TimeUnit.SECONDS));
            as(Valid.class, results.poll(10, TimeUnit.SECONDS));
            assertNotNull(checks);
        }

        Thread.sleep(200); // let a check that was already running finish
        int resultsSeen = results.size();
        int requestsSeen = fake.requests.size();
        Thread.sleep(400);

        assertEquals(resultsSeen, results.size(), "no results after close()");
        assertEquals(requestsSeen, fake.requests.size(), "no requests after close()");
        assertEquals(Set.of(true), onVirtualThread);
    }

    @Test
    void firstCheckHappensAfterOneInterval() throws Exception {
        BlockingQueue<LicenseResult> results = new LinkedBlockingQueue<>();

        try (PeakstoneLicense.PeriodicChecks checks = license().startPeriodicChecks(Duration.ofMillis(700), results::add)) {
            Thread.sleep(250);
            assertEquals(0, fake.requests.size(), "the caller verifies at startup; the schedule starts after one interval");
            as(Valid.class, results.poll(10, TimeUnit.SECONDS));
            assertNotNull(checks);
        }
    }

    @Test
    void closeIsIdempotentAndStopsBeforeTheFirstCheck() throws Exception {
        PeakstoneLicense.PeriodicChecks checks = license().startPeriodicChecks(Duration.ofMillis(300), r -> {});

        checks.close();
        checks.close();
        Thread.sleep(600);

        assertEquals(0, fake.requests.size());
    }

    @Test
    void honoursRetryAfterWhenItIsLongerThanTheInterval() throws Exception {
        BlockingQueue<LicenseResult> results = new LinkedBlockingQueue<>();
        fake.respondRaw(r -> fake.requests.size() == 1 ? Reply.status(429, "retry-after", "1") : fake.valid(r));

        try (PeakstoneLicense.PeriodicChecks checks = license().startPeriodicChecks(Duration.ofMillis(50), results::add)) {
            Unavailable first = as(Unavailable.class, results.poll(10, TimeUnit.SECONDS));
            as(Valid.class, results.poll(10, TimeUnit.SECONDS));
            assertEquals(Duration.ofSeconds(1), first.retryAfter());
            assertNotNull(checks);
        }

        long gapMillis = TimeUnit.NANOSECONDS.toMillis(fake.requests.get(1).nanos() - fake.requests.get(0).nanos());
        assertTrue(gapMillis >= 900, "second check came after only " + gapMillis + " ms");
    }

    @Test
    void aFailingCallbackDoesNotStopTheChecks() throws Exception {
        BlockingQueue<LicenseResult> results = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();

        try (PeakstoneLicense.PeriodicChecks checks = license().startPeriodicChecks(Duration.ofMillis(50), result -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom (expected in this test)");
            }
            results.add(result);
        })) {
            as(Valid.class, results.poll(10, TimeUnit.SECONDS));
            assertTrue(calls.get() >= 2);
            assertNotNull(checks);
        }
    }

    @Test
    void reportsOfflineAndUnavailableAsTheyHappen() throws Exception {
        BlockingQueue<LicenseResult> results = new LinkedBlockingQueue<>();
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());
        fake.close();

        try (PeakstoneLicense.PeriodicChecks checks = license.startPeriodicChecks(Duration.ofMillis(50), results::add)) {
            as(LicenseResult.Offline.class, results.poll(10, TimeUnit.SECONDS));
            clock.advance(Duration.ofDays(4));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            LicenseResult result;
            do {
                result = results.poll(1, TimeUnit.SECONDS);
            } while (result instanceof LicenseResult.Offline && System.nanoTime() < deadline);
            as(Unavailable.class, result); // fails, instead of hanging, if the grace period never ends
            assertNotNull(checks);
        }
    }

    @Test
    void rejectsBadArguments() {
        PeakstoneLicense license = license();

        assertThrows(IllegalArgumentException.class, () -> license.startPeriodicChecks(Duration.ZERO, r -> {}));
        assertThrows(IllegalArgumentException.class, () -> license.startPeriodicChecks(Duration.ofSeconds(-1), r -> {}));
        assertThrows(NullPointerException.class, () -> license.startPeriodicChecks(null, r -> {}));
        assertThrows(NullPointerException.class, () -> license.startPeriodicChecks(Duration.ofHours(1), null));
    }
}

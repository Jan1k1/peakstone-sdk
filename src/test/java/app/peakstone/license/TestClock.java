package app.peakstone.license;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when the test says so. */
final class TestClock extends Clock {
    private volatile Instant now;

    TestClock(Instant start) {
        this.now = start;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}

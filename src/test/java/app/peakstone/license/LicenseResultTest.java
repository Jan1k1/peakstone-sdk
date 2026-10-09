package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.peakstone.license.LicenseResult.Invalid;
import app.peakstone.license.LicenseResult.Offline;
import app.peakstone.license.LicenseResult.Status;
import app.peakstone.license.LicenseResult.Unavailable;
import app.peakstone.license.LicenseResult.Valid;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LicenseResultTest {
    private static final Instant T = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void onlyValidAndOfflineAllowUse() {
        assertTrue(new Valid("Network", T, T).allowsUse());
        assertTrue(new Offline("Network", T).allowsUse());
        for (Status status : Status.values()) {
            assertFalse(new Invalid(status, "no").allowsUse(), status.name());
        }
        assertFalse(new Unavailable("down", Duration.ZERO).allowsUse());
    }

    @Test
    void statusesAreExactlyTheDocumentedOnes() {
        assertEquals(
                java.util.List.of("UNKNOWN", "INACTIVE", "REVOKED", "WRONG_PRODUCT", "LIMIT_REACHED", "BAD_SIGNATURE", "MALFORMED"),
                java.util.Arrays.stream(Status.values()).map(Enum::name).toList());
    }

    /** Compiles only if the sealed hierarchy has exactly these four cases. */
    private static String describe(LicenseResult result) {
        return switch (result) {
            case Valid v -> "valid " + v.plan();
            case Offline o -> "offline " + o.plan();
            case Invalid i -> "invalid " + i.status();
            case Unavailable u -> "unavailable " + u.retryAfter().toSeconds();
        };
    }

    @Test
    void patternMatchingSwitchIsExhaustive() {
        assertEquals("valid Network", describe(new Valid("Network", T, null)));
        assertEquals("offline Network", describe(new Offline("Network", T)));
        assertEquals("invalid REVOKED", describe(new Invalid(Status.REVOKED, "revoked")));
        assertEquals("unavailable 30", describe(new Unavailable("slow down", Duration.ofSeconds(30))));
    }

    @Test
    void invalidAlwaysHasAReason() {
        assertEquals("INACTIVE", new Invalid(Status.INACTIVE, null).reason());
        assertEquals("INACTIVE", new Invalid(Status.INACTIVE, "  ").reason());
        assertEquals("cancelled", new Invalid(Status.INACTIVE, "cancelled").reason());
        assertThrows(NullPointerException.class, () -> new Invalid(null, "x"));
    }

    @Test
    void unavailableNeverHasANullOrNegativeRetryAfter() {
        assertEquals(Duration.ZERO, new Unavailable("x", null).retryAfter());
        assertEquals(Duration.ZERO, new Unavailable("x", Duration.ofSeconds(-3)).retryAfter());
        assertEquals(Duration.ofSeconds(9), new Unavailable("x", Duration.ofSeconds(9)).retryAfter());
        assertThrows(NullPointerException.class, () -> new Unavailable(null, Duration.ZERO));
    }

    @Test
    void validAndOfflineRequireAnExpiry() {
        assertThrows(NullPointerException.class, () -> new Valid("p", null, null));
        assertThrows(NullPointerException.class, () -> new Offline("p", null));
    }
}

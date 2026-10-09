package app.peakstone.license;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of a licence check. Switch on it with pattern matching; the compiler checks that
 * every case is handled:
 *
 * <pre>{@code
 * switch (license.verify()) {
 *     case LicenseResult.Valid v       -> enableEverything(v.plan());
 *     case LicenseResult.Offline o     -> enableEverything(o.plan());
 *     case LicenseResult.Invalid i     -> disable(i.reason());
 *     case LicenseResult.Unavailable u -> warn(u.reason());
 * }
 * }</pre>
 *
 * <p>{@link #allowsUse()} is a shortcut for "{@code Valid} or {@code Offline}".
 */
public sealed interface LicenseResult {

    /**
     * Peakstone confirmed the licence just now.
     *
     * @param plan      name of the subscription plan; may be {@code null}
     * @param expiresAt until when this answer may be reused for offline checks; this is not the end
     *                  of the subscription
     * @param periodEnd end of the current paid period; may be {@code null}
     */
    record Valid(String plan, Instant expiresAt, Instant periodEnd) implements LicenseResult {
        public Valid {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * Peakstone could not be reached, but a previously received, signed and still unexpired
     * confirmation is on disk.
     *
     * @param plan      name of the subscription plan; may be {@code null}
     * @param expiresAt when the cached confirmation stops being accepted
     */
    record Offline(String plan, Instant expiresAt) implements LicenseResult {
        public Offline {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * The licence must not be used. The cause is in {@code status}: {@code UNKNOWN}, {@code
     * INACTIVE}, {@code REVOKED}, {@code WRONG_PRODUCT} and {@code LIMIT_REACHED} are signed answers from Peakstone;
     * {@code BAD_SIGNATURE} and {@code MALFORMED} mean the response could not be trusted (a proxy,
     * captive portal, wrong clock or tampering).
     *
     * @param status why the licence was rejected
     * @param reason a short human-readable sentence, safe to log (it never contains the licence key)
     */
    record Invalid(Status status, String reason) implements LicenseResult {
        public Invalid {
            Objects.requireNonNull(status, "status");
            reason = reason == null || reason.isBlank() ? status.name() : reason;
        }
    }

    /**
     * The licence could not be checked right now (network error, timeout, HTTP 429, 5xx or any other
     * non-200 answer) and there is no usable cached confirmation. This says nothing about whether
     * the licence is valid; what to do about it is up to the plugin.
     *
     * @param reason     what went wrong, safe to log
     * @param retryAfter how long Peakstone asked to wait before the next attempt, or {@link
     *                   Duration#ZERO} if it gave no hint
     */
    record Unavailable(String reason, Duration retryAfter) implements LicenseResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
        }
    }

    /** Why an {@link Invalid} result was produced. */
    enum Status {
        /** Peakstone does not know this licence key. */
        UNKNOWN,
        /** The subscription is not active (cancelled, expired or unpaid). */
        INACTIVE,
        /** The licence was revoked. */
        REVOKED,
        /** The key is valid but belongs to a different plugin. */
        WRONG_PRODUCT,
        /**
         * This server is new and the licence already runs on the most servers the plugin allows.
         * Servers that were running before keep working. The buyer can free a slot with "Reset
         * servers" on peakstone.app. Not temporary: do not retry in a loop.
         */
        LIMIT_REACHED,
        /**
         * The response was not authentic for this request: bad signature, unknown signing key,
         * a response meant for another instance, nonce or licence, or outside its validity window
         * (check the system clock).
         */
        BAD_SIGNATURE,
        /** The response could not be parsed or has an unsupported version. */
        MALFORMED
    }

    /** {@return true if the plugin may run: the result is {@link Valid} or {@link Offline}} */
    default boolean allowsUse() {
        return switch (this) {
            case Valid v -> true;
            case Offline o -> true;
            case Invalid i -> false;
            case Unavailable u -> false;
        };
    }
}
